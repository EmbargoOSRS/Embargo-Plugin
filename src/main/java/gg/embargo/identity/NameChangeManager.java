package gg.embargo.identity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gg.embargo.EmbargoApi;
import gg.embargo.EmbargoConfig;
import gg.embargo.manifest.ManifestManager;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Nameable;
import net.runelite.api.events.NameableNameChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Harvests RSN changes from the friends/ignore/clan lists via
 * {@link NameableNameChanged} + {@link Nameable#getPrevName()} and batches
 * them to the Embargo API so the clan database stays current without manual
 * admin work. Same technique as the Wise Old Man plugin.
 */
@Slf4j
@Singleton
public class NameChangeManager {

    private static final String NAME_CHANGE_ENDPOINT = EmbargoApi.BASE_URL + "runelite/namechanges";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long FLUSH_INTERVAL_MINUTES = 5;

    @Inject
    private EventBus eventBus;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private EmbargoConfig config;

    @Inject
    private ManifestManager manifestManager;

    @Inject
    private ScheduledExecutorService scheduledExecutorService;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<NameChange> pendingChanges = new ConcurrentLinkedQueue<>();
    private ScheduledFuture<?> flushTask;

    public void startUp() {
        if (started.getAndSet(true)) {
            return;
        }
        eventBus.register(this);
        flushTask = scheduledExecutorService.scheduleAtFixedRate(this::flush,
                FLUSH_INTERVAL_MINUTES, FLUSH_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    public void shutDown() {
        if (!started.getAndSet(false)) {
            return;
        }
        eventBus.unregister(this);
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
        }
        // Push anything still queued before going away
        flush();
    }

    @Subscribe
    public void onNameableNameChanged(NameableNameChanged event) {
        if (!config.enableNameChangeSync() || !manifestManager.isFeatureEnabled("nameChangeSync")) {
            return;
        }

        Nameable nameable = event.getNameable();
        String name = sanitize(nameable.getName());
        String prevName = sanitize(nameable.getPrevName());

        if (name == null || prevName == null || name.equalsIgnoreCase(prevName)) {
            return;
        }

        pendingChanges.add(new NameChange(prevName, name));
        log.debug("Queued name change: {} -> {}", prevName, name);
    }

    private void flush() {
        if (pendingChanges.isEmpty()) {
            return;
        }

        List<NameChange> batch = new ArrayList<>();
        NameChange change;
        while ((change = pendingChanges.poll()) != null) {
            batch.add(change);
        }

        JsonArray changes = new JsonArray();
        for (NameChange nc : batch) {
            JsonObject obj = new JsonObject();
            obj.addProperty("oldName", nc.getOldName());
            obj.addProperty("newName", nc.getNewName());
            changes.add(obj);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("timestamp", Instant.now().toEpochMilli());
        payload.add("changes", changes);

        Request request = new Request.Builder()
                .url(NAME_CHANGE_ENDPOINT)
                .post(RequestBody.create(JSON, payload.toString()))
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                log.debug("Failed to submit name changes, re-queueing", e);
                pendingChanges.addAll(batch);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    if (!response.isSuccessful()) {
                        log.debug("Name change submission returned status {}", response.code());
                    }
                }
            }
        });
    }

    private static String sanitize(String name) {
        if (name == null) {
            return null;
        }
        String cleaned = Text.toJagexName(Text.removeTags(name));
        return cleaned.isEmpty() ? null : cleaned;
    }

    @Value
    private static class NameChange {
        String oldName;
        String newName;
    }
}
