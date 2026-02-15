
package gg.embargo.bingo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import gg.embargo.EmbargoConfig;
import gg.embargo.bingo.dto.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import okhttp3.*;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
@Singleton
public class BingoManager {
    private static final String API_BASE = "https://embargo.gg/api/";
    private static final String BINGO_ACTIVE_ENDPOINT = API_BASE + "bingo/plugin/active";
    private static final String BINGO_DROP_ENDPOINT = API_BASE + "bingo/plugin/drop";
    private static final String BINGO_COMPLETIONS_ENDPOINT = API_BASE + "bingo/plugin/completions";
    private static final String BINGO_TRACKING_DISABLED_ENDPOINT = API_BASE + "bingo/plugin/tracking-disabled";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final int STATE_REFRESH_INTERVAL_SECONDS = 60;
    private static final int COMPLETIONS_REFRESH_INTERVAL_SECONDS = 30;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private Gson gson;

    @Inject
    private EmbargoConfig config;

    @Inject
    @Nullable
    private BingoScreenshotManager screenshotManager;

    @Getter
    private volatile List<BingoState> currentStates = new CopyOnWriteArrayList<>();

    private String getEmbargoTag() {
        java.awt.Color color = config.embargoMessageColor();
        String hex = String.format("%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
        return "<col=" + hex + ">[Embargo]</col>";
    }

    @Deprecated
    public BingoState getCurrentState() {
        List<BingoState> states = currentStates;
        return states.isEmpty() ? null : states.get(0);
    }

    private final AtomicBoolean trackingActive = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Set<Integer> announcedCompletionIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<BingoDropSubmission> pendingSubmissions = new ConcurrentLinkedQueue<>();
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> stateRefreshFuture;
    private ScheduledFuture<?> completionsRefreshFuture;
    private final AtomicLong lastCompletionsFetchTime = new AtomicLong(0);
    private final List<Consumer<List<BingoState>>> stateChangeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<BingoCompletionEvent>> completionListeners = new CopyOnWriteArrayList<>();

    public void startUp() {
        if (started.getAndSet(true)) {
            log.debug("BingoManager already started");
            return;
        }

        log.debug("Starting BingoManager");

        executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "BingoManager");
            t.setDaemon(true);
            return t;
        });

        refreshBingoState();

        stateRefreshFuture = executor.scheduleAtFixedRate(
                this::refreshBingoState,
                STATE_REFRESH_INTERVAL_SECONDS,
                STATE_REFRESH_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        completionsRefreshFuture = executor.scheduleAtFixedRate(
                this::fetchAndAnnounceCompletions,
                COMPLETIONS_REFRESH_INTERVAL_SECONDS,
                COMPLETIONS_REFRESH_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        trackingActive.set(true);
    }

    public void shutDown() {
        if (!started.getAndSet(false)) {
            return;
        }

        log.debug("Shutting down BingoManager");

        trackingActive.set(false);

        if (stateRefreshFuture != null) {
            stateRefreshFuture.cancel(false);
            stateRefreshFuture = null;
        }

        if (completionsRefreshFuture != null) {
            completionsRefreshFuture.cancel(false);
            completionsRefreshFuture = null;
        }

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        currentStates.clear();
        announcedCompletionIds.clear();
        pendingSubmissions.clear();
        stateChangeListeners.clear();
        completionListeners.clear();
    }

    public void addStateChangeListener(Consumer<List<BingoState>> listener) {
        stateChangeListeners.add(listener);
    }

    public void removeStateChangeListener(Consumer<List<BingoState>> listener) {
        stateChangeListeners.remove(listener);
    }

    public void addCompletionListener(Consumer<BingoCompletionEvent> listener) {
        completionListeners.add(listener);
    }

    public void removeCompletionListener(Consumer<BingoCompletionEvent> listener) {
        completionListeners.remove(listener);
    }

    public boolean isEnrolledAndActive() {
        return currentStates.stream()
                .anyMatch(state -> state.isEnrolled() && state.isActive());
    }

    public List<BingoState> getActiveEnrolledStates() {
        return currentStates.stream()
                .filter(state -> state.isEnrolled() && state.isActive())
                .collect(Collectors.toList());
    }

    @Nullable
    public BingoState getStateByBoardId(int boardId) {
        return currentStates.stream()
                .filter(state -> state.getId() == boardId)
                .findFirst()
                .orElse(null);
    }

    public boolean shouldTrackDrops() {
        return trackingActive.get()
                && config.enableBingo()
                && config.enableBingoTracking()
                && isEnrolledAndActive();
    }

    public List<BingoState> getTrackingStates() {
        if (!trackingActive.get() || !config.enableBingo() || !config.enableBingoTracking()) {
            return Collections.emptyList();
        }
        return getActiveEnrolledStates();
    }

    @Deprecated
    @Nullable
    public String getCodeword() {
        return currentStates.stream()
                .filter(state -> state.isEnrolled() && state.isActive())
                .map(BingoState::getCodeword)
                .filter(codeword -> codeword != null && !codeword.isEmpty())
                .findFirst()
                .orElse(null);
    }

    public Map<String, String> getCodewords() {
        Map<String, String> codewords = new LinkedHashMap<>();
        for (BingoState state : currentStates) {
            if (state.isEnrolled() && state.isActive() && state.getCodeword() != null && !state.getCodeword().isEmpty()) {
                codewords.put(state.getName(), state.getCodeword());
            }
        }
        return codewords;
    }

    public void refreshBingoState() {
        if (client == null || client.getLocalPlayer() == null) {
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        String username = client.getLocalPlayer().getName();
        if (username == null || username.isEmpty()) {
            return;
        }

        fetchBingoStateAsync(username);
    }

    private void fetchBingoStateAsync(String username) {
        String url = BINGO_ACTIVE_ENDPOINT + "?rsn=" + username;

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.debug("Failed to fetch bingo state: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        if (response.code() == 404) {
                            log.debug("Bingo state: No active bingo (404)");
                            updateStates(Collections.emptyList());
                        } else {
                            log.warn("Bingo state request failed with code: {}", response.code());
                        }
                        return;
                    }

                    String json = response.body().string();
                    log.debug("Bingo state response: {}", json != null ? json.substring(0, Math.min(200, json.length())) : "null");

                    if (json == null || json.isEmpty() || json.equals("null")) {
                        log.debug("Bingo state: Empty response");
                        updateStates(Collections.emptyList());
                        return;
                    }

                    List<BingoState> newStates = parseBingoStates(json);
                    for (BingoState state : newStates) {
                        log.debug("Bingo state loaded: {} (id={}, enrolled={}, active={})",
                                state.getName(), state.getId(), state.isEnrolled(), state.isActive());
                    }
                    if (newStates.isEmpty()) {
                        log.debug("Bingo state: Parsed to empty list");
                    }
                    updateStates(newStates);
                } catch (Exception e) {
                    log.error("Error parsing bingo state", e);
                }
            }
        });
    }

    private List<BingoState> parseBingoStates(String json) {
        try {
            BingoApiResponse response = gson.fromJson(json, BingoApiResponse.class);
            if (response == null) {
                return Collections.emptyList();
            }

            if (response.active != null && !response.active) {
                return Collections.emptyList();
            }

            List<BingoState> states = new ArrayList<>();

            if (response.bingos != null && !response.bingos.isEmpty()) {
                for (BingoStateDto dto : response.bingos) {
                    BingoState state = BingoMapper.toState(dto);
                    if (state != null) {
                        states.add(state);
                    }
                }
            } else if (response.id != null && response.id > 0) {
                // Single bingo response (backward compatibility)
                BingoStateDto dto = new BingoStateDto();
                dto.id = response.id;
                dto.name = response.name;
                dto.description = response.description;
                dto.size = response.size != null ? response.size : 5;
                dto.startDate = response.startDate;
                dto.endDate = response.endDate;
                dto.status = response.status;
                dto.codeword = response.codeword;
                dto.tiles = response.tiles;
                dto.userTeam = response.userTeam;
                dto.teamProgress = response.teamProgress;

                BingoState state = BingoMapper.toState(dto);
                if (state != null) {
                    states.add(state);
                }
            }

            return states;
        } catch (Exception e) {
            log.error("Error parsing bingo states JSON", e);
            return Collections.emptyList();
        }
    }

    private void updateStates(List<BingoState> newStates) {
        List<BingoState> oldStates = currentStates;
        currentStates = newStates != null ? new CopyOnWriteArrayList<>(newStates) : new CopyOnWriteArrayList<>();

        if (!oldStates.equals(currentStates)) {
            for (Consumer<List<BingoState>> listener : stateChangeListeners) {
                try {
                    listener.accept(currentStates);
                } catch (Exception e) {
                    log.error("Error in state change listener", e);
                }
            }
        }
    }

    void submitDrop(BingoState state, int tileId, int itemId, String itemName,
            int quantity, String source, boolean fromCollectionLog, boolean isPet) {
        if (client.getLocalPlayer() == null) {
            return;
        }
        String playerName = client.getLocalPlayer().getName();

        BingoTile tile = state.getTile(tileId);
        if (tile != null) {
            BingoTeamTileProgress progress = state.getProgress(tileId);
            int currentCount = progress != null ? progress.getCurrentCount() : 0;
            int requiredCount = tile.getRequiredCount();

            if (currentCount < requiredCount) {
                announceLocalDrop(playerName, tile.getTitle(), itemName, state.getUserTeam());
            }
        }

        boolean needsScreenshot = screenshotManager != null;

        BingoDropSubmission.BingoDropSubmissionBuilder builder = BingoDropSubmission.builder()
                .bingoBoardId(state.getId())
                .tileId(tileId)
                .playerName(playerName)
                .itemId(itemId)
                .itemName(itemName)
                .quantity(quantity)
                .source(source)
                .fromCollectionLog(fromCollectionLog)
                .isPet(isPet)
                .world(client.getWorld());

        if (needsScreenshot) {
            screenshotManager.captureBase64(base64 -> {
                submitDropAsync(builder.screenshotBase64(base64).build());
            });
        } else {
            submitDropAsync(builder.build());
        }
    }

    private void submitDropAsync(BingoDropSubmission submission) {
        if (executor == null || executor.isShutdown()) {
            pendingSubmissions.add(submission);
            return;
        }

        executor.execute(() -> {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("bingoBoardId", submission.getBingoBoardId());
                payload.addProperty("tileId", submission.getTileId());
                payload.addProperty("rsn", submission.getPlayerName());
                payload.addProperty("itemId", submission.getItemId());
                payload.addProperty("itemName", submission.getItemName());
                payload.addProperty("quantity", submission.getQuantity());
                payload.addProperty("source", submission.getSource());
                payload.addProperty("timestamp", submission.getTimestamp().toString());
                payload.addProperty("fromCollectionLog", submission.isFromCollectionLog());
                payload.addProperty("isPet", submission.isPet());
                payload.addProperty("world", submission.getWorld());

                if (submission.getScreenshotBase64() != null) {
                    payload.addProperty("screenshotBase64", submission.getScreenshotBase64());
                }

                Request request = new Request.Builder()
                        .url(BINGO_DROP_ENDPOINT)
                        .post(RequestBody.create(JSON, payload.toString()))
                        .build();

                try (Response response = okHttpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        log.debug("Successfully submitted bingo drop: {} -> tile {}",
                                submission.getItemName(), submission.getTileId());

                        refreshBingoState();
                    } else {
                        log.warn("Failed to submit bingo drop: {}", response.code());
                        pendingSubmissions.add(submission);
                    }
                }
            } catch (Exception e) {
                log.error("Error submitting bingo drop", e);
                pendingSubmissions.add(submission);
            }
        });
    }

    private void announceLocalDrop(String rsn, String tileName, String itemName, @Nullable BingoTeam team) {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        if (!config.enableBingoAlerts()) {
            return;
        }

        String teamName = team != null ? team.getName() : "your team";

        client.addChatMessage(
                ChatMessageType.GAMEMESSAGE,
                "",
                getEmbargoTag() + " <col=ffffff>" + rsn +
                        "</col> has made progress on <col=00ff00>" + tileName +
                        "</col> with <col=ffff00>" + itemName +
                        "</col> for " + teamName + "!",
                null);
    }

    private void fetchAndAnnounceCompletions() {
        if (!isEnrolledAndActive()) {
            return;
        }

        List<BingoState> activeStates = getActiveEnrolledStates();
        if (activeStates.isEmpty()) {
            return;
        }

        for (BingoState state : activeStates) {
            fetchCompletionsForBoard(state.getId());
        }
    }

    private void fetchCompletionsForBoard(int boardId) {
        if (client == null || client.getLocalPlayer() == null) {
            return;
        }

        String playerName = client.getLocalPlayer().getName();
        if (playerName == null || playerName.isEmpty()) {
            return;
        }

        long lastFetch = lastCompletionsFetchTime.get();
        StringBuilder urlBuilder = new StringBuilder(BINGO_COMPLETIONS_ENDPOINT);
        urlBuilder.append("?rsn=").append(playerName);
        urlBuilder.append("&boardId=").append(boardId);
        if (lastFetch > 0) {
            urlBuilder.append("&since=").append(lastFetch);
        }

        Request request = new Request.Builder()
                .url(urlBuilder.toString())
                .get()
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return;
            }

            String json = response.body().string();
            List<BingoCompletionDto> completions = gson.fromJson(json,
                    new TypeToken<List<BingoCompletionDto>>(){}.getType());

            if (completions == null || completions.isEmpty()) {
                return;
            }

            lastCompletionsFetchTime.set(Instant.now().toEpochMilli());

            for (BingoCompletionDto dto : completions) {
                if (announcedCompletionIds.contains(dto.id)) {
                    continue;
                }

                announcedCompletionIds.add(dto.id);

                BingoCompletionEvent completion = BingoMapper.toCompletionEvent(dto);
                if (completion == null) {
                    continue;
                }

                announceCompletion(completion);

                for (Consumer<BingoCompletionEvent> listener : completionListeners) {
                    try {
                        listener.accept(completion);
                    } catch (Exception e) {
                        log.error("Error in completion listener", e);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error fetching completions: {}", e.getMessage());
        }
    }

    private void announceCompletion(BingoCompletionEvent completion) {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        if (!config.enableBingoAlerts()) {
            return;
        }

        clientThread.invokeLater(() -> {
            String tag = getEmbargoTag();
            String message;
            if ("xp".equalsIgnoreCase(completion.getCompletionType())) {
                message = String.format(
                        "%s <col=ffffff>%s</col> completed XP tile <col=00ff00>%s</col> for %s!",
                        tag,
                        completion.getCompletedByRsn(),
                        completion.getTileTitle(),
                        completion.getTeamName());
            } else {
                message = String.format(
                        "%s <col=ffffff>%s</col> has completed <col=00ff00>%s</col> for %s!",
                        tag,
                        completion.getCompletedByRsn(),
                        completion.getTileTitle(),
                        completion.getTeamName());
            }

            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    message,
                    null);
        });
    }

    public void notifyTrackingDisabled() {
        if (client == null || client.getLocalPlayer() == null) {
            return;
        }

        String username = client.getLocalPlayer().getName();

        executor.execute(() -> {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("playerName", username);
                payload.addProperty("disabledAt", Instant.now().toString());

                Request request = new Request.Builder()
                        .url(BINGO_TRACKING_DISABLED_ENDPOINT)
                        .post(RequestBody.create(JSON, payload.toString()))
                        .build();

                try (Response response = okHttpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        log.debug("Notified server that bingo tracking was disabled for {}", username);
                    }
                }
            } catch (Exception e) {
                log.error("Error notifying tracking disabled", e);
            }
        });
    }

    public void onLogout() {
        announcedCompletionIds.clear();
        lastCompletionsFetchTime.set(0);
    }

    public void sendBingoAlert(boolean isFirstLogin) {
        List<BingoState> activeStates = getActiveEnrolledStates();
        if (activeStates.isEmpty()) {
            return;
        }

        if (!config.enableBingoAlerts()) {
            return;
        }

        clientThread.invokeLater(() -> {
            String tag = getEmbargoTag();
            for (BingoState state : activeStates) {
                String timeRemaining = state.getFormattedTimeRemaining();
                String message;
                if (isFirstLogin) {
                    message = String.format(
                            "%s <col=ffffff>%s</col> is active! It ends in <col=00ff00>%s</col>.",
                            tag,
                            state.getName(),
                            timeRemaining);
                } else {
                    message = String.format(
                            "%s <col=ffffff>%s</col> ends in <col=00ff00>%s</col>.",
                            tag,
                            state.getName(),
                            timeRemaining);
                }

                client.addChatMessage(
                        ChatMessageType.GAMEMESSAGE,
                        "",
                        message,
                        null);
            }

            // Check required game settings for accurate bingo tracking
            List<String> missingSettings = new ArrayList<>();

            if (client.getVarbitValue(VarbitID.OPTION_COLLECTION_NEW_ITEM) == 0) {
                missingSettings.add("Collection log - New addition notification");
            }
            if (client.getVarbitValue(VarbitID.OPTION_LOOTNOTIFICATION_ON) != 1) {
                missingSettings.add("Loot drop notifications");
            }
            if (client.getVarbitValue(VarbitID.OPTION_LOOTNOTIFICATION_UNTRADEABLES) != 1) {
                missingSettings.add("Untradeable loot notifications");
            }
            // CA_TASK_POPUP: 0 = enabled, 1 = disabled
            if (client.getVarbitValue(VarbitID.CA_TASK_POPUP) != 0) {
                missingSettings.add("Combat Achievement Tasks - Completion popup");
            }
            if (client.getVarbitValue(VarbitID.CA_FAILURE_NOTIFICATIONS_ENABLED) != 1) {
                missingSettings.add("Combat Achievement Tasks - Failure");
            }
            if (client.getVarbitValue(VarbitID.CA_REFAILURE_NOTIFICATIONS_ENABLED) != 1) {
                missingSettings.add("Combat Achievement Tasks - Repeat failure");
            }
            if (client.getVarbitValue(VarbitID.CA_TASK_RECOMPLETION_NOTIFICATIONS) != 1) {
                missingSettings.add("Combat Achievement Tasks - Repeat completion");
            }

            if (!missingSettings.isEmpty()) {
                client.addChatMessage(
                        ChatMessageType.GAMEMESSAGE,
                        "",
                        tag + " <col=ff0000>Warning:</col> Please enable the following in your game settings for accurate bingo tracking:",
                        null);
                for (String setting : missingSettings) {
                    client.addChatMessage(
                            ChatMessageType.GAMEMESSAGE,
                            "",
                            tag + "  - <col=ffffff>" + setting + "</col>",
                            null);
                }
            }
        });
    }
}
