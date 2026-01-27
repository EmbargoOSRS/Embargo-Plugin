/*
 * Copyright (c) 2021, andmcadams
 * modified by Sharpienero, Contronym
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package gg.embargo;

import com.google.common.collect.HashMultimap;
import com.google.gson.*;
import gg.embargo.collections.ClanData;
import gg.embargo.collections.DropActivity;
import gg.embargo.collections.PlayerAppearance;
import gg.embargo.manifest.ManifestManager;
import gg.embargo.ui.EmbargoPanel;
import gg.embargo.untrackables.UntrackableItemManager;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.task.Schedule;
import okhttp3.*;
import okio.BufferedSource;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
@Singleton
public class DataManager {
    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ClientThread clientThread;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private EmbargoPanel embargoPanel;

    @Inject
    private Gson gson;

    @Getter
    @Setter
    private HashSet<Integer> varpsToCheck;

    @Getter
    @Setter
    private HashSet<Integer> varbitsToCheck;

    @Inject
    private ManifestManager manifestManager;

    @Getter
    @Setter
    private int lastManifestVersion = -1;

    AtomicBoolean isUsernameRegistered = new AtomicBoolean(false);
    public AtomicBoolean stopTryingForAccount = new AtomicBoolean(false);

    private int[] oldVarps;

    private final HashMultimap<Integer, Integer> varpToVarbitMapping = HashMultimap.create();
    private volatile boolean varpMappingReady = false;

    private final HashMap<Integer, Integer> varbData = new HashMap<>();
    private final HashMap<Integer, Integer> varpData = new HashMap<>();
    private final HashMap<String, Integer> levelData = new HashMap<>();

    // New data structures for additional tracking
    private final HashMap<String, Long> xpData = new HashMap<>();
    private final HashMap<String, Integer> combatAchievementData = new HashMap<>();
    private final HashMap<String, Map<String, Integer>> achievementDiaryData = new HashMap<>();
    private volatile ClanData clanData = null;
    private volatile PlayerAppearance playerAppearance = null;
    private final ConcurrentLinkedQueue<DropActivity> pendingDropActivities = new ConcurrentLinkedQueue<>();

    // Combat Achievement tier IDs for script 4784 (1-6)
    // Script 4784 returns the number of tasks completed for each tier
    private static final int CA_SCRIPT_ID = 4784;
    private static final String[] CA_TIER_NAMES = {"easy", "medium", "hard", "elite", "master", "grandmaster"};
    // Tier IDs: Easy=1, Medium=2, Hard=3, Elite=4, Master=5, Grandmaster=6

    // Achievement Diary names - order matches script 2200 IDs (0-11)
    // Must match RuneProfile's AchievementDiary enum order
    private static final String[] DIARY_NAMES = {
        "Karamja",      // 0
        "Ardougne",     // 1
        "Falador",      // 2
        "Fremennik",    // 3
        "Kandarin",     // 4
        "Desert",       // 5
        "Lumbridge",    // 6
        "Morytania",    // 7
        "Varrock",      // 8
        "Wilderness",   // 9
        "Western",      // 10 (Western Provinces)
        "Kourend"       // 11
    };

    // Valuable drop threshold (100k GP)
    private static final long VALUABLE_DROP_THRESHOLD = 100000L;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    enum APIRoutes {
        MANIFEST("runelite/manifest"),
        UNTRACKABLES("untrackables"),
        CHECKREGISTRATION("checkregistration"),
        GET_PROFILE("getgear"),
        SUBMIT_LOOT("loot"),
        GET_RAID_MONSTERS_TO_TRACK_LOOT("lootBosses"),
        PREPARE_RAID("raid"),
        UPLOAD_CLOG("collectionlog"),
        MINIGAME_COMPLETE("minigame"),
        GET_MEMBER_INFO("embargo-profile"),
        BOUNTIES("bounties"),
        EVENTS("events"),
        LAST_POLL("lastpoll");

        APIRoutes(String route) {
            this.route = route;
        }

        private final String route;

        @Override
        public String toString() {
            return route;
        }
    }

    // private static final String MOCK_API_URI =
    // "https://a278d141-927f-433b-8e4b-6d994067900d.mock.pstmn.io/api/";
    private static final String API_URI = "https://embargo.gg/api/";
    private static final String MANIFEST_ENDPOINT = API_URI + APIRoutes.MANIFEST;
    private static final String UNTRACKABLE_POST_ENDPOINT = API_URI + APIRoutes.UNTRACKABLES;
    private static final String CHECK_REGISTRATION_ENDPOINT = API_URI + APIRoutes.CHECKREGISTRATION;
    private static final String GET_PROFILE_ENDPOINT = API_URI + APIRoutes.GET_PROFILE;
    private static final String SUBMIT_LOOT_ENDPOINT = API_URI + APIRoutes.SUBMIT_LOOT;
    private static final String TRACK_MONSTERS_ENDPOINT = API_URI + APIRoutes.GET_RAID_MONSTERS_TO_TRACK_LOOT;
    private static final String PREPARE_RAID_ENDPOINT = API_URI + APIRoutes.PREPARE_RAID;
    private static final String MINIGAME_COMPLETION_ENDPOINT = API_URI + APIRoutes.MINIGAME_COMPLETE;
    private static final String CLOG_UNLOCK_ENDPOINT = API_URI + APIRoutes.UPLOAD_CLOG;
    private static final String GET_MEMBER_INFO_ENDPOINT = API_URI + APIRoutes.GET_MEMBER_INFO;
    private static final String BOUNTIES_ENDPOINT = API_URI + APIRoutes.BOUNTIES;
    private static final String EVENTS_ENDPOINT = API_URI + APIRoutes.EVENTS;
    private static final String LAST_POLL_ENDPOINT = API_URI + APIRoutes.LAST_POLL;

    // Boss list from API - use volatile for thread safety and instance field for proper cleanup
    private volatile List<String> bossesToTrack = null;

    public void storeVarbitChanged(int varbIndex, int varbValue) {
        synchronized (this) {
            varbData.put(varbIndex, varbValue);
        }
    }

    public void resetVarbsAndVarpsToCheck() {
        varbitsToCheck = null;
        varpsToCheck = null;
    }

    public List<Player> getSurroundingPlayers() {
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null) {
            return Collections.emptyList();
        }
        List<Player> players = new ArrayList<>();
        for (Player player : worldView.players()) {
            players.add(player);
        }
        return players;
    }

    public boolean shouldTrackLoot(String bossName) {
        if (bossName == null || bossName.isEmpty()) {
            return false;
        }

        List<String> bosses = getTrackableBosses();
        if (bosses == null) {
            return false;
        }

        return bosses.contains(bossName);
    }

    public List<String> getTrackableBosses() {
        if (bossesToTrack != null) {
            return bossesToTrack;
        }
        okHttpClient.newCall(new Request.Builder().url(TRACK_MONSTERS_ENDPOINT).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                log.debug("Failed to get raid boss list", e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String json = response.body().string();
                        // Parse as List<String> with proper type safety
                        List<String> parsed = gson.fromJson(json,
                                new com.google.gson.reflect.TypeToken<List<String>>() {
                                }.getType());
                        if (parsed != null) {
                            bossesToTrack = parsed;
                        }
                    }
                } finally {
                    response.close();
                }
            }
        });
        return null;
    }

    /**
     * Clears the cached boss list. Called during cleanup.
     */
    public void clearBossCache() {
        bossesToTrack = null;
    }

    public void uploadCollectionLogUnlock(String item, String player) {
        JsonObject payload = getClogUploadPayload(item, player);
        // log.debug(String.valueOf(payload));

        okHttpClient.newCall(new Request.Builder().url(CLOG_UNLOCK_ENDPOINT)
                .post(RequestBody.create(JSON, payload.toString())).build()).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        log.debug("Failed to upload new clog slot to Embargo", e);
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try (response) {
                            if (response.isSuccessful()) {
                                log.debug("Successfully uploaded new collection log slot");
                            }
                        }
                    }
                });
    }

    public void uploadRaidCompletion(String raid, String message) {
        if (client == null || client.getLocalPlayer() == null) {
            return;
        }

        JsonObject payload = getRaidCompletionPayload(raid, message);
        okHttpClient.newCall(new Request.Builder().url(PREPARE_RAID_ENDPOINT)
                .post(RequestBody.create(JSON, payload.toString())).build()).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        log.debug("Failed to upload upload raid completion", e);
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try (response) {
                            if (response.isSuccessful()) {
                                log.debug("Successfully uploaded raid preparation");
                            }
                        }
                    }
                });
    }

    public void uploadMinigameCompletion(String minigameName, String message) {
        if (client == null || client.getLocalPlayer() == null) {
            return;
        }

        JsonObject payload = getMinigamePayload(minigameName, message);
        okHttpClient.newCall(new Request.Builder().url(MINIGAME_COMPLETION_ENDPOINT)
                .post(RequestBody.create(JSON, payload.toString())).build()).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        log.debug("Failed to upload upload minigame completion", e);
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try (response) {
                            if (response.isSuccessful()) {
                                log.debug("Successfully uploaded minigame preparation");
                            }
                        }
                    }
                });
    }

    private JsonObject getClogUploadPayload(String itemName, String username) {

        JsonObject payload = new JsonObject();
        payload.addProperty("playerName", username);
        payload.addProperty("itemName", itemName);

        return payload;
    }

    @NonNull
    private JsonObject getMinigamePayload(String minigame, String message) {
        var user = client.getLocalPlayer().getName();
        var world = client.getWorld();
        List<Player> players = getSurroundingPlayers();

        // convert List<Player> to JSON
        JsonArray playersJson = new JsonArray();
        for (Player player : players) {
            JsonObject playerJson = new JsonObject();
            playerJson.addProperty("name", player.getName());
            playersJson.add(playerJson);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("minigame", minigame);
        payload.addProperty("world", world);
        payload.addProperty("message", message);
        payload.addProperty("user", user);
        payload.add("players", playersJson);
        return payload;
    }

    @NonNull
    private JsonObject getRaidCompletionPayload(String raid, String message) {
        var user = client.getLocalPlayer().getName();
        List<Player> players = getSurroundingPlayers();

        // convert List<Player> to JSON
        JsonArray playersJson = new JsonArray();
        for (Player player : players) {
            JsonObject playerJson = new JsonObject();
            playerJson.addProperty("name", player.getName());
            playersJson.add(playerJson);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("raid", raid);
        payload.addProperty("message", message);
        payload.addProperty("user", user);
        payload.add("players", playersJson);
        return payload;
    }

    public CompletableFuture<JsonObject> getProfileAsync(String username, boolean isMemberInfoCall) {
        String endpoint = isMemberInfoCall
                ? GET_MEMBER_INFO_ENDPOINT + '/' + username
                : GET_PROFILE_ENDPOINT + '/' + username;
        return fetchJsonAsync(endpoint, JsonObject.class, new JsonObject(), false);
    }

    /**
     * Generic helper method to fetch JSON data from an API endpoint asynchronously
     *
     * @param endpoint     The API endpoint URL
     * @param type         The class type to parse the response into (JsonObject.class or JsonArray.class)
     * @param defaultValue The default value to return on failure or empty response
     * @param allowNull    Whether to treat null/empty responses as valid (returns null instead of defaultValue)
     * @param <T>          The type of JSON element (JsonObject or JsonArray)
     * @return CompletableFuture containing the parsed JSON response
     */
    private <T extends JsonElement> CompletableFuture<T> fetchJsonAsync(
            String endpoint, Class<T> type, T defaultValue, boolean allowNull) {
        CompletableFuture<T> future = new CompletableFuture<>();

        Request request = new Request.Builder()
                .url(endpoint)
                .get()
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                log.debug("Failed to fetch from {}: {}", endpoint, e.getMessage());
                future.complete(allowNull ? null : defaultValue);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    BufferedSource source = response.body().source();
                    String json = source.readUtf8();
                    response.close();
                    if (allowNull && (json == null || json.equals("null") || json.isEmpty())) {
                        future.complete(null);
                    } else {
                        future.complete(gson.fromJson(json, type));
                    }
                } else {
                    response.close();
                    future.complete(allowNull ? null : defaultValue);
                }
            }
        });

        return future;
    }

    /**
     * Fetches bounties from the API asynchronously
     *
     * @return CompletableFuture containing the bounties JSON response
     */
    public CompletableFuture<JsonObject> getBountiesAsync() {
        return fetchJsonAsync(BOUNTIES_ENDPOINT, JsonObject.class, new JsonObject(), false);
    }

    /**
     * Fetches events from the API asynchronously
     *
     * @return CompletableFuture containing the events JSON array response
     */
    public CompletableFuture<JsonArray> getEventsAsync() {
        return fetchJsonAsync(EVENTS_ENDPOINT, JsonArray.class, new JsonArray(), false);
    }

    /**
     * Fetches the last active poll from the API asynchronously
     *
     * @return CompletableFuture containing the poll JSON object, or null if no active poll
     */
    public CompletableFuture<JsonObject> getLastPollAsync() {
        return fetchJsonAsync(LAST_POLL_ENDPOINT, JsonObject.class, null, true);
    }

    private final AtomicBoolean apiFailureMode = new AtomicBoolean(false);
    private final AtomicLong lastApiFailure = new AtomicLong(0);
    private static final long API_RETRY_DELAY_MINUTES = 1;

    /**
     * Checks if a user is registered with Embargo asynchronously
     * 
     * @param username The username to check
     * @param callback Callback to handle the result
     */
    public void isUserRegisteredAsync(String username, Consumer<Boolean> callback) {
        if (username == null) {
            callback.accept(false);
            return;
        }

        if (stopTryingForAccount.get()) {
            callback.accept(false);
            return;
        }

        if (isUsernameRegistered.get()) {
            callback.accept(true);
            return;
        }

        log.debug("Checking if {} is registered with Embargo", username);

        // If we're in API failure mode, only retry after the delay period
        long currentTime = Instant.now().getEpochSecond();
        long failureTime = lastApiFailure.get();
        long elapsedMinutes = TimeUnit.SECONDS.toMinutes(currentTime - failureTime);

        if (apiFailureMode.get() && elapsedMinutes < API_RETRY_DELAY_MINUTES) {
            log.debug("apiFailureMode is true, skipping execution for {} minute(s)", API_RETRY_DELAY_MINUTES);
            callback.accept(false);
            return;
        }

        apiFailureMode.set(false);

        try {
            Request request = new Request.Builder()
                    .url(CHECK_REGISTRATION_ENDPOINT + "/" + username)
                    .get()
                    .build();

            okHttpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    log.error("Failed to check if {} is registered with Embargo's database", username);
                    apiFailureMode.set(true);
                    lastApiFailure.set(Instant.now().getEpochSecond());
                    callback.accept(false);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try (response) {
                        if (response.isSuccessful()) {
                            String responseBody = response.body().string();
                            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
                            if (jsonResponse != null && jsonResponse.has("message")
                                    && "registered".equals(jsonResponse.get("message").getAsString())) {
                                log.debug("{} is registered, return true", username);
                                isUsernameRegistered.set(true);
                                callback.accept(true);
                            } else {
                                log.debug("{} is NOT registered, return false", username);
                                stopTryingForAccount.set(true);
                                callback.accept(false);
                            }
                            apiFailureMode.set(false);

                        } else {
                            String responseBody = response.body().string();
                            try {
                                JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
                                if (jsonResponse != null && jsonResponse.has("message")
                                        && "not registered".equals(jsonResponse.get("message").getAsString())) {
                                    stopTryingForAccount.set(true);
                                    callback.accept(false);
                                    return;
                                }
                            } catch (Exception e) {
                                // Response is not valid JSON (e.g., 502 error page)
                                log.debug("Non-JSON error response: {}", response.code());
                            }
                            log.error("Failed to check if {} is registered with Embargo's database. Status: {}",
                                    username, response.code());
                            apiFailureMode.set(true);
                            lastApiFailure.set(Instant.now().getEpochSecond());
                            callback.accept(false);
                            isUsernameRegistered.set(false);
                        }
                    }
                }
            });
        } catch (Exception e) {
            // Log once and enter failure mode
            if (!apiFailureMode.get()) {
                log.error("Failed to check if user is registered. API may be down. Will retry in {} minutes.",
                        API_RETRY_DELAY_MINUTES, e);
                apiFailureMode.set(true);
                lastApiFailure.set(Instant.now().getEpochSecond());
                isUsernameRegistered.set(false);
            }
            callback.accept(false);
        }
    }

    public void uploadLoot(LootReceived event) {
        JsonObject payload = getJsonObject(event);

        log.debug("Uploading payload: " + payload);

        Request request = new Request.Builder()
                .url(SUBMIT_LOOT_ENDPOINT)
                .post(RequestBody.create(JSON, payload.toString()))
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                log.error("Error uploading loot", e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    if (response.isSuccessful()) {
                        log.debug("Loot uploaded successfully");
                    } else {
                        log.error("Loot upload failed with status " + response.code());
                    }
                }
            }
        });
    }

    @NonNull
    private JsonObject getJsonObject(LootReceived event) {
        Collection<ItemStack> itemStacks = event.getItems();

        var user = client.getLocalPlayer().getName();
        List<Player> players = getSurroundingPlayers();

        // convert List<Player> to JSON
        JsonArray playersJson = new JsonArray();
        for (Player player : players) {
            JsonObject playerJson = new JsonObject();
            playerJson.addProperty("name", player.getName());
            playersJson.add(playerJson);
        }

        // convert itemStacks to JSON using gson
        JsonArray itemStacksJson = new JsonArray();
        for (ItemStack itemStack : itemStacks) {
            JsonObject itemStackJson = new JsonObject();
            itemStackJson.addProperty("id", itemStack.getId());
            itemStackJson.addProperty("quantity", itemStack.getQuantity());
            itemStackJson.addProperty("price", itemManager.getItemPrice(itemStack.getId()));
            itemStackJson.addProperty("name", itemManager.getItemComposition(itemStack.getId()).getName());

            itemStacksJson.add(itemStackJson);
        }

        // convert json array to String
        String itemStacksJsonString = itemStacksJson.toString();

        // build payload with bossName and itemStacks
        JsonObject payload = new JsonObject();
        payload.addProperty("bossName", event.getName());
        payload.addProperty("user", user);
        payload.addProperty("itemStacks", itemStacksJsonString);
        payload.add("players", playersJson);
        return payload;
    }

    public void storeVarbitChangedIfNotStored(int varbIndex, int varbValue) {
        synchronized (this) {
            if (!varbData.containsKey(varbIndex))
                this.storeVarbitChanged(varbIndex, varbValue);
        }
    }

    public void storeVarpChanged(int varpIndex, int varpValue) {
        synchronized (this) {
            varpData.put(varpIndex, varpValue);
        }
    }

    public void storeVarpChangedIfNotStored(int varpIndex, int varpValue) {
        synchronized (this) {
            if (!varpData.containsKey(varpIndex))
                this.storeVarpChanged(varpIndex, varpValue);
        }
    }

    public void storeSkillChanged(String skill, int skillLevel) {
        synchronized (this) {
            levelData.put(skill, skillLevel);
        }
    }

    public void storeSkillChanged(String skill, int skillLevel, long xp) {
        synchronized (this) {
            levelData.put(skill, skillLevel);
            xpData.put(skill, xp);
        }
    }

    public void storeSkillChangedIfNotChanged(String skill, int skillLevel) {
        synchronized (this) {
            if (!levelData.containsKey(skill))
                storeSkillChanged(skill, skillLevel);
        }
    }

    public void storeSkillChangedIfNotChanged(String skill, int skillLevel, long xp) {
        synchronized (this) {
            if (!levelData.containsKey(skill))
                storeSkillChanged(skill, skillLevel, xp);
        }
    }

    // ========== New Capture Methods ==========

    /**
     * Captures combat achievement completion counts using script 4784.
     * MUST be called from the client thread.
     */
    public void captureCombatAchievements() {
        // Ensure game is fully loaded before running scripts
        if (client.getGameState() != GameState.LOGGED_IN) {
            log.info("[Embargo] captureCombatAchievements() skipped - game state is {}", client.getGameState());
            return;
        }

        // Script 4784 with tier ID (1-6) returns completion count for that tier
        int[] counts = new int[CA_TIER_NAMES.length];

        for (int tierId = 1; tierId <= CA_TIER_NAMES.length; tierId++) {
            try {
                client.runScript(CA_SCRIPT_ID, tierId);
                int[] stack = client.getIntStack();
                counts[tierId - 1] = stack[0];
                log.info("[Embargo] CA script 4784 tier {} returned stack[0]={}", tierId, stack[0]);
            } catch (Exception e) {
                log.warn("[Embargo] Failed to capture CA tier {}: {}", tierId, e.getMessage());
                counts[tierId - 1] = 0;
            }
        }

        synchronized (this) {
            for (int i = 0; i < CA_TIER_NAMES.length; i++) {
                combatAchievementData.put(CA_TIER_NAMES[i], counts[i]);
            }
        }

        log.info("[Embargo] Captured Combat Achievements: easy={}, medium={}, hard={}, elite={}, master={}, grandmaster={}",
            counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]);
    }

    /**
     * Captures achievement diary completion data using script 2200.
     * MUST be called from the client thread.
     */
    public void captureAchievementDiaries() {
        // Ensure game is fully loaded before running scripts
        if (client.getGameState() != GameState.LOGGED_IN) {
            log.info("[Embargo] captureAchievementDiaries() skipped - game state is {}", client.getGameState());
            return;
        }

        log.info("[Embargo] captureAchievementDiaries() called - capturing {} diaries, game state: {}",
            DIARY_NAMES.length, client.getGameState());

        // Script 2200 returns diary completion counts at stack indices 0, 3, 6, 9
        for (int diaryId = 0; diaryId < DIARY_NAMES.length; diaryId++) {
            try {
                client.runScript(2200, diaryId);
                int[] stack = client.getIntStack();

                // Log first 12 stack values to debug
                if (diaryId == 0) {
                    log.info("[Embargo] Script 2200 stack (first 12): [{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}]",
                        stack[0], stack[1], stack[2], stack[3], stack[4], stack[5],
                        stack[6], stack[7], stack[8], stack[9], stack[10], stack[11]);
                }

                Map<String, Integer> tiers = new HashMap<>();
                tiers.put("easy", stack[0]);
                tiers.put("medium", stack[3]);
                tiers.put("hard", stack[6]);
                tiers.put("elite", stack[9]);

                synchronized (this) {
                    achievementDiaryData.put(DIARY_NAMES[diaryId], tiers);
                }
                log.info("[Embargo] Diary {} (id={}): easy={}, medium={}, hard={}, elite={}",
                    DIARY_NAMES[diaryId], diaryId, stack[0], stack[3], stack[6], stack[9]);
            } catch (Exception e) {
                log.warn("[Embargo] Failed to capture diary data for {}: {}", DIARY_NAMES[diaryId], e.getMessage());
            }
        }

        synchronized (this) {
            log.info("[Embargo] captureAchievementDiaries() complete - achievementDiaryData size: {}", achievementDiaryData.size());
        }
    }

    public void captureClanData() {
        ClanChannel clanChannel = client.getClanChannel();
        if (clanChannel == null) {
            clanData = null;
            return;
        }

        clanData = ClanData.builder()
            .clanName(clanChannel.getName())
            .memberCount(clanChannel.getMembers().size())
            .build();
    }

    public void capturePlayerAppearance() {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            playerAppearance = null;
            return;
        }

        PlayerComposition composition = localPlayer.getPlayerComposition();
        if (composition == null) {
            playerAppearance = null;
            return;
        }

        // Get kit IDs by iterating over KitType values
        KitType[] kitTypes = KitType.values();
        int[] kitIds = new int[kitTypes.length];
        for (int i = 0; i < kitTypes.length; i++) {
            kitIds[i] = composition.getKitId(kitTypes[i]);
        }

        playerAppearance = PlayerAppearance.builder()
            .equipmentIds(composition.getEquipmentIds() != null ? composition.getEquipmentIds().clone() : new int[0])
            .kitIds(kitIds)
            .colors(composition.getColors() != null ? composition.getColors().clone() : new int[0])
            .isFemale(composition.isFemale())
            .npcTransformId(composition.getTransformedNpcId())
            .build();
    }

    public void trackValuableDrop(LootReceived event) {
        for (ItemStack item : event.getItems()) {
            long geValue = (long) itemManager.getItemPrice(item.getId()) * item.getQuantity();
            if (geValue >= VALUABLE_DROP_THRESHOLD) {
                DropActivity activity = DropActivity.builder()
                    .itemName(itemManager.getItemComposition(item.getId()).getName())
                    .itemId(item.getId())
                    .quantity(item.getQuantity())
                    .geValue(geValue)
                    .source(event.getName())
                    .sourceType(event.getType().name())
                    .timestamp(Instant.now().toEpochMilli())
                    .world(client.getWorld())
                    .build();
                pendingDropActivities.add(activity);
            }
        }
    }

    private List<DropActivity> drainDropQueue() {
        List<DropActivity> batch = new ArrayList<>();
        DropActivity activity;
        while ((activity = pendingDropActivities.poll()) != null) {
            batch.add(activity);
        }
        return batch;
    }

    // ========== End New Capture Methods ==========

    private <K, V> HashMap<K, V> clearChanges(HashMap<K, V> h) {
        HashMap<K, V> temp;
        synchronized (this) {
            if (h.isEmpty()) {
                return new HashMap<>();
            }
            temp = new HashMap<>(h);
            h.clear();
        }
        return temp;
    }

    public void clearData() {
        synchronized (this) {
            varbData.clear();
            varpData.clear();
            levelData.clear();
            xpData.clear();
            combatAchievementData.clear();
            achievementDiaryData.clear();
            clanData = null;
            playerAppearance = null;
            pendingDropActivities.clear();
        }
    }

    private boolean hasDataToPush() {
        return !(varbData.isEmpty() && varpData.isEmpty() && levelData.isEmpty()
            && xpData.isEmpty() && combatAchievementData.isEmpty() && achievementDiaryData.isEmpty()
            && clanData == null && playerAppearance == null && pendingDropActivities.isEmpty());
    }

    private JsonObject convertToJson() {
        JsonObject j = new JsonObject();
        JsonObject parent = new JsonObject();
        // We need to synchronize this to handle the case where the RuneScapeProfileType
        // changes
        synchronized (this) {
            log.info("[Embargo] convertToJson() called - CA data size: {}, Diary data size: {}",
                combatAchievementData.size(), achievementDiaryData.size());

            RuneScapeProfileType r = RuneScapeProfileType.getCurrent(client);
            HashMap<Integer, Integer> tempVarbData = clearChanges(varbData);
            HashMap<Integer, Integer> tempVarpData = clearChanges(varpData);
            HashMap<String, Integer> tempLevelData = clearChanges(levelData);
            HashMap<String, Long> tempXpData = clearChanges(xpData);
            // CA and diary data don't change often - send cached values without clearing
            // These are only updated on login or when specifically changed
            HashMap<String, Integer> tempCaData = new HashMap<>(combatAchievementData);
            HashMap<String, Map<String, Integer>> tempDiaryData = new HashMap<>(achievementDiaryData);
            List<DropActivity> tempDrops = drainDropQueue();

            log.info("[Embargo] convertToJson() - tempCaData size: {}, tempDiaryData size: {}",
                tempCaData.size(), tempDiaryData.size());

            j.add("varb", gson.toJsonTree(tempVarbData));
            j.add("varp", gson.toJsonTree(tempVarpData));
            j.add("level", gson.toJsonTree(tempLevelData));
            j.add("xp", gson.toJsonTree(tempXpData));
            j.add("combatAchievements", gson.toJsonTree(tempCaData));
            j.add("achievementDiaries", gson.toJsonTree(tempDiaryData));

            if (clanData != null) {
                j.add("clan", gson.toJsonTree(clanData));
            }
            if (playerAppearance != null) {
                j.add("appearance", gson.toJsonTree(playerAppearance));
            }
            if (!tempDrops.isEmpty()) {
                j.add("drops", gson.toJsonTree(tempDrops));
            }

            parent.addProperty("username", client.getLocalPlayer().getName());
            parent.addProperty("profile", r.name());
            parent.addProperty("version", manifestManager.getLastCheckedManifestVersion());
            parent.add("data", j);
        }
        return parent;
    }

    private void restoreData(JsonObject jObj) {
        synchronized (this) {
            if (!jObj.get("profile").getAsString().equals(RuneScapeProfileType.getCurrent(client).name())) {
                log.error("Not restoring data from failed call since the profile type has changed");
                return;
            }
            JsonObject dataObj = jObj.getAsJsonObject("data");
            JsonObject varbObj = dataObj.getAsJsonObject("varb");
            JsonObject varpObj = dataObj.getAsJsonObject("varp");
            JsonObject levelObj = dataObj.getAsJsonObject("level");
            for (String k : varbObj.keySet()) {
                this.storeVarbitChangedIfNotStored(Integer.parseInt(k), varbObj.get(k).getAsInt());
            }
            for (String k : varpObj.keySet()) {
                this.storeVarpChangedIfNotStored(Integer.parseInt(k), varpObj.get(k).getAsInt());
            }
            for (String k : levelObj.keySet()) {
                this.storeSkillChangedIfNotChanged(k, levelObj.get(k).getAsInt());
            }

            // Restore XP data
            if (dataObj.has("xp")) {
                JsonObject xpObj = dataObj.getAsJsonObject("xp");
                for (String k : xpObj.keySet()) {
                    if (!xpData.containsKey(k)) {
                        xpData.put(k, xpObj.get(k).getAsLong());
                    }
                }
            }

            // Restore Combat Achievement data
            if (dataObj.has("combatAchievements")) {
                JsonObject caObj = dataObj.getAsJsonObject("combatAchievements");
                for (String k : caObj.keySet()) {
                    if (!combatAchievementData.containsKey(k)) {
                        combatAchievementData.put(k, caObj.get(k).getAsInt());
                    }
                }
            }

            // Restore Achievement Diary data
            if (dataObj.has("achievementDiaries")) {
                JsonObject diaryObj = dataObj.getAsJsonObject("achievementDiaries");
                for (String diaryName : diaryObj.keySet()) {
                    if (!achievementDiaryData.containsKey(diaryName)) {
                        JsonObject tiers = diaryObj.getAsJsonObject(diaryName);
                        Map<String, Integer> tierMap = new HashMap<>();
                        for (String tier : tiers.keySet()) {
                            tierMap.put(tier, tiers.get(tier).getAsInt());
                        }
                        achievementDiaryData.put(diaryName, tierMap);
                    }
                }
            }

            // Restore drops (add back to queue)
            if (dataObj.has("drops")) {
                JsonArray dropsArr = dataObj.getAsJsonArray("drops");
                for (JsonElement elem : dropsArr) {
                    DropActivity drop = gson.fromJson(elem, DropActivity.class);
                    pendingDropActivities.add(drop);
                }
            }
        }
    }

    protected void submitToAPI() {
        if (!hasDataToPush() || client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null
                || stopTryingForAccount.get())
            return;

        if (RuneScapeProfileType.getCurrent(client) != RuneScapeProfileType.STANDARD)
            return;

        isUserRegisteredAsync(client.getLocalPlayer().getName(), isRegistered -> {
            if (!isRegistered) {
                return;
            }

            if (client.getGameState() == GameState.LOGIN_SCREEN || client.getGameState() == GameState.HOPPING) {
                return;
            }

            try {
                JsonObject payload = convertToJson();

                okHttpClient.newCall(new Request.Builder().url(UNTRACKABLE_POST_ENDPOINT)
                        .post(RequestBody.create(JSON, payload.toString())).build()).enqueue(new Callback() {
                            @Override
                            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                                log.error(e.getLocalizedMessage());
                                restoreData(payload);
                                log.error("Failed to submit player in submitToAPI, restoring data. Cause of failure:",
                                        e);
                            }

                            @Override
                            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                                try (response) {
                                    if (response.isSuccessful()) {
                                        log.debug("Successfully uploaded untrackable items");
                                    } else {
                                        log.error("submitToAPI onResponse returned, but without success");
                                    }
                                }
                            }
                        });
            } catch (Exception e) {
                log.error("Error preparing data for API submission", e);
            }
        });
    }

    private HashSet<Integer> parseSet(JsonArray j) {
        HashSet<Integer> h = new HashSet<>();
        for (JsonElement jObj : j) {
            h.add(jObj.getAsInt());
        }
        return h;
    }

    public void loadInitialData() {
        manifestManager.getLatestManifest();

        for (int varbIndex : varbitsToCheck) {
            storeVarbitChanged(varbIndex, client.getVarbitValue(varbIndex));
        }

        for (int varpIndex : varpsToCheck) {
            storeVarpChanged(varpIndex, client.getVarpValue(varpIndex));
        }

        // Capture skills with both level and XP
        for (Skill s : Skill.values()) {
            storeSkillChanged(s.getName(), client.getRealSkillLevel(s), client.getSkillExperience(s));
        }

        // Capture new data types
        captureCombatAchievements();
        captureAchievementDiaries();
        captureClanData();
        capturePlayerAppearance();
    }

    // Track ticks for delayed capture
    private int loginTickCounter = -1;
    private static final int TICKS_BEFORE_CAPTURE = 10; // Wait 10 ticks (~6 seconds) after login

    /**
     * Forces a full sync of all player data on login.
     * Captures all data types and immediately submits to the API.
     */
    public void forceFullSync() {
        log.info("[Embargo] forceFullSync() called");
        // Reset tick counter to trigger delayed capture
        loginTickCounter = 0;

        clientThread.invokeLater(() -> {
            if (client == null || client.getLocalPlayer() == null) {
                log.info("[Embargo] forceFullSync() - client or localPlayer is null, retrying...");
                return false;
            }

            if (client.getGameState() != GameState.LOGGED_IN) {
                log.info("[Embargo] forceFullSync() - game state is {}, retrying...", client.getGameState());
                return false;
            }

            log.info("[Embargo] forceFullSync() - Starting full data sync on login (tick counter: {})", loginTickCounter);

            // Ensure we have the latest manifest
            if (varbitsToCheck == null || varpsToCheck == null) {
                manifestManager.getLatestManifest();
            }

            // Capture all varbit data
            if (varbitsToCheck != null) {
                for (int varbIndex : varbitsToCheck) {
                    storeVarbitChanged(varbIndex, client.getVarbitValue(varbIndex));
                }
            }

            // Capture all varp data
            if (varpsToCheck != null) {
                for (int varpIndex : varpsToCheck) {
                    storeVarpChanged(varpIndex, client.getVarpValue(varpIndex));
                }
            }

            // Capture skills with both level and XP
            for (Skill s : Skill.values()) {
                storeSkillChanged(s.getName(), client.getRealSkillLevel(s), client.getSkillExperience(s));
            }

            // Capture clan and appearance immediately (don't need scripts)
            captureClanData();
            capturePlayerAppearance();

            // Don't capture CA/Diary data here - let the delayed capture handle it
            // These scripts need more time for the game to be fully loaded
            log.info("[Embargo] forceFullSync() - basic data captured, CA/Diary capture will happen after {} ticks", TICKS_BEFORE_CAPTURE);

            // Don't submit yet - wait for CA/Diary capture to complete in onGameTickForCapture()
            // The scheduled submitToAPI() will handle sending the full data
            return true;
        });
    }

    /**
     * Called on each game tick to handle delayed data capture.
     * Should be called from a GameTick subscriber.
     */
    public void onGameTickForCapture() {
        if (loginTickCounter >= 0) {
            loginTickCounter++;
            if (loginTickCounter >= TICKS_BEFORE_CAPTURE) {
                log.info("[Embargo] Delayed capture triggered after {} ticks", loginTickCounter);
                loginTickCounter = -1; // Reset to prevent repeated captures

                // Now capture CA and Diary data
                captureCombatAchievements();
                captureAchievementDiaries();

                // Submit the newly captured data
                submitToAPI();
            }
        }
    }

    // NEEDS TO BE MODIFIED TO USE NEW MANIFEST OBJECT STUFF
    protected void getManifest() {
        // log.debug("Getting manifest file...");
        try {
            Request r = new Request.Builder()
                    .url(MANIFEST_ENDPOINT)
                    .build();
            okHttpClient.newCall(r).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    log.error("Error retrieving manifest", e);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (response) {
                        if (response.isSuccessful()) {
                            if (response.body() == null) {
                                log.error("Manifest request succeeded but returned empty body");
                                return;
                            }

                            try {
                                JsonObject j = gson.fromJson(response.body().string(), JsonObject.class);
                                setVarbitsToCheck(parseSet(j.getAsJsonArray("varbits")));
                                setVarpsToCheck(parseSet(j.getAsJsonArray("varps")));
                                try {
                                    int manifestVersion = j.get("version").getAsInt();
                                    if (getLastManifestVersion() != manifestVersion) {
                                        setLastManifestVersion(manifestVersion);
                                        clientThread.invoke(() -> loadInitialData());
                                    }
                                } catch (UnsupportedOperationException | NullPointerException exception) {
                                    setLastManifestVersion(-1);
                                }
                            } catch (NullPointerException e) {
                                log.error("Manifest possibly missing varbits or varps entry from /manifest call");
                                log.error(e.getLocalizedMessage());
                            } catch (ClassCastException e) {
                                log.error("Manifest from /manifest call might have varbits or varps as not a list");
                                log.error(e.getLocalizedMessage());
                            } catch (IOException | JsonSyntaxException e) {
                                log.error(e.getLocalizedMessage());
                            }
                        } else {
                            log.error("Manifest request returned with status " + response.code());
                            if (response.body() == null) {
                                log.error("Manifest request returned empty body");
                            } else {
                                log.error(response.body().toString());
                            }
                        }
                    }
                }
            });
        } catch (IllegalArgumentException e) {
            log.error("Bad URL given: " + e.getLocalizedMessage());
        }
    }

    // NEEDS TO BE MODIFIED TO USE NEW MANIFEST OBJECT STUFF
    protected int getVersion() {
        // log.debug("Attempting to get manifest version...");
        Request request = new Request.Builder()
                .url(MANIFEST_ENDPOINT)
                .build();

        try {
            okHttpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, IOException e) {
                    log.error("Error retrieving manifest", e);
                }

                @Override
                public void onResponse(@NonNull Call call, Response response) throws IOException {
                    try (response) {
                        if (response.isSuccessful()) {
                            if (response.body() == null) {
                                log.error("Manifest request succeeded but returned empty body");
                                return;
                            }

                            try {
                                JsonObject j = gson.fromJson(response.body().string(), JsonObject.class);
                                try {
                                    int manifestVersion = j.get("version").getAsInt();
                                    if (manifestManager.getLatestManifest().getVersion() != manifestVersion) {
                                        // update to use new manifest stuff
                                        clientThread.invoke(() -> loadInitialData());
                                    }
                                } catch (UnsupportedOperationException | NullPointerException exception) {
                                    setLastManifestVersion(-1);
                                }
                            } catch (NullPointerException | ClassCastException e) {
                                log.error(e.getLocalizedMessage());
                            } catch (IOException | JsonSyntaxException e) {
                                log.error(e.getLocalizedMessage());
                            }
                        } else {
                            log.error("Manifest request returned with status " + response.code());
                            if (response.body() == null) {
                                log.error("Manifest request returned empty body");
                            } else {
                                log.error(response.body().toString());
                            }
                        }
                    }
                }
            });
        } catch (IllegalArgumentException e) {
            log.error("asd");
        }
        return -1;
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged varbitChanged) {
        if (client == null || varbitsToCheck == null || varpsToCheck == null)
            return;
        if (oldVarps == null)
            setupVarpTracking();

        int varpIndexChanged = varbitChanged.getVarpId();
        if (varpsToCheck.contains(varpIndexChanged)) {
            storeVarpChanged(varpIndexChanged, client.getVarpValue(varpIndexChanged));
        }
        if (varpMappingReady) {
            for (Integer i : varpToVarbitMapping.get(varpIndexChanged)) {
                if (!varbitsToCheck.contains(i))
                    continue;
                // For each varbit index, see if it changed.
                int oldValue = client.getVarbitValue(oldVarps, i);
                int newValue = client.getVarbitValue(i);
                if (oldValue != newValue)
                    storeVarbitChanged(i, newValue);
            }
        }
        oldVarps[varpIndexChanged] = client.getVarpValue(varpIndexChanged);
    }

    // Need to keep track of old varps and what varps each varb is in.
    // On change
    // Get varp, if varp in hashset, queue it.
    // Get each varb index in varp. If varb changed and varb in hashset, queue it.
    // Checking if varb has changed requires us to keep track of old varps
    private void setupVarpTracking() {
        final int VARBITS_ARCHIVE_ID = 14;
        // Init stuff to keep track of varb changes
        varpMappingReady = false;
        varpToVarbitMapping.clear();

        if (oldVarps == null) {
            oldVarps = new int[client.getVarps().length];
        }

        // Set oldVarps to be the current varps
        System.arraycopy(client.getVarps(), 0, oldVarps, 0, oldVarps.length);

        // For all varbits, add their ids to the multimap with the varp index as their
        // key
        clientThread.invoke(() -> {
            if (client.getIndexConfig() == null) {
                return false;
            }
            IndexDataBase indexVarbits = client.getIndexConfig();
            final int[] varbitIds = indexVarbits.getFileIds(VARBITS_ARCHIVE_ID);
            for (int id : varbitIds) {
                VarbitComposition varbit = client.getVarbit(id);
                if (varbit != null) {
                    varpToVarbitMapping.put(varbit.getIndex(), id);
                }
            }
            varpMappingReady = true;
            // Capture initial values for all tracked varbits
            if (varbitsToCheck != null) {
                for (Integer varbitId : varbitsToCheck) {
                    int value = client.getVarbitValue(varbitId);
                    if (value != 0) {
                        storeVarbitChanged(varbitId, value);
                    }
                }
            }
            return true;
        });
    }

    @Schedule(period = 5 * 60, unit = ChronoUnit.SECONDS, asynchronous = true)
    public void resyncManifest() {
        // log.debug("Attempting to resync manifest");
        if (manifestManager.getManifest().getVersion() != getLastManifestVersion()) {
            getManifest();
        }
    }

    @Schedule(period = 10, unit = ChronoUnit.SECONDS, asynchronous = true)
    public void scheduledSubmit() {
        if (stopTryingForAccount.get()) {
            return;
        }
        if (client != null
                && (client.getGameState() != GameState.HOPPING && client.getGameState() != GameState.LOGIN_SCREEN)) {
            submitToAPI();
            if (client.getLocalPlayer() != null) {
                String username = client.getLocalPlayer().getName();

                isUserRegisteredAsync(username, isRegistered -> {
                    if (isRegistered) {
                        embargoPanel.updateLoggedIn(true);
                    } else {
                        embargoPanel.isLoggedIn = false;
                        embargoPanel.updateLoggedIn(false);
                        embargoPanel.logOut();
                    }
                });
            }
        } else {
            // log.debug("User is hopping or logged out, do not send data");
            embargoPanel.isLoggedIn = false;
            embargoPanel.updateLoggedIn(false);
            embargoPanel.logOut();
        }
    }

}