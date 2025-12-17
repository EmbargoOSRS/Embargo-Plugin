package gg.embargo.eastereggs;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import gg.embargo.EmbargoConfig;
import gg.embargo.manifest.ManifestManager;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;
import net.runelite.client.eventbus.EventBus;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@Singleton
public class ItemRenameManager {

    private final EventBus eventBus;
    private final EmbargoConfig config;
    private final ManifestManager manifestManager;

    @Inject
    public ItemRenameManager(EventBus eventBus, EmbargoConfig config, ManifestManager manifestManager) {
        this.eventBus = eventBus;
        this.config = config;
        this.manifestManager = manifestManager;
    }

    private boolean manifestFetchAttempted = false;
    private boolean manifestParsed = false;

    private static final Set<MenuAction> ITEM_MENU_ACTIONS = ImmutableSet.of(
            MenuAction.GROUND_ITEM_FIRST_OPTION, MenuAction.GROUND_ITEM_SECOND_OPTION,
            MenuAction.GROUND_ITEM_THIRD_OPTION, MenuAction.GROUND_ITEM_FOURTH_OPTION,
            MenuAction.GROUND_ITEM_FIFTH_OPTION, MenuAction.EXAMINE_ITEM_GROUND,
            // Inventory + Using Item on Players/NPCs/Objects
            MenuAction.CC_OP, MenuAction.CC_OP_LOW_PRIORITY, MenuAction.WIDGET_TARGET,
            MenuAction.WIDGET_TARGET_ON_PLAYER, MenuAction.WIDGET_TARGET_ON_NPC,
            MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, MenuAction.WIDGET_TARGET_ON_GROUND_ITEM,
            MenuAction.WIDGET_TARGET_ON_WIDGET);

    // Default item name remappings
    private static final ImmutableMap<String, String> DEFAULT_ITEM_REMAP = ImmutableMap.<String, String>builder().build();

    // Map for custom renamings
    private final Map<String, String> customItemRemap = new HashMap<>();

    public boolean featureEnabled() {
        return config.enableClanEasterEggs() && config.enableItemRenames();
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        // Only process if easter eggs are enabled
        if (!featureEnabled()) {
            return;
        }

        if (!manifestParsed && manifestManager.getManifest() != null) {
            parseManifest();
        }

        MenuEntry entry = event.getMenuEntry();
        if (ITEM_MENU_ACTIONS.contains(entry.getType())) {
            remapMenuEntryText(entry);
        }
    }

    public void startUp() {
        eventBus.register(this);

        if (!featureEnabled()) return;

        setupMenuRenames();
        manifestManager.getLatestManifest(); // Fetch manifest on startup
    }

    public void shutDown() {
        customItemRemap.clear();
        manifestParsed = false;
        manifestFetchAttempted = false;
        eventBus.unregister(this);
    }

    public void setupMenuRenames() {
        customItemRemap.clear();
        customItemRemap.putAll(DEFAULT_ITEM_REMAP);
    }

    public void parseManifest() {
        if (manifestManager.getManifest() == null ||
            manifestManager.getManifest().getItemRenames() == null ||
            manifestManager.getManifest().getItemRenames().isEmpty()) {
            if (!manifestFetchAttempted) {
                manifestFetchAttempted = true;
                manifestManager.getLatestManifest();
                log.debug("manifest.itemRenames is empty, attempting to refetch");
            }
            return;
        }

        // Clear and rebuild map
        customItemRemap.clear();
        customItemRemap.putAll(DEFAULT_ITEM_REMAP);
        customItemRemap.putAll(manifestManager.getManifest().getItemRenames());

        manifestParsed = true;
        log.debug("Parsed {} item renames from manifest", customItemRemap.size());
    }

    /**
     * Remaps a menu entry's text if the target matches an entry in the custom item map.
     *
     * @param menuEntry The menu entry to modify
     */
    private void remapMenuEntryText(MenuEntry menuEntry) {
        if (customItemRemap.isEmpty()) {
            return;
        }

        NPC npc = menuEntry.getNpc();
        String target = menuEntry.getTarget();
        String cleanTarget = npc != null ? Text.removeTags(npc.getName()) : Text.removeTags(target);

        String replacement = customItemRemap.get(cleanTarget);
        if (replacement != null) {
            menuEntry.setTarget(target.replace(cleanTarget, replacement));
        }
    }
}
