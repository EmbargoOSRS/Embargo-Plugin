package gg.embargo.collections;

/*
 * Copyright (c) 2025, andmcadams
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

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import gg.embargo.EmbargoConfig;
import gg.embargo.manifest.Manifest;
import gg.embargo.manifest.ManifestManager;
import gg.embargo.ui.EmbargoPanel;
import gg.embargo.ui.SyncButtonManager;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import okhttp3.*;

import javax.inject.Inject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class CollectionLogManager {

    private final int VARBITS_ARCHIVE_ID = 14;
    private static final String PLUGIN_USER_AGENT = "Embargo Runelite Plugin";

    private static final String SUBMIT_URL = "https://embargo.gg/api/runelite/uploadcollectionlog";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // Limit playerDataMap size to prevent unbounded growth - LRU eviction
    private static final int MAX_PLAYER_DATA_CACHE_SIZE = 10;
    private final Map<PlayerProfile, PlayerData> playerDataMap = new LinkedHashMap<PlayerProfile, PlayerData>(
            MAX_PLAYER_DATA_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PlayerProfile, PlayerData> eldest) {
            return size() > MAX_PLAYER_DATA_CACHE_SIZE;
        }
    };
    private int cyclesSinceSuccessfulCall = 0;
    // Use instance field instead of static to allow proper cleanup per instance
    private final List<Map<String, Map<String, Object>>> rawClogItems = new ArrayList<>();
    private int tickCollectionLogScriptFired = -1;

    private SyncButtonManager syncButtonManager;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private ScheduledExecutorService scheduledExecutorService;

    @Inject
    private EmbargoPanel embargoPanel;

    @Inject
    private Gson gson;

    @Inject
    private EmbargoConfig config;

    @Inject
    private Manifest manifest;

    @Inject
    private ManifestManager manifestManager;

    @Inject
    private ItemManager itemManager;

    private final Client client;
    private final ClientThread clientThread;
    private final EventBus eventBus;

    @Inject
    private CollectionLogManager(
            Client client,
            ClientThread clientThread,
            EventBus eventBus) {
        this.client = client;
        this.clientThread = clientThread;
        this.eventBus = eventBus;
    }

    public void startUp(SyncButtonManager mainSyncButtonManager) {
        eventBus.register(this);
        manifestManager.getLatestManifest();
        syncButtonManager = mainSyncButtonManager;

        clientThread.invoke(() -> {
            if (client.getIndexConfig() == null || client.getGameState().ordinal() < GameState.LOGIN_SCREEN.ordinal()) {
                return false;
            }
            manifestManager.getLatestManifest();
            return true;
        });

    }

    public void shutDown() {
        eventBus.unregister(this);
        rawClogItems.clear();
        playerDataMap.clear();
        syncButtonManager.shutDown();
    }

    @Subscribe
    public void onGameTick(GameTick gameTick) {
        // Submit the collection log data two ticks after the first script prefires
        if (tickCollectionLogScriptFired != -1 &&
                tickCollectionLogScriptFired + 2 < client.getTickCount()) {
            tickCollectionLogScriptFired = -1;
            if (manifestManager.getManifest() == null) {
                client.addChatMessage(ChatMessageType.CONSOLE, "Embargo",
                        "Failed to sync collection log. Try restarting the Embargo plugin.", "Embargo");
                return;
            }
            scheduledExecutorService.execute(this::submitTask);
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {
        GameState state = gameStateChanged.getGameState();
        switch (state) {
            // When hopping or logging out, we need to clear any state related to the player
            case HOPPING:
            case LOGGING_IN:
            case CONNECTION_LOST:
            case LOGIN_SCREEN: // Add this case to handle explicit logout
                rawClogItems.clear();
                embargoPanel.logOut();
                break;
        }
    }

    // CollectionLog Subscribe
    @Subscribe
    public void onScriptPreFired(ScriptPreFired preFired) {
        if (syncButtonManager.isSyncAllowed() && preFired.getScriptId() == 4100) {
            tickCollectionLogScriptFired = client.getTickCount();
            Object[] args = preFired.getScriptEvent().getArguments();
            int itemId = (int) args[1];
            int itemCount = (int) args[2];

            String itemName;
            try {
                ItemComposition ic = itemManager.getItemComposition(itemId);
                itemName = ic.getName();
            } catch (Exception e) {
                itemName = String.valueOf(itemId);
            }

            // Remove any existing entry for this itemName
            String finalItemName = itemName;
            rawClogItems.removeIf(map -> map.containsKey(finalItemName));

            // Add the new entry
            Map<String, Object> itemData = new HashMap<>();
            itemData.put("id", itemId);
            itemData.put("quantity", itemCount);

            Map<String, Map<String, Object>> entry = new HashMap<>();
            entry.put(itemName, itemData);

            rawClogItems.add(entry);
        }
    }

    synchronized public void submitTask() {
        // If sync hasn't been toggled to be allowed
        if (!syncButtonManager.isSyncAllowed()) {
            return;
        }

        // TODO: do we want other GameStates?
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        if (client.getLocalPlayer() == null) {
            log.debug("Skipped due to local player being null");
            return;
        }

        String username = client.getLocalPlayer().getName();
        RuneScapeProfileType profileType = RuneScapeProfileType.getCurrent(client);
        PlayerProfile profileKey = new PlayerProfile(username, profileType);

        PlayerData newPlayerData = getPlayerData();
        PlayerData oldPlayerData = playerDataMap.computeIfAbsent(profileKey, k -> new PlayerData());

        // Do not send if slot data wasn't generated
        if (newPlayerData.rawClogItems.isEmpty()) {
            return;
        }

        submitPlayerData(profileKey, newPlayerData, oldPlayerData);
    }

    private PlayerData getPlayerData() {
        PlayerData out = new PlayerData();
        out.rawClogItems = rawClogItems;
        return out;
    }

    private void merge(PlayerData oldPlayerData, PlayerData delta) {
        oldPlayerData.rawClogItems = delta.rawClogItems;
    }

    private void submitPlayerData(PlayerProfile profileKey, PlayerData delta, PlayerData old) {
        // If cyclesSinceSuccessfulCall is not a perfect square, we should not try to
        // submit.
        // This gives us quadratic backoff.
        cyclesSinceSuccessfulCall += 1;
        if (Math.pow((int) Math.sqrt(cyclesSinceSuccessfulCall), 2) != cyclesSinceSuccessfulCall) {
            return;
        }

        PlayerDataSubmission submission = new PlayerDataSubmission(
                profileKey.getUsername(),
                profileKey.getProfileType().name(),
                delta);

        Request request = new Request.Builder()
                .addHeader("User-Agent", PLUGIN_USER_AGENT)
                .url(SUBMIT_URL)
                .post(RequestBody.create(JSON, gson.toJson(submission)))
                .build();

        Call call = okHttpClient.newCall(request);
        call.timeout().timeout(3, TimeUnit.SECONDS);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.debug("Failed to submit: ", e);
                clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.CONSOLE, "Embargo",
                        "Failed to upload data to Embargo.", "Embargo"));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful()) {
                        log.debug("Failed to submit: {}", response.code());
                        clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.CONSOLE, "Embargo",
                                "Failed to upload data to Embargo.", "Embargo"));
                        return;
                    }
                    merge(old, delta);
                    cyclesSinceSuccessfulCall = 0;
                    clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.CONSOLE, "Embargo",
                            "Done uploading data to Embargo.", "Embargo"));
                } finally {
                    response.close();
                }
            }
        });
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        String CONFIG_GROUP = "embargo";
        if (!event.getGroup().equals(CONFIG_GROUP)) {
            return;
        }

        if (config.showCollectionLogSyncButton()) {
            syncButtonManager.startUp();
        } else {
            syncButtonManager.shutDown();
        }
    }
}
