package gg.embargo.events;

import gg.embargo.EmbargoConfig;
import gg.embargo.manifest.ClanEvent;
import gg.embargo.manifest.Manifest;
import gg.embargo.manifest.ManifestManager;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides the clan event schedule (delivered through the manifest) and fires
 * an opt-in desktop notification shortly before a subscribed event starts.
 * Per-event notification subscriptions are persisted in the plugin config so
 * they survive restarts.
 */
@Slf4j
@Singleton
public class ClanEventScheduleManager {

    private static final String CONFIG_GROUP = "embargo";
    private static final String NOTIFY_KEY_PREFIX = "eventnotify.";

    @Inject
    private ManifestManager manifestManager;

    @Inject
    private EmbargoConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private Notifier notifier;

    @Inject
    private ScheduledExecutorService scheduledExecutorService;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Set<Integer> notifiedEventIds = new HashSet<>();
    private ScheduledFuture<?> notifyTask;

    public void startUp() {
        if (started.getAndSet(true)) {
            return;
        }
        notifyTask = scheduledExecutorService.scheduleAtFixedRate(this::checkForUpcomingEvents,
                30, 60, TimeUnit.SECONDS);
    }

    public void shutDown() {
        if (!started.getAndSet(false)) {
            return;
        }
        if (notifyTask != null) {
            notifyTask.cancel(false);
            notifyTask = null;
        }
        notifiedEventIds.clear();
    }

    /**
     * @return schedule events that have not yet ended, soonest first
     */
    public List<ClanEvent> getUpcomingEvents() {
        Manifest manifest = manifestManager.getManifest();
        if (manifest == null || manifest.schedule == null) {
            return new ArrayList<>();
        }

        long now = Instant.now().getEpochSecond();
        List<ClanEvent> upcoming = new ArrayList<>();
        for (ClanEvent event : manifest.schedule) {
            if (event != null && event.getTitle() != null && !event.isExpired(now)) {
                upcoming.add(event);
            }
        }
        upcoming.sort(Comparator.comparingLong(ClanEvent::getStartsAt));
        return upcoming;
    }

    public boolean isSubscribed(int eventId) {
        return Boolean.TRUE.toString()
                .equals(configManager.getConfiguration(CONFIG_GROUP, NOTIFY_KEY_PREFIX + eventId));
    }

    public void setSubscribed(int eventId, boolean subscribed) {
        if (subscribed) {
            configManager.setConfiguration(CONFIG_GROUP, NOTIFY_KEY_PREFIX + eventId, true);
        } else {
            configManager.unsetConfiguration(CONFIG_GROUP, NOTIFY_KEY_PREFIX + eventId);
        }
    }

    private void checkForUpcomingEvents() {
        try {
            if (!manifestManager.isFeatureEnabled("schedule")) {
                return;
            }

            long now = Instant.now().getEpochSecond();
            long leadSeconds = Math.max(1, config.eventNotifyMinutes()) * 60L;

            for (ClanEvent event : getUpcomingEvents()) {
                long untilStart = event.getStartsAt() - now;
                if (untilStart <= 0 || untilStart > leadSeconds) {
                    continue;
                }
                if (notifiedEventIds.contains(event.getId()) || !isSubscribed(event.getId())) {
                    continue;
                }

                notifiedEventIds.add(event.getId());
                long minutes = Math.max(1, untilStart / 60);
                StringBuilder message = new StringBuilder("Embargo event \"")
                        .append(event.getTitle())
                        .append("\" starts in ~").append(minutes).append(" minute").append(minutes == 1 ? "" : "s");
                if (event.getWorld() > 0) {
                    message.append(" on W").append(event.getWorld());
                }
                if (event.getLocation() != null && !event.getLocation().isEmpty()) {
                    message.append(" at ").append(event.getLocation());
                }
                notifier.notify(message.toString());
            }
        } catch (Exception e) {
            log.debug("Error checking upcoming events", e);
        }
    }
}
