package gg.embargo.pbs;

import com.google.gson.JsonObject;
import gg.embargo.EmbargoApi;
import gg.embargo.EmbargoConfig;
import gg.embargo.PlayerIdentity;
import gg.embargo.manifest.ManifestManager;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks boss kill times and personal bests from game chat and submits them
 * to the Embargo API for clan speed leaderboards.
 * <p>
 * The game prints the kill-count line and the duration line separately, so
 * the two are correlated within a five-second window before submitting -
 * the same approach used by the Ruthless clan plugin.
 */
@Slf4j
@Singleton
public class PersonalBestManager {

    // These patterns come from RuneLite's ChatCommandsPlugin (BSD 2-Clause)
    private static final Pattern KILLCOUNT_PATTERN = Pattern.compile(
            "Your (?<pre>completion count for |subdued |completed )?(?:<col=[0-9a-f]{6}>)?(?<boss>.+?)(?:</col>)? "
                    + "(?<post>(?:(?:kill|harvest|lap|completion|success) )?(?:count )?)is: ?"
                    + "<col=[0-9a-f]{6}>(?<kc>[0-9,]+)</col>");
    private static final Pattern KILL_DURATION_PATTERN = Pattern.compile(
            "(?i)(?:(?:Fight |Lap |Challenge |Corrupted challenge )?duration:|Subdued in|(?<!total )completion time:) "
                    + "<col=[0-9a-f]{6}>(?<time>[0-9:.]+)</col>\\. Personal best: (?:<col=ff0000>)?"
                    + "(?<pb>[0-9:]+(?:\\.[0-9]+)?)");
    private static final Pattern NEW_PB_PATTERN = Pattern.compile(
            "(?i)(?:(?:Fight |Lap |Challenge |Corrupted challenge )?duration:|Subdued in|(?<!total )completion time:) "
                    + "<col=[0-9a-f]{6}>(?<pb>[0-9:]+(?:\\.[0-9]+)?)</col> \\(new personal best\\)");

    private static final long CORRELATION_WINDOW_MS = 5000L;
    private static final String PB_ENDPOINT = EmbargoApi.BASE_URL + "runelite/personalbest";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private EmbargoConfig config;

    @Inject
    private ManifestManager manifestManager;

    private final AtomicBoolean started = new AtomicBoolean(false);

    private String lastBoss;
    private int lastKc = -1;
    private double lastTiming = -1;
    private double lastPb = -1;
    private boolean isNewPb;
    private Instant lastKcUpdate;

    public void startUp() {
        if (started.getAndSet(true)) {
            return;
        }
        eventBus.register(this);
    }

    public void shutDown() {
        if (!started.getAndSet(false)) {
            return;
        }
        eventBus.unregister(this);
        reset();
    }

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage) {
        if (chatMessage.getType() != ChatMessageType.GAMEMESSAGE) {
            return;
        }

        if (!config.enablePbTracking() || !manifestManager.isFeatureEnabled("pbTracking")) {
            return;
        }

        String message = chatMessage.getMessage();

        Matcher matcher = KILLCOUNT_PATTERN.matcher(message);
        if (matcher.find()) {
            lastBoss = matcher.group("boss");
            lastKc = Integer.parseInt(matcher.group("kc").replace(",", ""));
            lastKcUpdate = Instant.now();
        }

        matcher = KILL_DURATION_PATTERN.matcher(message);
        if (matcher.find()) {
            isNewPb = false;
            lastTiming = timeStringToSeconds(matcher.group("time"));
            lastPb = timeStringToSeconds(matcher.group("pb"));
        }

        matcher = NEW_PB_PATTERN.matcher(message);
        if (matcher.find()) {
            isNewPb = true;
            lastTiming = timeStringToSeconds(matcher.group("pb"));
            lastPb = timeStringToSeconds(matcher.group("pb"));
        }

        if (lastBoss != null && !lastBoss.isEmpty() && lastKc > 0 && lastTiming > 0.0 && isWithinWindow()) {
            submit(lastBoss, lastKc, lastTiming, lastPb, isNewPb);
            reset();
        }
    }

    private boolean isWithinWindow() {
        return lastKcUpdate != null
                && Instant.now().toEpochMilli() - lastKcUpdate.toEpochMilli() < CORRELATION_WINDOW_MS;
    }

    private void submit(String boss, int kc, double seconds, double pbSeconds, boolean newPb) {
        String rsn = PlayerIdentity.getUsername(client);
        if (rsn == null) {
            return;
        }

        JsonObject payload = new JsonObject();
        // Client-generated id so the server can drop duplicate submissions
        payload.addProperty("guid", UUID.randomUUID().toString());
        payload.addProperty("rsn", rsn);
        payload.addProperty("boss", boss);
        payload.addProperty("kc", kc);
        payload.addProperty("seconds", seconds);
        payload.addProperty("pbSeconds", pbSeconds);
        payload.addProperty("isNewPb", newPb);
        payload.addProperty("world", client.getWorld());
        payload.addProperty("timestamp", Instant.now().toEpochMilli());

        Request request = new Request.Builder()
                .url(PB_ENDPOINT)
                .post(RequestBody.create(JSON, payload.toString()))
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                log.debug("Failed to submit personal best", e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    if (response.isSuccessful()) {
                        log.debug("Submitted kill time for {} (kc {}, {}s{})", boss, kc, seconds,
                                newPb ? ", new PB" : "");
                    } else {
                        log.debug("Personal best submission returned status {}", response.code());
                    }
                }
            }
        });
    }

    private void reset() {
        lastBoss = null;
        lastKc = -1;
        lastTiming = -1;
        lastPb = -1;
        isNewPb = false;
        lastKcUpdate = null;
    }

    static double timeStringToSeconds(String timeString) {
        String[] s = timeString.split(":");
        if (s.length == 2) {
            return Integer.parseInt(s[0]) * 60 + Double.parseDouble(s[1]);
        } else if (s.length == 3) {
            return Integer.parseInt(s[0]) * 60 * 60 + Integer.parseInt(s[1]) * 60 + Double.parseDouble(s[2]);
        }
        return Double.parseDouble(timeString);
    }
}
