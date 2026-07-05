package gg.embargo.announcements;

import gg.embargo.EmbargoConfig;
import gg.embargo.manifest.Announcement;
import gg.embargo.manifest.Manifest;
import gg.embargo.manifest.ManifestManager;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows staff announcements (delivered through the manifest) as broadcast
 * chat lines in-game. Each announcement is shown at most once per session,
 * and only while inside its display window.
 */
@Slf4j
@Singleton
public class AnnouncementManager {

    // Check for new announcements roughly every 60 seconds (100 ticks)
    private static final int TICKS_BETWEEN_CHECKS = 100;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private EventBus eventBus;

    @Inject
    private ManifestManager manifestManager;

    @Inject
    private EmbargoConfig config;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Set<Integer> shownAnnouncementIds = new HashSet<>();
    private int ticksSinceCheck = 0;

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
        shownAnnouncementIds.clear();
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        if (++ticksSinceCheck < TICKS_BETWEEN_CHECKS) {
            return;
        }
        ticksSinceCheck = 0;

        if (!config.showClanAnnouncements() || !manifestManager.isFeatureEnabled("announcements")) {
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        Manifest manifest = manifestManager.getManifest();
        if (manifest == null || manifest.announcements == null || manifest.announcements.isEmpty()) {
            return;
        }

        long now = Instant.now().getEpochSecond();
        for (Announcement announcement : manifest.announcements) {
            if (announcement == null || !announcement.isActive(now)
                    || shownAnnouncementIds.contains(announcement.getId())) {
                continue;
            }

            shownAnnouncementIds.add(announcement.getId());
            String message = "<col=ff9000>[Embargo]</col> " + announcement.getMessage();
            clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.BROADCAST, "", message, null));
        }
    }
}
