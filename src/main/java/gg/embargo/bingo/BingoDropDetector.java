
package gg.embargo.bingo;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Singleton
public class BingoDropDetector {

    private static final Pattern COLLECTION_LOG_PATTERN = Pattern.compile(
            "New item added to your collection log: (.*)");
    private static final Pattern PET_DROP_PATTERN = Pattern.compile(
            "You have a funny feeling like you('re| would have been) being followed\\.");
    private static final Pattern PET_INSURED_PATTERN = Pattern.compile(
            "You feel something weird sneaking into your backpack\\.");
    private static final Pattern UNTRADEABLE_DROP_PATTERN = Pattern.compile(
            "Untradeable drop: (.+)");
    private static final Pattern CLUE_COMPLETED_PATTERN = Pattern.compile(
            "You have completed (?<scrollCount>\\d+) (?<scrollType>\\w+) Treasure Trails?\\.");

    private static final int MAX_PET_TICKS_WAIT = 5;

    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    @Inject
    private EventBus eventBus;

    @Inject
    private BingoManager bingoManager;

    private volatile boolean pendingPetDrop = false;
    private volatile String pendingPetName = null;
    private volatile int pendingPetItemId = -1;
    private final AtomicInteger petTicksWaited = new AtomicInteger(0);

    private volatile boolean pendingClueReward = false;
    private volatile String pendingClueType = null;
    private final AtomicInteger clueTicksWaited = new AtomicInteger(0);

    private volatile boolean started = false;

    public void startUp() {
        if (started) {
            return;
        }
        started = true;
        eventBus.register(this);
    }

    public void shutDown() {
        if (!started) {
            return;
        }
        started = false;
        eventBus.unregister(this);
        resetPetState();
        resetClueState();
    }

    @Subscribe
    public void onLootReceived(LootReceived event) {
        if (!bingoManager.shouldTrackDrops()) {
            return;
        }

        LootRecordType eventType = event.getType();
        if (eventType != LootRecordType.NPC && eventType != LootRecordType.EVENT) {
            return;
        }

        if (RuneScapeProfileType.getCurrent(client) != RuneScapeProfileType.STANDARD) {
            return;
        }

        String source = event.getName();

        for (ItemStack itemStack : event.getItems()) {
            int itemId = itemStack.getId();

            ItemComposition itemComp = itemManager.getItemComposition(itemId);
            String itemName = itemComp != null ? itemComp.getName() : "Unknown";

            matchAndSubmitItem(itemId, itemName, itemStack.getQuantity(), source);
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (pendingPetDrop) {
            if (pendingPetName != null) {
                submitPetDrop(pendingPetItemId, pendingPetName);
                resetPetState();
            } else if (petTicksWaited.incrementAndGet() > MAX_PET_TICKS_WAIT) {
                submitPetDrop(-1, "Pet");
                resetPetState();
            }
        }

        if (pendingClueReward && clueTicksWaited.incrementAndGet() > 1) {
            resetClueState();
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (!bingoManager.shouldTrackDrops()) {
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

        if (pendingPetDrop && pendingPetName == null) {
            Matcher untradeableMatcher = UNTRADEABLE_DROP_PATTERN.matcher(message);
            if (untradeableMatcher.matches()) {
                resolvePetIdentity(untradeableMatcher.group(1));
                return;
            }
        }

        Matcher clueMatcher = CLUE_COMPLETED_PATTERN.matcher(message);
        if (clueMatcher.find()) {
            pendingClueReward = true;
            pendingClueType = clueMatcher.group("scrollType");
            clueTicksWaited.set(0);
            return;
        }

        Matcher clogMatcher = COLLECTION_LOG_PATTERN.matcher(message);
        if (clogMatcher.matches()) {
            String itemName = clogMatcher.group(1);
            handleCollectionLogUnlock(itemName);
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        if (event.getGroupId() != InterfaceID.TRAIL_REWARDSCREEN) {
            return;
        }

        if (!bingoManager.shouldTrackDrops() || !pendingClueReward) {
            return;
        }

        Widget clueReward = client.getWidget(InterfaceID.TrailRewardscreen.ITEMS);
        if (clueReward == null) {
            return;
        }

        Widget[] children = clueReward.getChildren();
        if (children == null) {
            return;
        }

        String source = "Clue Scroll (" + pendingClueType + ")";

        for (Widget child : children) {
            if (child == null) {
                continue;
            }

            int itemId = child.getItemId();
            int quantity = child.getItemQuantity();
            if (itemId <= -1 || quantity <= 0) {
                continue;
            }

            ItemComposition itemComp = itemManager.getItemComposition(itemId);
            String itemName = itemComp != null ? itemComp.getName() : "Unknown";

            matchAndSubmitItem(itemId, itemName, quantity, source);
        }

        resetClueState();
    }

    private void matchAndSubmitItem(int itemId, String itemName, int quantity, String source) {
        for (BingoState state : bingoManager.getTrackingStates()) {
            Set<Integer> matchingTileIds = state.getTileIdsForItem(itemId);
            for (int tileId : matchingTileIds) {
                BingoTeamTileProgress progress = state.getProgress(tileId);
                if (progress != null && progress.isCompleted()) {
                    log.debug("Skipping drop for already completed tile {}", tileId);
                    continue;
                }
                bingoManager.submitDrop(state, tileId, itemId, itemName, quantity, source, false, false);
            }
        }
    }

    private void handlePetDrop() {
        pendingPetDrop = true;
        pendingPetName = null;
        pendingPetItemId = -1;
        petTicksWaited.set(0);
    }

    private void resolvePetIdentity(String itemName) {
        List<BingoState> trackingStates = bingoManager.getTrackingStates();
        for (BingoState state : trackingStates) {
            for (BingoTile tile : state.getTilesByPosition()) {
                if (tile.getTileType() != BingoTileType.PET) {
                    continue;
                }
                for (BingoItemRequirement req : tile.getItemRequirements()) {
                    if (req.getItemName() != null && req.getItemName().equalsIgnoreCase(itemName)) {
                        pendingPetName = itemName;
                        pendingPetItemId = req.getItemId();
                        return;
                    }
                }
            }
        }
        pendingPetName = itemName;
    }

    private void submitPetDrop(int itemId, String itemName) {
        List<BingoState> trackingStates = bingoManager.getTrackingStates();
        if (trackingStates.isEmpty()) {
            return;
        }

        for (BingoState state : trackingStates) {
            for (BingoTile tile : state.getTilesByPosition()) {
                if (tile.getTileType() != BingoTileType.PET) {
                    continue;
                }

                BingoTeamTileProgress progress = state.getProgress(tile.getId());
                if (progress != null && progress.isCompleted()) {
                    log.debug("Skipping pet drop for already completed tile {}", tile.getId());
                    continue;
                }

                if (itemId != -1 && !tile.acceptsItem(itemId)) {
                    continue;
                }

                bingoManager.submitDrop(state, tile.getId(), itemId, itemName, 1, "Pet Drop", false, true);
            }
        }
    }

    private void handleCollectionLogUnlock(String itemName) {
        List<BingoState> trackingStates = bingoManager.getTrackingStates();
        if (trackingStates.isEmpty()) {
            return;
        }

        log.debug("Collection log unlock detected: {}", itemName);

        if (pendingPetDrop) {
            resolvePetIdentity(itemName);
            submitPetDrop(pendingPetItemId, pendingPetName != null ? pendingPetName : "Pet");
            resetPetState();
            return;
        }

        for (BingoState state : trackingStates) {
            for (BingoTile tile : state.getTilesByPosition()) {
                BingoTeamTileProgress progress = state.getProgress(tile.getId());
                if (progress != null && progress.isCompleted()) {
                    continue;
                }

                for (BingoItemRequirement req : tile.getItemRequirements()) {
                    if (req.getItemName() != null && req.getItemName().equalsIgnoreCase(itemName)) {
                        bingoManager.submitDrop(state, tile.getId(), req.getItemId(), itemName, 1,
                                "Collection Log", true, false);
                    }
                }
            }
        }
    }

    private void resetPetState() {
        pendingPetDrop = false;
        pendingPetName = null;
        pendingPetItemId = -1;
        petTicksWaited.set(0);
    }

    private void resetClueState() {
        pendingClueReward = false;
        pendingClueType = null;
        clueTicksWaited.set(0);
    }
}
