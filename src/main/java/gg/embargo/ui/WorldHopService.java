package gg.embargo.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.WorldService;
import net.runelite.client.util.WorldUtil;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Quick-hops to a target world, reimplementing the World Hopper flow the way
 * the Sith Clan plugin does: open the world switcher over a few game ticks,
 * then hop once its widget is loaded.
 */
@Slf4j
@Singleton
public class WorldHopService {

    private static final int MAX_SWITCHER_ATTEMPTS = 3;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private EventBus eventBus;

    @Inject
    private WorldService worldService;

    private final AtomicBoolean started = new AtomicBoolean(false);

    private net.runelite.api.World quickHopTargetWorld;
    private int displaySwitcherAttempts;

    public void startUp() {
        if (started.getAndSet(true)) {
            return;
        }
        eventBus.register(this);
    }

    public void shutDown() {
        if (!started.getAndSet(false)) {
            return;
        }
        eventBus.unregister(this);
        quickHopTargetWorld = null;
    }

    /**
     * Requests a hop to the given world id. Safe to call from any thread.
     */
    public void requestHop(int worldId) {
        clientThread.invoke(() -> hop(worldId));
    }

    private void hop(int worldId) {
        WorldResult worldResult = worldService.getWorlds();
        if (worldResult == null) {
            return;
        }

        World world = worldResult.findWorld(worldId);
        if (world == null) {
            log.debug("World {} not found", worldId);
            return;
        }

        final net.runelite.api.World rsWorld = client.createWorld();
        rsWorld.setActivity(world.getActivity());
        rsWorld.setAddress(world.getAddress());
        rsWorld.setId(world.getId());
        rsWorld.setPlayerCount(world.getPlayers());
        rsWorld.setLocation(world.getLocation());
        rsWorld.setTypes(WorldUtil.toWorldTypes(world.getTypes()));

        if (client.getGameState() == GameState.LOGIN_SCREEN) {
            client.changeWorld(rsWorld);
            return;
        }

        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                "<col=ff9000>[Embargo]</col> Quick-hopping to World " + world.getId() + "...", null);

        quickHopTargetWorld = rsWorld;
        displaySwitcherAttempts = 0;
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        if (quickHopTargetWorld == null) {
            return;
        }

        if (client.getWidget(InterfaceID.Worldswitcher.BUTTONS) == null) {
            client.openWorldHopper();

            if (++displaySwitcherAttempts >= MAX_SWITCHER_ATTEMPTS) {
                log.debug("Could not open world switcher, giving up hop");
                quickHopTargetWorld = null;
            }
        } else {
            client.hopToWorld(quickHopTargetWorld);
            quickHopTargetWorld = null;
        }
    }
}
