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
import gg.embargo.EmbargoApi;
import gg.embargo.EmbargoConfig;
import gg.embargo.PlayerIdentity;
import gg.embargo.manifest.Manifest;
import gg.embargo.manifest.ManifestManager;
import gg.embargo.ui.EmbargoPanel;
import gg.embargo.ui.SyncButtonManager;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
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

    private static final String SUBMIT_URL = EmbargoApi.BASE_URL + "runelite/uploadcollectionlog";
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
    // The game transmits collection log entries to the client via script 4100;
    // script 7797 fires when the collection log interface is set up, and script
    // 2240 re-initializes the interface
    private static final int COLLECTION_DELAYED_TRANSMIT_SCRIPT = 4100;
    private static final int COLLECTION_LOG_SETUP_SCRIPT = 7797;
    private static final int COLLECTION_INIT_SCRIPT = 2240;

    private int cyclesSinceSuccessfulCall = 0;
    // Use instance field instead of static to allow proper cleanup per instance
    private final List<Map<String, Map<String, Object>>> rawClogItems = new ArrayList<>();
    private int tickCollectionLogScriptFired = -1;
    private boolean isAutoRetrieval = false;

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
        // Submit the collection log data two ticks after the last script prefire
        if (tickCollectionLogScriptFired == -1 ||
                tickCollectionLogScriptFired + 2 >= client.getTickCount()) {
            return;
        }

        tickCollectionLogScriptFired = -1;
        isAutoRetrieval = false;

        // Chat feedback is only shown for button-triggered syncs; automatic
        // captures on collection log open stay silent
        boolean manualSync = syncButtonManager.isSyncAllowed();
        syncButtonManager.setSyncAllowed(false);

        // Only submit automatically when the user has auto sync enabled - the
        // transmit script also fires when the search is opened by hand
        if (!manualSync && !config.autoSyncCollectionLog()) {
            return;
        }

        if (manifestManager.getManifest() == null) {
            if (manualSync) {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "Embargo",
                        "Failed to sync collection log. Try restarting the Embargo plugin.", "Embargo");
            }
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) {
            return;
        }

        // Snapshot everything that needs the client thread here; only the HTTP
        // submission runs on the executor
        String username = PlayerIdentity.getUsername(client);
        RuneScapeProfileType profileType = RuneScapeProfileType.getCurrent(client);
        List<Map<String, Map<String, Object>>> itemsSnapshot = new ArrayList<>(rawClogItems);

        scheduledExecutorService.execute(() -> submitTask(username, profileType, itemsSnapshot, manualSync));
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
                tickCollectionLogScriptFired = -1;
                isAutoRetrieval = false;
                embargoPanel.logOut();
                break;
        }
    }

    // Code from: WikiSync
    // Repository: https://github.com/weirdgloop/WikiSync
    // License: BSD 2-Clause License
    @Subscribe
    public void onScriptPreFired(ScriptPreFired preFired) {
        if (preFired.getScriptId() != COLLECTION_DELAYED_TRANSMIT_SCRIPT) {
            return;
        }

        // Never capture while viewing another player's collection log through
        // the POH adventure log
        if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1) {
            return;
        }

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

    // When the collection log is opened, make the server transmit every entry
    // by toggling the search (search needs the full dataset), then re-run the
    // init script so the view resets and the user never sees it.
    // Technique from WikiSync (https://github.com/weirdgloop/WikiSync,
    // BSD 2-Clause), as used by RuneProfile.
    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (event.getScriptId() != COLLECTION_LOG_SETUP_SCRIPT) {
            return;
        }

        if (!config.autoSyncCollectionLog()) {
            return;
        }

        // Viewing another player's collection log via the POH adventure log -
        // drop anything captured to avoid storing their data
        if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1) {
            rawClogItems.clear();
            return;
        }

        // The search toggle and init script below re-fire the setup script;
        // don't re-trigger while a retrieval is already underway
        if (isAutoRetrieval || tickCollectionLogScriptFired != -1) {
            return;
        }

        isAutoRetrieval = true;
        client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
        client.runScript(COLLECTION_INIT_SCRIPT);
    }

    synchronized public void submitTask(String username, RuneScapeProfileType profileType,
            List<Map<String, Map<String, Object>>> items, boolean manualSync) {
        // Do not send if slot data wasn't generated
        if (username == null || items.isEmpty()) {
            return;
        }

        PlayerProfile profileKey = new PlayerProfile(username, profileType);

        PlayerData newPlayerData = new PlayerData();
        newPlayerData.rawClogItems = items;
        PlayerData oldPlayerData = playerDataMap.computeIfAbsent(profileKey, k -> new PlayerData());

        submitPlayerData(profileKey, newPlayerData, oldPlayerData, manualSync);
    }

    private void merge(PlayerData oldPlayerData, PlayerData delta) {
        oldPlayerData.rawClogItems = delta.rawClogItems;
    }

    private void submitPlayerData(PlayerProfile profileKey, PlayerData delta, PlayerData old, boolean manualSync) {
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
                if (manualSync) {
                    clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "Embargo",
                            "Failed to upload data to Embargo.", "Embargo"));
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful()) {
                        log.debug("Failed to submit: {}", response.code());
                        if (manualSync) {
                            clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                                    "<col=ff9000>[Embargo]</col> Failed to upload collection log data.", null));
                        }
                        return;
                    }
                    merge(old, delta);
                    cyclesSinceSuccessfulCall = 0;
                    if (manualSync) {
                        clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                                "<col=ff9000>[Embargo]</col> Collection log synced successfully.", null));
                    }
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

        // Only react to the button toggle itself - reacting to every config
        // change would repeatedly re-register the button manager
        if (!event.getKey().equals("showCollectionLogSyncButton")) {
            return;
        }

        if (config.showCollectionLogSyncButton()) {
            syncButtonManager.startUp();
        } else {
            syncButtonManager.shutDown();
        }
    }
}
