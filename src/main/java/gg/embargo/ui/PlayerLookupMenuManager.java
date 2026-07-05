package gg.embargo.ui;

import com.google.common.collect.ImmutableSet;
import gg.embargo.EmbargoConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adds an "Embargo Lookup" right-click option to players in the world and to
 * name entries in the friends/ignore/clan/chat lists, opening the member's
 * profile on embargo.gg. Follows the pattern used by RuneLite's own Hiscore
 * plugin and the Wise Old Man plugin.
 */
@Slf4j
@Singleton
public class PlayerLookupMenuManager {

    private static final String LOOKUP = "Embargo Lookup";

    // When one of these options exists on a menu entry, the entry's target is
    // a player name and a lookup option can be attached alongside it
    private static final Set<String> AFTER_OPTIONS = ImmutableSet.of(
            "Message", "Add ignore", "Remove friend", "Delete", "Kick", "Reject");

    @Inject
    private Client client;

    @Inject
    private EventBus eventBus;

    @Inject
    private MenuManager menuManager;

    @Inject
    private EmbargoConfig config;

    private final AtomicBoolean started = new AtomicBoolean(false);

    public void startUp() {
        if (started.getAndSet(true)) {
            return;
        }
        eventBus.register(this);
        menuManager.addPlayerMenuItem(LOOKUP);
    }

    public void shutDown() {
        if (!started.getAndSet(false)) {
            return;
        }
        eventBus.unregister(this);
        menuManager.removePlayerMenuItem(LOOKUP);
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        if (!config.showLookupMenuOption()) {
            return;
        }

        // The world right-click menu item is added to every player via
        // addPlayerMenuItem; strip it back off unless the player is in our clan.
        MenuEntry menuEntry = event.getMenuEntry();
        if (menuEntry.getType() == MenuAction.RUNELITE_PLAYER && LOOKUP.equals(event.getOption())) {
            Player player = menuEntry.getPlayer();
            if (player == null || player.getName() == null || !isClanMember(player.getName())) {
                client.getMenu().removeMenuEntry(menuEntry);
            }
            return;
        }

        // Name entries in the friends/ignore/clan/chat lists.
        if (!AFTER_OPTIONS.contains(event.getOption())) {
            return;
        }

        String target = Text.removeTags(event.getTarget());
        if (target.isEmpty() || !isClanMember(target)) {
            return;
        }

        client.getMenu().createMenuEntry(-2)
                .setOption(LOOKUP)
                .setTarget(event.getTarget())
                .setType(MenuAction.RUNELITE)
                .onClick(e -> lookup(Text.removeTags(e.getTarget())));
    }

    /**
     * Whether the given player name belongs to a member of the local player's
     * clan channel. Names are normalised the same way the notice board does.
     */
    private boolean isClanMember(String name) {
        ClanChannel clanChannel = client.getClanChannel();
        if (clanChannel == null) {
            return false;
        }

        String jagexName = Text.toJagexName(name);
        for (ClanChannelMember member : clanChannel.getMembers()) {
            if (Text.toJagexName(member.getName()).equals(jagexName)) {
                return true;
            }
        }
        return false;
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (event.getMenuAction() != MenuAction.RUNELITE_PLAYER || !LOOKUP.equals(event.getMenuOption())) {
            return;
        }

        Player player = event.getMenuEntry().getPlayer();
        if (player != null && player.getName() != null) {
            lookup(player.getName());
        }
    }

    private void lookup(String playerName) {
        if (!config.showLookupMenuOption()) {
            return;
        }

        String name = Text.toJagexName(playerName);
        try {
            LinkBrowser.browse(new URI("https", "embargo.gg", "/profile/" + name, null).toASCIIString());
        } catch (URISyntaxException e) {
            log.debug("Failed to build lookup URL for {}", name, e);
        }
    }
}
