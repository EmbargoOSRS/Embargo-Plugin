package gg.embargo.models;

import gg.embargo.EmbargoApi;
import gg.embargo.EmbargoConfig;
import gg.embargo.PlayerIdentity;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Uploads the local player's (and optionally their pet's) 3D model to the
 * Embargo API as PLY files, following the approach used by the RuneProfile
 * plugin: models are captured on the client thread via Player#getModel() and
 * Client#getFollower(), exported with {@link ModelExporter}, and uploaded
 * asynchronously.
 */
@Slf4j
@Singleton
public class ModelUploadManager {

    private static final String MODEL_UPLOAD_ENDPOINT = EmbargoApi.BASE_URL + "runelite/models/upload";
    private static final MediaType PLY = MediaType.parse("model/ply");

    // Minimum time between automatic uploads
    private static final long UPLOAD_COOLDOWN_MS = 60_000;
    // Wait for rapid equipment changes to settle before capturing
    private static final long EQUIPMENT_DEBOUNCE_MS = 2_000;
    // Delay after login before capturing, so the model is fully loaded
    private static final long LOGIN_DELAY_MS = 3_000;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private EmbargoConfig config;

    @Inject
    private EventBus eventBus;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicLong lastUploadTime = new AtomicLong(0);

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> debounceTask;

    public void startUp() {
        if (started.getAndSet(true)) {
            return;
        }

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EmbargoModelUpload");
            t.setDaemon(true);
            return t;
        });

        eventBus.register(this);
        log.debug("ModelUploadManager started");
    }

    public void shutDown() {
        if (!started.getAndSet(false)) {
            return;
        }

        eventBus.unregister(this);

        if (debounceTask != null) {
            debounceTask.cancel(false);
            debounceTask = null;
        }

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        log.debug("ModelUploadManager shut down");
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (!config.enableModelUploads()) {
            return;
        }

        if (event.getGameState() == GameState.LOGGED_IN) {
            scheduleUpload(LOGIN_DELAY_MS);
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (!config.enableModelUploads()) {
            return;
        }

        if (event.getContainerId() != InventoryID.WORN) {
            return;
        }

        scheduleDebounced();
    }

    private void scheduleDebounced() {
        ScheduledExecutorService e = executor;
        if (e == null || e.isShutdown()) {
            return;
        }

        if (debounceTask != null) {
            debounceTask.cancel(false);
        }

        debounceTask = e.schedule(this::triggerUpload, EQUIPMENT_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void scheduleUpload(long delayMs) {
        ScheduledExecutorService e = executor;
        if (e == null || e.isShutdown()) {
            return;
        }

        e.schedule(this::triggerUpload, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Manually triggers an upload (e.g. from a UI action), bypassing the
     * automatic-upload cooldown.
     */
    public void manualUpload() {
        lastUploadTime.set(0);
        triggerUpload();
    }

    private void triggerUpload() {
        if (!started.get() || !config.enableModelUploads()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastUploadTime.get() < UPLOAD_COOLDOWN_MS) {
            log.debug("Model upload skipped, cooldown active");
            return;
        }

        // Model capture must happen on the client thread
        clientThread.invokeLater(() -> {
            if (client.getGameState() != GameState.LOGGED_IN) {
                return;
            }

            // Match the rest of the plugin: only sync from standard worlds
            if (RuneScapeProfileType.getCurrent(client) != RuneScapeProfileType.STANDARD) {
                return;
            }

            Player localPlayer = client.getLocalPlayer();
            String rsn = PlayerIdentity.getUsername(client);
            if (localPlayer == null || rsn == null || rsn.isEmpty()) {
                return;
            }

            byte[] playerPly = exportModel(localPlayer.getModel());
            if (playerPly == null) {
                log.debug("Could not export player model");
                return;
            }

            byte[] petPly = null;
            if (config.includePlayerPet()) {
                NPC pet = client.getFollower();
                if (pet != null) {
                    petPly = exportModel(pet.getModel());
                }
            }

            uploadModels(rsn, playerPly, petPly);
        });
    }

    /**
     * Exports a model to PLY bytes. Must be called on the client thread.
     */
    private byte[] exportModel(Model model) {
        if (model == null) {
            return null;
        }

        try {
            return ModelExporter.toBytes(client, model);
        } catch (Exception e) {
            log.debug("Failed to export model", e);
            return null;
        }
    }

    private void uploadModels(String rsn, byte[] playerPly, byte[] petPly) {
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("rsn", rsn)
                .addFormDataPart("playerModel", "player.ply", RequestBody.create(PLY, playerPly));

        if (petPly != null) {
            builder.addFormDataPart("petModel", "pet.ply", RequestBody.create(PLY, petPly));
        }

        Request request = new Request.Builder()
                .url(MODEL_UPLOAD_ENDPOINT)
                .post(builder.build())
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                log.debug("Model upload failed", e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    if (response.isSuccessful()) {
                        lastUploadTime.set(System.currentTimeMillis());
                        log.debug("Uploaded player model for {} ({} bytes{})", rsn, playerPly.length,
                                petPly != null ? ", pet " + petPly.length + " bytes" : "");
                    } else {
                        log.debug("Model upload returned status {}", response.code());
                    }
                }
            }
        });
    }
}
