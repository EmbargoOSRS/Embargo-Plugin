package gg.embargo.eastereggs;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import gg.embargo.EmbargoConfig;
import gg.embargo.manifest.ManifestManager;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
public class NPCRenameManager {

    private final EventBus eventBus;
    private final EmbargoConfig config;
    private final ManifestManager manifestManager;

    @Inject
    public NPCRenameManager(EventBus eventBus, EmbargoConfig config, ManifestManager manifestManager) {
        this.eventBus = eventBus;
        this.config = config;
        this.manifestManager = manifestManager;
    }

    private static final Set<MenuAction> NPC_MENU_ACTIONS = ImmutableSet.of(
            MenuAction.NPC_FIRST_OPTION,
            MenuAction.NPC_SECOND_OPTION,
            MenuAction.NPC_THIRD_OPTION,
            MenuAction.NPC_FOURTH_OPTION,
            MenuAction.NPC_FIFTH_OPTION,
            MenuAction.WIDGET_TARGET_ON_NPC,
            MenuAction.EXAMINE_NPC);

    private static final ImmutableMap<String, String> DEFAULT_NPC_RENAMES = ImmutableMap.<String, String>builder().build();
    private final Map<String, String> customNPCRemaps = new HashMap<>();

    private boolean manifestFetchAttempted = false;

    public boolean featureEnabled() {
        return config.enableClanEasterEggs() && config.enableNpcRenames();
    }

    @Subscribe
    protected void onMenuEntryAdded(MenuEntryAdded event) {

        if (!featureEnabled()) {
            return;
        }

        if (manifestManager.getManifest() == null) {
            manifestManager.getLatestManifest();
            return;
        }

        // Check if manifest is empty and fetch if needed
        if (manifestManager.getManifest().getItemRenames() == null) {
            manifestManager.getLatestManifest();
        }

        parseManifest();
        MenuEntry entry = event.getMenuEntry();


        if (NPC_MENU_ACTIONS.contains(entry.getType())) {
            remapMenuEntryText(entry, (HashMap<String, String>) customNPCRemaps);  // Use customNPCRemaps instead of npcListHashMap
        }
    }

    public void startUp() {
        eventBus.register(this);

        if (!featureEnabled()) return;

        setupMenuRenames();
        manifestManager.getLatestManifest(); // Fetch manifest on startup
    }

    public void shutDown() {
        customNPCRemaps.clear();
        eventBus.unregister(this);
    }

    public void setupMenuRenames() {
        customNPCRemaps.clear();
        customNPCRemaps.putAll(DEFAULT_NPC_RENAMES);
    }

    public void parseManifest() {
        if (manifestManager.getManifest().getNpcRenames() == null || manifestManager.getManifest().getNpcRenames().isEmpty()) {
            if (!manifestFetchAttempted) {
                manifestFetchAttempted = true;
                manifestManager.getLatestManifest();
                log.debug("manifest.npcRenames is empty, attempting to refetch");
            }
            return;
        }

        // Clear and repopulate custom remaps
        customNPCRemaps.clear();
        customNPCRemaps.putAll(DEFAULT_NPC_RENAMES);

        // Add manifest renames
        for (Map.Entry<String, String> entry : manifestManager.getManifest().getNpcRenames().entrySet()) {
            String originalName = entry.getKey();
            String newName = entry.getValue();
            customNPCRemaps.put(originalName, newName);
        }
    }

    private void remapMenuEntryText(MenuEntry menuEntry, HashMap<String, String> map) {
        String target = menuEntry.getTarget();
        String cleanTarget;
        
        NPC npc = menuEntry.getNpc();
        cleanTarget = npc != null ? Text.removeTags(npc.getName()) : Text.removeTags(target);
        
        String replacement = customNPCRemaps.get(cleanTarget);
        if (replacement != null) {
            menuEntry.setTarget(target.replace(cleanTarget, replacement));
        }
    }
}


