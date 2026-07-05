package gg.embargo.ui;

import gg.embargo.events.ClanEventScheduleManager;
import gg.embargo.manifest.ClanEvent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Side-panel section listing scheduled clan events with local start times, a
 * live countdown, an opt-in pre-event notification checkbox, and a quick-hop
 * button when the event has a world.
 */
@Slf4j
@Singleton
public class ClanSchedulePanel extends JPanel {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d h:mm a");
    private static final Color COLOR_GREEN = new Color(0, 200, 83);
    private static final Color COLOR_ORANGE = new Color(255, 152, 31);

    private final ClanEventScheduleManager scheduleManager;
    private final WorldHopService worldHopService;

    // Refreshes countdown labels twice a minute
    private final Timer refreshTimer;

    @Inject
    public ClanSchedulePanel(ClanEventScheduleManager scheduleManager, WorldHopService worldHopService) {
        this.scheduleManager = scheduleManager;
        this.worldHopService = worldHopService;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        refreshTimer = new Timer(30_000, e -> refresh());
        refreshTimer.setRepeats(true);
    }

    public void startUp() {
        refresh();
        refreshTimer.start();
    }

    public void shutDown() {
        refreshTimer.stop();
    }

    public void refresh() {
        SwingUtilities.invokeLater(this::rebuild);
    }

    private void rebuild() {
        removeAll();

        List<ClanEvent> events = scheduleManager.getUpcomingEvents();
        if (events.isEmpty()) {
            add(smallLabel("No scheduled events", ColorScheme.LIGHT_GRAY_COLOR));
        } else {
            long now = Instant.now().getEpochSecond();
            for (ClanEvent event : events) {
                add(Box.createVerticalStrut(4));
                add(buildEventCard(event, now));
            }
        }

        revalidate();
        repaint();
    }

    private JPanel buildEventCard(ClanEvent event, long now) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = smallLabel(event.getTitle(), Color.WHITE);
        title.setFont(FontManager.getRunescapeBoldFont());
        card.add(title);

        // Start time converted to the viewer's local timezone
        ZonedDateTime localStart = Instant.ofEpochSecond(event.getStartsAt()).atZone(ZoneId.systemDefault());
        card.add(smallLabel(localStart.format(TIME_FORMAT), ColorScheme.LIGHT_GRAY_COLOR));

        long untilStart = event.getStartsAt() - now;
        if (untilStart > 0) {
            card.add(smallLabel("Starts in " + formatDuration(untilStart), COLOR_ORANGE));
        } else {
            card.add(smallLabel("Happening now", COLOR_GREEN));
        }

        if (event.getHost() != null && !event.getHost().isEmpty()) {
            card.add(smallLabel("Host: " + event.getHost(), ColorScheme.LIGHT_GRAY_COLOR));
        }
        if (event.getLocation() != null && !event.getLocation().isEmpty()) {
            card.add(smallLabel("Location: " + event.getLocation(), ColorScheme.LIGHT_GRAY_COLOR));
        }

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (event.getWorld() > 0) {
            JButton hopButton = new JButton("Hop to W" + event.getWorld());
            hopButton.setFont(FontManager.getRunescapeSmallFont());
            hopButton.setFocusPainted(false);
            hopButton.addActionListener(e -> worldHopService.requestHop(event.getWorld()));
            actions.add(hopButton);
        }

        JCheckBox notifyBox = new JCheckBox("Notify me");
        notifyBox.setFont(FontManager.getRunescapeSmallFont());
        notifyBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        notifyBox.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        notifyBox.setSelected(scheduleManager.isSubscribed(event.getId()));
        notifyBox.addActionListener(e -> scheduleManager.setSubscribed(event.getId(), notifyBox.isSelected()));
        actions.add(notifyBox);

        card.add(actions);
        return card;
    }

    private static JLabel smallLabel(String text, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static String formatDuration(long seconds) {
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;

        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return Math.max(1, minutes) + "m";
    }
}
