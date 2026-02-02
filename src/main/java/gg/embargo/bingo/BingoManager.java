
package gg.embargo.bingo;

import com.google.gson.*;
import gg.embargo.EmbargoConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Singleton
public class BingoManager {
    private static final String API_BASE = "https://embargo.gg/api/";
    private static final String BINGO_ACTIVE_ENDPOINT = API_BASE + "bingo/plugin/active";
    private static final String BINGO_DROP_ENDPOINT = API_BASE + "bingo/plugin/drop";
    private static final String BINGO_SCREENSHOT_ENDPOINT = API_BASE + "bingo/plugin/screenshot";
    private static final String BINGO_COMPLETIONS_ENDPOINT = API_BASE + "bingo/plugin/completions";
    private static final String BINGO_TRACKING_DISABLED_ENDPOINT = API_BASE + "bingo/plugin/tracking-disabled";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final Pattern COLLECTION_LOG_PATTERN = Pattern.compile(
            "New item added to your collection log: (.*)");
    private static final Pattern PET_DROP_PATTERN = Pattern.compile(
            "You have a funny feeling like you('re| would have been) being followed\\.");
    private static final Pattern PET_INSURED_PATTERN = Pattern.compile(
            "You feel something weird sneaking into your backpack\\.");

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
    private ItemManager itemManager;

    @Inject
    private EmbargoConfig config;

    @Inject
    private EventBus eventBus;

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

        log.info("Starting BingoManager");

        executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "BingoManager");
            t.setDaemon(true);
            return t;
        });

        eventBus.register(this);

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

        log.info("Shutting down BingoManager");

        trackingActive.set(false);
        eventBus.unregister(this);

        if (stateRefreshFuture != null) {
            stateRefreshFuture.cancel(false);
            stateRefreshFuture = null;
        }

        if (completionsRefreshFuture != null) {
            completionsRefreshFuture.cancel(false);
            completionsRefreshFuture = null;
        }

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
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
                        log.info("Bingo state loaded: {} (id={}, enrolled={}, active={})",
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
            JsonObject root = gson.fromJson(json, JsonObject.class);
            if (root == null) {
                return Collections.emptyList();
            }

            if (root.has("active") && !root.get("active").getAsBoolean()) {
                return Collections.emptyList();
            }

            List<BingoState> states = new ArrayList<>();

            if (root.has("bingos") && root.get("bingos").isJsonArray()) {
                JsonArray bingosArray = root.getAsJsonArray("bingos");
                for (JsonElement element : bingosArray) {
                    if (element.isJsonObject()) {
                        BingoState state = parseSingleBingoState(element.getAsJsonObject());
                        if (state != null) {
                            states.add(state);
                        }
                    }
                }
            } else {
                BingoState state = parseSingleBingoState(root);
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

    @Nullable
    private BingoState parseSingleBingoState(JsonObject obj) {
        try {
            int id = obj.has("id") ? obj.get("id").getAsInt() : 0;
            String name = obj.has("name") ? obj.get("name").getAsString() : "";
            String description = obj.has("description") && !obj.get("description").isJsonNull()
                    ? obj.get("description").getAsString()
                    : "";
            int size = obj.has("size") ? obj.get("size").getAsInt() : 5;
            String status = obj.has("status") ? obj.get("status").getAsString() : "";
            String codeword = obj.has("codeword") && !obj.get("codeword").isJsonNull()
                    ? obj.get("codeword").getAsString()
                    : null;

            Instant startDate = parseInstant(obj, "startDate");
            Instant endDate = parseInstant(obj, "endDate");

            Map<Integer, BingoTile> tiles = new HashMap<>();
            if (obj.has("tiles")) {
                JsonElement tilesElement = obj.get("tiles");
                if (tilesElement.isJsonArray()) {
                    for (JsonElement tileElement : tilesElement.getAsJsonArray()) {
                        BingoTile tile = parseTile(tileElement.getAsJsonObject());
                        if (tile != null) {
                            tiles.put(tile.getId(), tile);
                        }
                    }
                } else if (tilesElement.isJsonObject()) {
                    JsonObject tilesObj = tilesElement.getAsJsonObject();
                    for (String key : tilesObj.keySet()) {
                        BingoTile tile = parseTile(tilesObj.getAsJsonObject(key));
                        if (tile != null) {
                            tiles.put(tile.getId(), tile);
                        }
                    }
                }
            }

            BingoTeam userTeam = null;
            if (obj.has("userTeam") && !obj.get("userTeam").isJsonNull()) {
                userTeam = parseTeam(obj.getAsJsonObject("userTeam"));
            }

            Map<Integer, BingoTeamTileProgress> teamProgress = new HashMap<>();
            if (obj.has("teamProgress")) {
                JsonElement progressElement = obj.get("teamProgress");
                if (progressElement.isJsonArray()) {
                    for (JsonElement elem : progressElement.getAsJsonArray()) {
                        BingoTeamTileProgress progress = parseProgress(elem.getAsJsonObject());
                        if (progress != null) {
                            teamProgress.put(progress.getBingoTileId(), progress);
                        }
                    }
                } else if (progressElement.isJsonObject()) {
                    JsonObject progressObj = progressElement.getAsJsonObject();
                    for (String key : progressObj.keySet()) {
                        BingoTeamTileProgress progress = parseProgress(progressObj.getAsJsonObject(key));
                        if (progress != null) {
                            teamProgress.put(progress.getBingoTileId(), progress);
                        }
                    }
                }
            }

            return BingoState.builder()
                    .id(id)
                    .name(name)
                    .description(description)
                    .size(size)
                    .startDate(startDate)
                    .endDate(endDate)
                    .status(status)
                    .codeword(codeword)
                    .tiles(tiles)
                    .userTeam(userTeam)
                    .teamProgress(teamProgress)
                    .itemIdToTileIds(BingoState.buildItemLookup(tiles))
                    .build();

        } catch (Exception e) {
            log.debug("Error parsing single bingo state: {}", e.getMessage());
            return null;
        }
    }

    private Instant parseInstant(JsonObject obj, String field) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return null;
        }
        try {
            return Instant.parse(obj.get(field).getAsString());
        } catch (Exception e) {
            return null;
        }
    }

    private BingoTile parseTile(JsonObject obj) {
        try {
            int id = obj.get("id").getAsInt();
            int bingoBoardId = obj.has("bingoBoardId") ? obj.get("bingoBoardId").getAsInt() : 0;
            int position = obj.has("position") ? obj.get("position").getAsInt() : 0;
            String title = obj.has("title") ? obj.get("title").getAsString() : "";
            String description = obj.has("description") && !obj.get("description").isJsonNull()
                    ? obj.get("description").getAsString()
                    : "";
            String imageUrl = obj.has("imageUrl") && !obj.get("imageUrl").isJsonNull()
                    ? obj.get("imageUrl").getAsString()
                    : null;
            String wikiKey = obj.has("wikiKey") && !obj.get("wikiKey").isJsonNull()
                    ? obj.get("wikiKey").getAsString()
                    : null;
            int points = obj.has("points") ? obj.get("points").getAsInt() : 1;
            String tileTypeStr = obj.has("tileType") ? obj.get("tileType").getAsString() : "single";
            int requiredCount = obj.has("requiredCount") ? obj.get("requiredCount").getAsInt() : 1;

            List<BingoItemRequirement> itemRequirements = new ArrayList<>();
            if (obj.has("itemRequirements") && obj.get("itemRequirements").isJsonArray()) {
                for (JsonElement reqElement : obj.getAsJsonArray("itemRequirements")) {
                    BingoItemRequirement req = parseItemRequirement(reqElement.getAsJsonObject());
                    if (req != null) {
                        itemRequirements.add(req);
                    }
                }
            }

            List<BingoItemGroup> itemGroups = new ArrayList<>();
            if (obj.has("itemGroups") && obj.get("itemGroups").isJsonArray()) {
                for (JsonElement groupElement : obj.getAsJsonArray("itemGroups")) {
                    BingoItemGroup group = parseItemGroup(groupElement.getAsJsonObject());
                    if (group != null) {
                        itemGroups.add(group);
                    }
                }
            }

            return BingoTile.builder()
                    .id(id)
                    .bingoBoardId(bingoBoardId)
                    .position(position)
                    .title(title)
                    .description(description)
                    .imageUrl(imageUrl)
                    .wikiKey(wikiKey)
                    .points(points)
                    .tileType(BingoTileType.fromValue(tileTypeStr))
                    .requiredCount(requiredCount)
                    .itemRequirements(itemRequirements)
                    .itemGroups(itemGroups)
                    .build();
        } catch (Exception e) {
            log.debug("Error parsing tile: {}", e.getMessage());
            return null;
        }
    }

    private BingoItemRequirement parseItemRequirement(JsonObject obj) {
        try {
            return BingoItemRequirement.builder()
                    .id(obj.has("id") ? obj.get("id").getAsInt() : 0)
                    .itemGroupId(obj.has("itemGroupId") && !obj.get("itemGroupId").isJsonNull()
                            ? obj.get("itemGroupId").getAsInt()
                            : null)
                    .itemId(obj.get("itemId").getAsInt())
                    .itemName(obj.has("itemName") ? obj.get("itemName").getAsString() : "")
                    .requiredQuantity(obj.has("requiredQuantity") ? obj.get("requiredQuantity").getAsInt() : 1)
                    .isAlternative(obj.has("isAlternative") && obj.get("isAlternative").getAsBoolean())
                    .source(obj.has("source") && !obj.get("source").isJsonNull()
                            ? obj.get("source").getAsString()
                            : null)
                    .build();
        } catch (Exception e) {
            log.debug("Error parsing item requirement: {}", e.getMessage());
            return null;
        }
    }

    private BingoItemGroup parseItemGroup(JsonObject obj) {
        try {
            List<BingoItemRequirement> items = new ArrayList<>();
            if (obj.has("items") && obj.get("items").isJsonArray()) {
                for (JsonElement itemElement : obj.getAsJsonArray("items")) {
                    BingoItemRequirement item = parseItemRequirement(itemElement.getAsJsonObject());
                    if (item != null) {
                        items.add(item);
                    }
                }
            }

            return BingoItemGroup.builder()
                    .id(obj.has("id") ? obj.get("id").getAsInt() : 0)
                    .bingoTileId(obj.has("bingoTileId") ? obj.get("bingoTileId").getAsInt() : 0)
                    .groupName(obj.has("groupName") ? obj.get("groupName").getAsString() : "")
                    .requiredCount(obj.has("requiredCount") ? obj.get("requiredCount").getAsInt() : 1)
                    .sortOrder(obj.has("sortOrder") ? obj.get("sortOrder").getAsInt() : 0)
                    .items(items)
                    .build();
        } catch (Exception e) {
            log.debug("Error parsing item group: {}", e.getMessage());
            return null;
        }
    }

    private BingoTeam parseTeam(JsonObject obj) {
        try {
            List<String> members = new ArrayList<>();
            if (obj.has("members") && obj.get("members").isJsonArray()) {
                for (JsonElement memberElement : obj.getAsJsonArray("members")) {
                    if (memberElement.isJsonPrimitive()) {
                        members.add(memberElement.getAsString());
                    } else if (memberElement.isJsonObject()) {
                        JsonObject memberObj = memberElement.getAsJsonObject();
                        if (memberObj.has("rsn")) {
                            members.add(memberObj.get("rsn").getAsString());
                        }
                    }
                }
            }

            return BingoTeam.builder()
                    .id(obj.get("id").getAsInt())
                    .bingoBoardId(obj.has("bingoBoardId") ? obj.get("bingoBoardId").getAsInt() : 0)
                    .name(obj.has("name") ? obj.get("name").getAsString() : "")
                    .colorHex(obj.has("colorHex") && !obj.get("colorHex").isJsonNull()
                            ? obj.get("colorHex").getAsString()
                            : null)
                    .totalPoints(obj.has("totalPoints") ? obj.get("totalPoints").getAsInt() : 0)
                    .completedTiles(obj.has("completedTiles") ? obj.get("completedTiles").getAsInt() : 0)
                    .partialTiles(obj.has("partialTiles") ? obj.get("partialTiles").getAsInt() : 0)
                    .members(members)
                    .build();
        } catch (Exception e) {
            log.debug("Error parsing team: {}", e.getMessage());
            return null;
        }
    }

    private BingoTeamTileProgress parseProgress(JsonObject obj) {
        try {
            List<String> proofUrls = new ArrayList<>();
            if (obj.has("proofUrls") && obj.get("proofUrls").isJsonArray()) {
                for (JsonElement urlElement : obj.getAsJsonArray("proofUrls")) {
                    proofUrls.add(urlElement.getAsString());
                }
            }

            return BingoTeamTileProgress.builder()
                    .id(obj.has("id") ? obj.get("id").getAsInt() : 0)
                    .teamBingoBoardId(obj.has("teamBingoBoardId") ? obj.get("teamBingoBoardId").getAsInt() : 0)
                    .bingoTileId(obj.get("bingoTileId").getAsInt())
                    .status(BingoTileStatus.fromValue(obj.has("status") ? obj.get("status").getAsString() : "pending"))
                    .currentCount(obj.has("currentCount") ? obj.get("currentCount").getAsInt() : 0)
                    .proofUrls(proofUrls)
                    .notes(obj.has("notes") && !obj.get("notes").isJsonNull() ? obj.get("notes").getAsString() : null)
                    .completedAt(parseInstant(obj, "completedAt"))
                    .completedByRsn(obj.has("completedByRsn") && !obj.get("completedByRsn").isJsonNull()
                            ? obj.get("completedByRsn").getAsString()
                            : null)
                    .build();
        } catch (Exception e) {
            log.debug("Error parsing progress: {}", e.getMessage());
            return null;
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

    @Subscribe
    public void onLootReceived(LootReceived event) {
        if (!shouldTrackDrops()) {
            return;
        }

        LootRecordType eventType = event.getType();
        if (eventType != LootRecordType.NPC && eventType != LootRecordType.EVENT) {
            return;
        }

        if (RuneScapeProfileType.getCurrent(client) != RuneScapeProfileType.STANDARD) {
            return;
        }

        List<BingoState> trackingStates = getTrackingStates();
        if (trackingStates.isEmpty()) {
            return;
        }

        String source = event.getName();

        for (ItemStack itemStack : event.getItems()) {
            int itemId = itemStack.getId();

            ItemComposition itemComp = itemManager.getItemComposition(itemId);
            String itemName = itemComp != null ? itemComp.getName() : "Unknown";

            for (BingoState state : trackingStates) {
                Set<Integer> matchingTileIds = state.getTileIdsForItem(itemId);
                if (!matchingTileIds.isEmpty()) {
                    for (int tileId : matchingTileIds) {
                        BingoTeamTileProgress progress = state.getProgress(tileId);
                        if (progress != null && progress.isCompleted()) {
                            log.debug("Skipping drop for already completed tile {}", tileId);
                            continue;
                        }
                        submitDrop(state, tileId, itemId, itemName, itemStack.getQuantity(), source, false, false);
                    }
                }
            }
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (!shouldTrackDrops()) {
            return;
        }

        if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) {
            return;
        }

        String message = Text.removeTags(event.getMessage());

        if (PET_DROP_PATTERN.matcher(message).find() || PET_INSURED_PATTERN.matcher(message).find()) {
            handlePetDrop();
            return;
        }

        Matcher clogMatcher = COLLECTION_LOG_PATTERN.matcher(message);
        if (clogMatcher.matches()) {
            String itemName = clogMatcher.group(1);
            handleCollectionLogUnlock(itemName);
        }
    }

    private void handlePetDrop() {
        List<BingoState> trackingStates = getTrackingStates();
        if (trackingStates.isEmpty()) {
            return;
        }

        if (client.getLocalPlayer() == null) {
            return;
        }
        String playerName = client.getLocalPlayer().getName();

        for (BingoState state : trackingStates) {
            for (BingoTile tile : state.getTilesByPosition()) {
                if (tile.getTileType() == BingoTileType.PET) {
                    BingoTeamTileProgress progress = state.getProgress(tile.getId());
                    if (progress != null && progress.isCompleted()) {
                        log.debug("Skipping pet drop for already completed tile {}", tile.getId());
                        continue;
                    }

                    BingoDropSubmission submission = BingoDropSubmission.builder()
                            .bingoBoardId(state.getId())
                            .tileId(tile.getId())
                            .playerName(playerName)
                            .itemId(-1)
                            .itemName("Pet")
                            .quantity(1)
                            .source("Pet Drop")
                            .isPet(true)
                            .world(client.getWorld())
                            .build();

                    submitDropAsync(submission);

                    if (screenshotManager != null) {
                        screenshotManager.captureAndUpload(state.getId(), tile.getId(), -1, "Pet");
                    }
                }
            }
        }
    }

    private void handleCollectionLogUnlock(String itemName) {
        List<BingoState> trackingStates = getTrackingStates();
        if (trackingStates.isEmpty()) {
            return;
        }

        if (client.getLocalPlayer() == null) {
            return;
        }

        log.debug("Collection log unlock detected: {}", itemName);

        String playerName = client.getLocalPlayer().getName();

        for (BingoState state : trackingStates) {
            for (BingoTile tile : state.getTilesByPosition()) {
                BingoTeamTileProgress progress = state.getProgress(tile.getId());
                if (progress != null && progress.isCompleted()) {
                    continue;
                }

                for (BingoItemRequirement req : tile.getItemRequirements()) {
                    if (req.getItemName() != null && req.getItemName().equalsIgnoreCase(itemName)) {
                        BingoDropSubmission submission = BingoDropSubmission.builder()
                                .bingoBoardId(state.getId())
                                .tileId(tile.getId())
                                .playerName(playerName)
                                .itemId(req.getItemId())
                                .itemName(itemName)
                                .quantity(1)
                                .source("Collection Log")
                                .fromCollectionLog(true)
                                .world(client.getWorld())
                                .build();

                        submitDropAsync(submission);
                    }
                }
            }
        }
    }

    private void submitDrop(BingoState state, int tileId, int itemId, String itemName,
            int quantity, String source, boolean fromCollectionLog, boolean isPet) {
        if (client.getLocalPlayer() == null) {
            return;
        }
        String playerName = client.getLocalPlayer().getName();

        BingoDropSubmission submission = BingoDropSubmission.builder()
                .bingoBoardId(state.getId())
                .tileId(tileId)
                .playerName(playerName)
                .itemId(itemId)
                .itemName(itemName)
                .quantity(quantity)
                .source(source)
                .fromCollectionLog(fromCollectionLog)
                .isPet(isPet)
                .world(client.getWorld())
                .build();

        submitDropAsync(submission);

        BingoTile tile = state.getTile(tileId);
        if (tile != null) {
            BingoTeamTileProgress progress = state.getProgress(tileId);
            int currentCount = progress != null ? progress.getCurrentCount() : 0;
            int requiredCount = tile.getRequiredCount();

            if (currentCount < requiredCount) {
                if (screenshotManager != null && !fromCollectionLog) {
                    screenshotManager.captureAndUpload(state.getId(), tileId, itemId, itemName);
                }

                announceLocalDrop(playerName, tile.getTitle(), itemName, state.getUserTeam());
            }
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
            JsonArray completions = gson.fromJson(json, JsonArray.class);

            if (completions == null || completions.size() == 0) {
                return;
            }

            lastCompletionsFetchTime.set(Instant.now().toEpochMilli());

            for (JsonElement element : completions) {
                JsonObject obj = element.getAsJsonObject();
                int completionId = obj.get("id").getAsInt();

                if (announcedCompletionIds.contains(completionId)) {
                    continue;
                }

                announcedCompletionIds.add(completionId);

                BingoCompletionEvent completion = BingoCompletionEvent.builder()
                        .id(completionId)
                        .bingoBoardId(obj.has("bingoBoardId") ? obj.get("bingoBoardId").getAsInt() : 0)
                        .tileId(obj.has("tileId") ? obj.get("tileId").getAsInt() : 0)
                        .tileTitle(obj.has("tileTitle") ? obj.get("tileTitle").getAsString() : "")
                        .teamId(obj.has("teamId") ? obj.get("teamId").getAsInt() : 0)
                        .teamName(obj.has("teamName") ? obj.get("teamName").getAsString() : "")
                        .completedByRsn(obj.has("completedByRsn") ? obj.get("completedByRsn").getAsString() : "")
                        .completedAt(parseInstant(obj, "completedAt"))
                        .pointsAwarded(obj.has("pointsAwarded") ? obj.get("pointsAwarded").getAsInt() : 0)
                        .completionType(obj.has("completionType") ? obj.get("completionType").getAsString() : "")
                        .screenshotUrl(obj.has("screenshotUrl") && !obj.get("screenshotUrl").isJsonNull()
                                ? obj.get("screenshotUrl").getAsString()
                                : null)
                        .build();

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
                        log.info("Notified server that bingo tracking was disabled for {}", username);
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
        });
    }
}
