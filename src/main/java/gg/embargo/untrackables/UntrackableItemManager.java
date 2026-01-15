package gg.embargo.untrackables;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@Singleton
public class UntrackableItemManager {

    @Inject
    private Client client;

    private final EventBus eventBus;

    @Inject
    private UntrackableItemManager(Client client, EventBus eventBus) {
        this.client = client;
        this.eventBus = eventBus;
    }

    @Inject
    private OkHttpClient okHttpClient;

    private static final String UNTRACKABLE_ENDPOINT = "https://embargo.gg/api/untrackables";

    // Limit size to prevent unbounded growth - LRU eviction for player loot timestamps
    private static final int MAX_LOOT_TIME_CACHE_SIZE = 50;
    private final Map<String, LocalDateTime> lastLootTime = new LinkedHashMap<String, LocalDateTime>(MAX_LOOT_TIME_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, LocalDateTime> eldest) {
            return size() > MAX_LOOT_TIME_CACHE_SIZE;
        }
    };

    @Getter
    enum UntrackableItems {

        BOOK_OF_THE_DEAD(25818),
        MUSIC_CAPE(13221),
        MUSIC_CAPE_T(13222),
        BARROWS_GLOVES(7462),
        IMBUED_SARADOMIN_CAPE(21791),
        IMBUED_GUTHIX_CAPE(21793),
        IMBUED_ZAMORAK_CAPE(21795),
        IMBUED_SARADOMIN_MAX_CAPE(21776),
        IMBUED_ZAMORAK_MAX_CAPE(21780),
        IMBUED_GUTHIX_MAX_CAPE(21784),
        IMBUED_SARADOMIN_MAX_CAPE_I(24232),
        IMBUED_ZAMORAK_MAX_CAPE_I(24233),
        IMBUED_GUTHIX_MAX_CAPE_I(24234),

        //BINGO #1 ITEMS FOR START COUNTS
        BEGINNER_REWARD_CASKET(23245),
        EASY_REWARD_CASKET(20546),
        MEDIUM_REWARD_CASKET(20545),
        HARD_REWARD_CASKET(20544),
        ELITE_REWARD_CASKET(20543),
        MASTER_REWARD_CASKET(19836),

        MOSSY_KEY(22374),
        GIANT_KEY(20754);


        private final int itemId;

        UntrackableItems(int itemId) {
            this.itemId = itemId;
        }
    }

    void getUntrackableItems(String username) {
        Widget widget = client.getWidget(786445);
        ItemContainer itemContainer = client.getItemContainer(InventoryID.BANK);
        Widget[] children;
        if (widget != null) {
            children = widget.getChildren();
        } else {
            return;
        }
        if (itemContainer != null && children != null) {

            //Convert itemMap to use manifest.untrackableItems (which is a list of integers) instead of hardcoded enum
            var itemMap = Arrays.stream(UntrackableItems.values()).map(UntrackableItems::getItemId).collect(Collectors.toCollection(HashSet::new));
            List<Integer> playerItems = new ArrayList<>();
            java.util.Map<Integer, Integer> itemQuantities = new java.util.HashMap<>();
            for (int i = 0; i < itemContainer.size(); ++i) {

                Widget child = children[i];
                var currentItem = child.getItemId();
                if (itemMap.contains(currentItem)) {
                    playerItems.add(currentItem);
                    int quantity = child.getItemQuantity();
                    itemQuantities.put(currentItem, quantity);
                }
            }

            var RequestBody = new FormBody.Builder();
            for (int i=0; i < playerItems.size(); i++) {
                RequestBody.add("itemIds[" + i + "]", String.valueOf(playerItems.get(i)));
                RequestBody.add("quantities[" + i + "]", String.valueOf(itemQuantities.get(playerItems.get(i))));
            }

            RequestBody.add("username", username);

            Request request = new Request.Builder()
                    .url(UNTRACKABLE_ENDPOINT)
                    .post(RequestBody.build())
                    .addHeader("Content-Type", "application/json")
                    .build();

            try {
                okHttpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        log.error("Something went wrong inside of getUntrackableItems: {}", e.getLocalizedMessage());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        if (response.isSuccessful()) {
                            log.debug("Successfully submitted getUntrackableItems");
                        }
                        response.close();
                    }
                });
            } catch (IllegalArgumentException e) {
                log.error("Bad URL given: {}", e.getLocalizedMessage());
            }
        }
    }

    public void startUp() {
        eventBus.register(this);
    }

    public void shutDown() {
        eventBus.unregister(this);
        lastLootTime.clear();
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (event.getScriptId() == 277) {
            if (client == null || client.getLocalPlayer() == null) {
                return;
            }

            var username = client.getLocalPlayer().getName();

            if (lastLootTime.containsKey(username)) {
                LocalDateTime lastLootTimestamp = lastLootTime.get(username);

                if (LocalDateTime.now().isBefore(lastLootTimestamp)) {
                    log.debug("Player has opened bank within the last 3 minutes, not checking for untrackable items");
                    return;
                }

            }
            getUntrackableItems(username);
            lastLootTime.put(username, LocalDateTime.now().plusMinutes(3));
        }
    }
}
