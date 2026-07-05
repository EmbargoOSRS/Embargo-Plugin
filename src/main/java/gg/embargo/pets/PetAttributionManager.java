package gg.embargo.pets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gg.embargo.EmbargoApi;
import gg.embargo.EmbargoConfig;
import gg.embargo.PlayerIdentity;
import gg.embargo.manifest.ManifestManager;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attributes pet drops to their probable source.
 * <p>
 * When the "funny feeling" message fires, the pet NPC has just spawned next
 * to the player - so recent nearby NPC spawns identify the pet itself, and
 * the most recent boss kill-count message identifies what dropped it. The
 * spawn-correlation approach follows the Boomerang Bandits plugin.
 */
@Slf4j
@Singleton
public class PetAttributionManager {

    private static final String FOLLOWED_MESSAGE = "You have a funny feeling like you're being followed";
    private static final String WOULD_HAVE_BEEN_FOLLOWED_MESSAGE = "You have a funny feeling like you would have been followed";

    // Same pattern family as RuneLite's ChatCommandsPlugin (BSD 2-Clause)
    private static final Pattern KILLCOUNT_PATTERN = Pattern.compile(
            "Your (?<pre>completion count for |subdued |completed )?(?:<col=[0-9a-f]{6}>)?(?<boss>.+?)(?:</col>)? "
                    + "(?<post>(?:(?:kill|harvest|lap|completion|success) )?(?:count )?)is: ?"
                    + "<col=[0-9a-f]{6}>(?<kc>[0-9,]+)</col>");

    private static final int NEARBY_TILE_DISTANCE = 5;
    private static final long SPAWN_WINDOW_MS = 10_000L;
    private static final long BOSS_KILL_WINDOW_MS = 90_000L;

    private static final String PET_ENDPOINT = EmbargoApi.BASE_URL + "runelite/petdrop";
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
    private final ConcurrentLinkedQueue<NpcSpawnEntry> recentNpcSpawns = new ConcurrentLinkedQueue<>();

    private volatile String lastBossKillName;
    private volatile long lastBossKillTime;

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
        recentNpcSpawns.clear();
        lastBossKillName = null;
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        if (!isEnabled()) {
            return;
        }

        NPC npc = event.getNpc();
        Player localPlayer = client.getLocalPlayer();
        if (npc == null || localPlayer == null) {
            return;
        }

        WorldPoint npcLocation = npc.getWorldLocation();
        WorldPoint playerLocation = localPlayer.getWorldLocation();
        if (npcLocation == null || playerLocation == null
                || npcLocation.distanceTo(playerLocation) > NEARBY_TILE_DISTANCE) {
            return;
        }

        recentNpcSpawns.add(new NpcSpawnEntry(npc.getId(), npc.getName(), System.currentTimeMillis()));
        pruneStaleSpawns();
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE || !isEnabled()) {
            return;
        }

        String message = Text.removeTags(event.getMessage());

        Matcher matcher = KILLCOUNT_PATTERN.matcher(event.getMessage());
        if (matcher.find()) {
            lastBossKillName = matcher.group("boss");
            lastBossKillTime = System.currentTimeMillis();
        }

        boolean followed = message.contains(FOLLOWED_MESSAGE);
        boolean wouldHaveBeenFollowed = message.contains(WOULD_HAVE_BEEN_FOLLOWED_MESSAGE);
        if (!followed && !wouldHaveBeenFollowed) {
            return;
        }

        pruneStaleSpawns();
        submitAttribution(!wouldHaveBeenFollowed);
    }

    private boolean isEnabled() {
        return config.enablePetAttribution() && manifestManager.isFeatureEnabled("petAttribution");
    }

    private void submitAttribution(boolean petFollowing) {
        String rsn = PlayerIdentity.getUsername(client);
        if (rsn == null) {
            return;
        }

        String probableSource = null;
        if (lastBossKillName != null
                && System.currentTimeMillis() - lastBossKillTime < BOSS_KILL_WINDOW_MS) {
            probableSource = lastBossKillName;
        }

        JsonArray nearbySpawns = new JsonArray();
        for (NpcSpawnEntry entry : recentNpcSpawns) {
            JsonObject spawn = new JsonObject();
            spawn.addProperty("npcId", entry.getNpcId());
            if (entry.getNpcName() != null) {
                spawn.addProperty("npcName", entry.getNpcName());
            }
            nearbySpawns.add(spawn);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("rsn", rsn);
        payload.addProperty("world", client.getWorld());
        payload.addProperty("timestamp", Instant.now().toEpochMilli());
        // false = "would have been followed" (pet went to bank/inventory or duplicate)
        payload.addProperty("petFollowing", petFollowing);
        if (probableSource != null) {
            payload.addProperty("probableSource", probableSource);
        }
        payload.add("nearbySpawns", nearbySpawns);

        Request request = new Request.Builder()
                .url(PET_ENDPOINT)
                .post(RequestBody.create(JSON, payload.toString()))
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                log.debug("Failed to submit pet attribution", e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    log.debug("Pet attribution submitted (status {})", response.code());
                }
            }
        });
    }

    private void pruneStaleSpawns() {
        long cutoff = System.currentTimeMillis() - SPAWN_WINDOW_MS;
        Iterator<NpcSpawnEntry> it = recentNpcSpawns.iterator();
        while (it.hasNext()) {
            if (it.next().getTimestamp() < cutoff) {
                it.remove();
            }
        }
    }

    @Value
    private static class NpcSpawnEntry {
        int npcId;
        String npcName;
        long timestamp;
    }
}
