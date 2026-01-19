package gg.embargo.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gg.embargo.DataManager;
import gg.embargo.EmbargoPlugin;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.info.JRichTextPane;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
public class EmbargoPanel extends PluginPanel {
    @Inject
    @Nullable
    private Client client;
    @Inject
    private EventBus eventBus;

    @Inject
    private DataManager dataManager;

    @Inject
    private MissingRequirementsPanel missingRequirementsPanelX;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ScheduledExecutorService executorService;

    @Setter
    public boolean isLoggedIn = false;

    // Keep track of all boxes
    // private final ArrayList<ItemID> items = new ArrayList<>();
    JPanel versionPanel = new JPanel();
    JPanel missingRequirementsPanel = new JPanel();
    private static final ImageIcon ARROW_RIGHT_ICON = new ImageIcon(
            ImageUtil.loadImageResource(EmbargoPanel.class, "/util/arrow_right.png"));
    private static final ImageIcon DISCORD_ICON = new ImageIcon(
            ImageUtil.loadImageResource(EmbargoPanel.class, "/discord_icon.png"));
    static ImageIcon GITHUB_ICON = new ImageIcon(ImageUtil.loadImageResource(EmbargoPanel.class, "/github_icon.png"));
    static ImageIcon WEBSITE_ICON = new ImageIcon(ImageUtil.loadImageResource(EmbargoPanel.class, "/website_icon.png"));
    private final JRichTextPane emailLabel = new JRichTextPane();
    private final JLabel loggedLabel = new JLabel();
    private final JLabel embargoScoreLabel = new JLabel(htmlLabel("Embargo Score:", " N/A"));
    private final JLabel accountScoreLabel = new JLabel(htmlLabel("Account Score:", " N/A"));
    private final JLabel communityScoreLabel = new JLabel(htmlLabel("Community Score:", " N/A"));
    private final JLabel currentRankLabel = new JLabel(htmlLabel("Current Rank:", " N/A"));
    private final JLabel isRegisteredWithClanLabel = new JLabel(htmlLabel("Account registered:", " No"));
    private final JLabel currentCALabel = new JLabel(htmlLabel("Current TA Tier:", " N/A"));
    final JLabel missingRequiredItemsLabel = new JLabel(
            htmlLabel("Sign in to see what requirements", " you are missing for rank up"));
    private final Font smallFont = FontManager.getRunescapeSmallFont();
    final JPanel missingRequirementsContainer = new JPanel(new BorderLayout(5, 0));
    private final JLabel missingItemCountLabel = new JLabel();
    private boolean missingRequirementsCollapsed = false;
    private JPanel missingRequirementsHeader;
    private JPanel collapsibleContent;
    private JLabel collapseIndicator;
    private JPanel accountInfoSection;
    private JPanel loggedOutSection;
    private JLabel refreshButton;
    private boolean refreshOnCooldown = false;
    private static final int REFRESH_COOLDOWN_SECONDS = 30;

    // Events section (contains Of The Week and Bounties subsections)
    private JPanel eventsContainer;

    // Of The Week subsection - dynamic panels for ongoing/upcoming
    private JPanel ofTheWeekOngoingPanel;
    private JPanel ofTheWeekUpcomingPanel;

    // Bounties subsection - dynamic panel for multiple bounties
    private JPanel bountiesListPanel;
    private final Set<Integer> alertedBountyIds = new HashSet<>();

    @Inject
    private EmbargoPanel() {
    }

    private String htmlLabel(String key, String value) {
        return "<html><body style = 'color:#a5a5a5'>" + key + "<span style = 'color:white'>" + value
                + "</span></body></html>";
    }

    void setupVersionPanel() {
        // Set up versionPanel with BoxLayout for better control
        versionPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        versionPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        versionPanel.setLayout(new BoxLayout(versionPanel, BoxLayout.Y_AXIS));

        // Set up Embargo Clan Version at top of Version panel
        JLabel version = new JLabel(htmlLabel("Embargo Clan Version: ", "1.5.0"));
        version.setFont(smallFont);
        version.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Set up custom embargo labels
        isRegisteredWithClanLabel.setFont(smallFont);
        isRegisteredWithClanLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        isRegisteredWithClanLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        embargoScoreLabel.setFont(smallFont);
        embargoScoreLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        embargoScoreLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        accountScoreLabel.setFont(smallFont);
        accountScoreLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        accountScoreLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        communityScoreLabel.setFont(smallFont);
        communityScoreLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        communityScoreLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        currentCALabel.setFont(smallFont);
        currentCALabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        currentCALabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        loggedLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        loggedLabel.setFont(smallFont);
        loggedLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        currentRankLabel.setFont(smallFont);
        currentRankLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        currentRankLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        emailLabel.setForeground(Color.WHITE);
        emailLabel.setFont(smallFont);

        versionPanel.add(version);
        versionPanel.add(Box.createVerticalStrut(4));
        versionPanel.add(loggedLabel);

        // Create logged out section (shown when not logged in)
        loggedOutSection = new JPanel();
        loggedOutSection.setLayout(new BoxLayout(loggedOutSection, BoxLayout.Y_AXIS));
        loggedOutSection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        loggedOutSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        loggedOutSection.add(emailLabel);

        // Create account info section (shown when logged in)
        accountInfoSection = new JPanel();
        accountInfoSection.setLayout(new BoxLayout(accountInfoSection, BoxLayout.Y_AXIS));
        accountInfoSection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        accountInfoSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        accountInfoSection.setVisible(false);

        // Add separator before Account Info section
        accountInfoSection.add(Box.createVerticalStrut(8));
        JSeparator sep = createSeparator();
        accountInfoSection.add(sep);
        accountInfoSection.add(Box.createVerticalStrut(8));

        // Add Account Info section header with refresh button
        JPanel accountInfoHeaderPanel = new JPanel(new BorderLayout());
        accountInfoHeaderPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        accountInfoHeaderPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        accountInfoHeaderPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        JLabel accountInfoHeader = new JLabel("Account Info");
        accountInfoHeader.setFont(new Font("SansSerif", Font.BOLD, 12));
        accountInfoHeader.setForeground(Color.WHITE);
        accountInfoHeaderPanel.add(accountInfoHeader, BorderLayout.WEST);

        // Refresh button
        refreshButton = new JLabel("\u21BB"); // Refresh symbol
        refreshButton.setFont(new Font("SansSerif", Font.BOLD, 14));
        refreshButton.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        refreshButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        refreshButton.setToolTipText("Refresh account data");
        refreshButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!refreshOnCooldown) {
                    refreshAccountData();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                if (!refreshOnCooldown) {
                    refreshButton.setForeground(Color.WHITE);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (!refreshOnCooldown) {
                    refreshButton.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                }
            }
        });
        accountInfoHeaderPanel.add(refreshButton, BorderLayout.EAST);

        accountInfoSection.add(accountInfoHeaderPanel);
        accountInfoSection.add(Box.createVerticalStrut(6));

        accountInfoSection.add(isRegisteredWithClanLabel);
        accountInfoSection.add(embargoScoreLabel);
        accountInfoSection.add(accountScoreLabel);
        accountInfoSection.add(communityScoreLabel);
        accountInfoSection.add(currentRankLabel);
        accountInfoSection.add(currentCALabel);

        versionPanel.add(loggedOutSection);
        versionPanel.add(accountInfoSection);
    }

    /**
     * Creates a horizontal separator line
     */
    private JSeparator createSeparator() {
        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        separator.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        separator.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        separator.setAlignmentX(Component.LEFT_ALIGNMENT);
        return separator;
    }

    JPanel setUpQuickLinks() {
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BorderLayout());
        wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(1, 0, 0, 0, ColorScheme.LIGHT_GRAY_COLOR),
                new EmptyBorder(10, 0, 0, 0)));

        JPanel actionsContainer = new JPanel();
        actionsContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        actionsContainer.setLayout(new GridLayout(0, 1, 0, 8));

        actionsContainer.add(buildLinkPanel(DISCORD_ICON, "Join us on our", "Discord", "https://discord.gg/YDGGyP3VEq"));
        actionsContainer.add(buildLinkPanel(WEBSITE_ICON, "Go to our", "clan website", "https://embargo.gg/"));
        actionsContainer.add(buildLinkPanel(GITHUB_ICON, "Report a bug or", "inspect the plugin code",
                "https://github.com/EmbargoOSRS/Embargo-Plugin"));

        wrapper.add(actionsContainer, BorderLayout.CENTER);
        return wrapper;
    }

    void setupMissingItemsPanel() {
        // Clear any existing content
        missingRequirementsContainer.removeAll();
        missingRequirementsPanel.removeAll();

        // Set up container styling with top border as separator
        missingRequirementsContainer.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(1, 0, 0, 0, ColorScheme.LIGHT_GRAY_COLOR),
                new EmptyBorder(10, 10, 10, 10)));
        missingRequirementsContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        missingRequirementsContainer.setFont(FontManager.getRunescapeSmallFont());
        missingRequirementsContainer.setForeground(Color.WHITE);
        missingRequirementsContainer.setLayout(new BorderLayout());

        // Create collapsible header
        missingRequirementsHeader = createMissingRequirementsHeader();
        missingRequirementsContainer.add(missingRequirementsHeader, BorderLayout.NORTH);

        // Set up collapsible content panel
        collapsibleContent = new JPanel(new BorderLayout());
        collapsibleContent.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Set up panel styling
        missingRequirementsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        missingRequirementsPanel.setBorder(new EmptyBorder(8, 0, 8, 0));
        missingRequirementsPanel.setLayout(new GridLayout(1, 1));

        // Always add the default message initially
        missingRequiredItemsLabel
                .setText(htmlLabel("Sign in to see what requirements", " you are missing for rank up"));
        missingRequiredItemsLabel.setFont(smallFont);
        missingRequiredItemsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        missingRequirementsPanel.add(missingRequiredItemsLabel);

        // Add panel to collapsible content
        collapsibleContent.add(missingRequirementsPanel, BorderLayout.CENTER);

        // Add collapsible content to container
        missingRequirementsContainer.add(collapsibleContent, BorderLayout.CENTER);

        // Add container to main panel
        this.add(missingRequirementsContainer, BorderLayout.NORTH);
        this.revalidate();
    }

    /**
     * Sets up the Events section panel with Of The Week and Bounties subsections
     */
    void setupEventsPanel() {
        eventsContainer = new JPanel();
        eventsContainer.setLayout(new BoxLayout(eventsContainer, BoxLayout.Y_AXIS));
        eventsContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        eventsContainer.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(1, 0, 0, 0, ColorScheme.LIGHT_GRAY_COLOR),
                new EmptyBorder(10, 10, 10, 10)));

        // Main Events Header
        JLabel eventsHeader = new JLabel("Events");
        eventsHeader.setFont(new Font("SansSerif", Font.BOLD, 12));
        eventsHeader.setForeground(Color.WHITE);
        eventsHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        eventsContainer.add(eventsHeader);
        eventsContainer.add(Box.createVerticalStrut(8));

        // === Of The Week Subsection ===
        JLabel ofTheWeekHeader = new JLabel("Of The Week");
        ofTheWeekHeader.setFont(new Font("SansSerif", Font.BOLD, 11));
        ofTheWeekHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        ofTheWeekHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        eventsContainer.add(ofTheWeekHeader);
        eventsContainer.add(Box.createVerticalStrut(4));

        // Ongoing events panel
        ofTheWeekOngoingPanel = new JPanel();
        ofTheWeekOngoingPanel.setLayout(new BoxLayout(ofTheWeekOngoingPanel, BoxLayout.Y_AXIS));
        ofTheWeekOngoingPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        ofTheWeekOngoingPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel ongoingLoading = new JLabel("Loading...");
        ongoingLoading.setFont(smallFont);
        ongoingLoading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        ofTheWeekOngoingPanel.add(ongoingLoading);
        eventsContainer.add(ofTheWeekOngoingPanel);

        // Upcoming events panel
        ofTheWeekUpcomingPanel = new JPanel();
        ofTheWeekUpcomingPanel.setLayout(new BoxLayout(ofTheWeekUpcomingPanel, BoxLayout.Y_AXIS));
        ofTheWeekUpcomingPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        ofTheWeekUpcomingPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        ofTheWeekUpcomingPanel.setVisible(false);
        eventsContainer.add(ofTheWeekUpcomingPanel);

        eventsContainer.add(Box.createVerticalStrut(8));

        // === Bounties Subsection ===
        JLabel bountyHeader = new JLabel("Bounties");
        bountyHeader.setFont(new Font("SansSerif", Font.BOLD, 11));
        bountyHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        bountyHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        eventsContainer.add(bountyHeader);
        eventsContainer.add(Box.createVerticalStrut(4));

        // Bounties list panel (shows ongoing + recent)
        bountiesListPanel = new JPanel();
        bountiesListPanel.setLayout(new BoxLayout(bountiesListPanel, BoxLayout.Y_AXIS));
        bountiesListPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        bountiesListPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel bountiesLoading = new JLabel("Loading...");
        bountiesLoading.setFont(smallFont);
        bountiesLoading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        bountiesListPanel.add(bountiesLoading);
        eventsContainer.add(bountiesListPanel);

        // Fetch events and bounties
        fetchAndUpdateEvents();
        fetchAndUpdateBounties();
    }

    /**
     * Fetches events from API and updates the Of The Week panel
     */
    private void fetchAndUpdateEvents() {
        dataManager.getEventsAsync().thenAccept(response -> {
            SwingUtilities.invokeLater(() -> {
                updateOfTheWeekPanel(response);
            });
        });
    }

    /**
     * Updates the Of The Week panel with the API response
     */
    private void updateOfTheWeekPanel(JsonArray events) {
        ofTheWeekOngoingPanel.removeAll();
        ofTheWeekUpcomingPanel.removeAll();

        if (events == null || events.size() == 0) {
            JLabel noEvents = new JLabel("No events");
            noEvents.setFont(smallFont);
            noEvents.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            ofTheWeekOngoingPanel.add(noEvents);
            ofTheWeekUpcomingPanel.setVisible(false);
            eventsContainer.revalidate();
            eventsContainer.repaint();
            return;
        }

        // Separate ongoing and upcoming events
        java.util.List<JsonObject> ongoingEvents = new ArrayList<>();
        java.util.List<JsonObject> upcomingEvents = new ArrayList<>();

        for (JsonElement element : events) {
            JsonObject event = element.getAsJsonObject();
            boolean started = event.has("started") && event.get("started").getAsBoolean();
            boolean completed = event.has("completed") && event.get("completed").getAsBoolean();

            if (started && !completed) {
                ongoingEvents.add(event);
            } else if (!started && !completed) {
                upcomingEvents.add(event);
            }
        }

        // Display ongoing events
        if (ongoingEvents.isEmpty()) {
            JLabel noOngoing = new JLabel("No ongoing events");
            noOngoing.setFont(smallFont);
            noOngoing.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            ofTheWeekOngoingPanel.add(noOngoing);
        } else {
            JLabel ongoingLabel = new JLabel("Ongoing");
            ongoingLabel.setFont(smallFont);
            ongoingLabel.setForeground(new Color(0x00, 0xc8, 0x00)); // Green for ongoing
            ofTheWeekOngoingPanel.add(ongoingLabel);

            for (JsonObject event : ongoingEvents) {
                addEventToPanel(ofTheWeekOngoingPanel, event, true);
            }
        }

        // Display upcoming events
        if (!upcomingEvents.isEmpty()) {
            ofTheWeekUpcomingPanel.add(Box.createVerticalStrut(6));
            JLabel upcomingLabel = new JLabel("Upcoming");
            upcomingLabel.setFont(smallFont);
            upcomingLabel.setForeground(new Color(0xff, 0xc0, 0x00)); // Orange/yellow for upcoming
            ofTheWeekUpcomingPanel.add(upcomingLabel);

            for (JsonObject event : upcomingEvents) {
                addEventToPanel(ofTheWeekUpcomingPanel, event, false);
            }
            ofTheWeekUpcomingPanel.setVisible(true);
        } else {
            ofTheWeekUpcomingPanel.setVisible(false);
        }

        eventsContainer.revalidate();
        eventsContainer.repaint();
    }

    /**
     * Adds a single event entry to the given panel
     */
    private void addEventToPanel(JPanel panel, JsonObject event, boolean isOngoing) {
        String name = event.has("name") ? event.get("name").getAsString() : "Unknown";
        String metric = event.has("metric") ? event.get("metric").getAsString() : "";
        int participants = event.has("participantCount") ? event.get("participantCount").getAsInt() : 0;
        int eventId = event.has("wiseOldManId") ? event.get("wiseOldManId").getAsInt() :
                      (event.has("id") ? event.get("id").getAsInt() : 0);

        // Shorten event name: "Boss Of The Week #X |" -> "BOTW |", "Skill Of The Week #X |" -> "SOTW |"
        String displayName = name
                .replaceFirst("Boss Of The Week #\\d+\\s*\\|", "BOTW |")
                .replaceFirst("Skill Of The Week #\\d+\\s*\\|", "SOTW |")
                .trim();

        // Event name as clickable link
        JLabel nameLabel = new JLabel(displayName);
        nameLabel.setFont(smallFont);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (eventId > 0) {
            nameLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
            nameLabel.setToolTipText("Click to view on embargo.gg");
            final int finalEventId = eventId;
            nameLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    LinkBrowser.browse("https://embargo.gg/competition/" + finalEventId);
                }
            });
        }
        panel.add(nameLabel);

        // Metric (using "Metric:" as label since it could be skill or boss)
        if (!metric.isEmpty()) {
            String formattedMetric = metric.substring(0, 1).toUpperCase() + metric.substring(1).replace("_", " ");
            JLabel metricLabel = new JLabel(htmlLabel("Metric:", " " + formattedMetric));
            metricLabel.setFont(smallFont);
            metricLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            metricLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(metricLabel);
        }

        // Participants (only for ongoing)
        if (isOngoing) {
            JLabel participantsLabel = new JLabel(htmlLabel("Participants:", " " + participants));
            participantsLabel.setFont(smallFont);
            participantsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            participantsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(participantsLabel);
        }
    }

    /**
     * Fetches bounties from API and updates the panel
     */
    private void fetchAndUpdateBounties() {
        dataManager.getBountiesAsync().thenAccept(response -> {
            SwingUtilities.invokeLater(() -> {
                updateBountyPanel(response);
            });
        });
    }

    /**
     * Updates the bounty panel with the API response
     * Shows: ongoing bounty (if any) + 2 most recent completed bounties
     */
    private void updateBountyPanel(JsonObject response) {
        bountiesListPanel.removeAll();

        if (response == null || !response.has("bounties")) {
            JLabel noBounties = new JLabel("No bounties");
            noBounties.setFont(smallFont);
            noBounties.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            bountiesListPanel.add(noBounties);
            eventsContainer.revalidate();
            eventsContainer.repaint();
            return;
        }

        JsonArray bounties = response.getAsJsonArray("bounties");
        java.util.List<JsonObject> activeBounties = new ArrayList<>();
        java.util.List<JsonObject> recentBounties = new ArrayList<>();

        // Separate active and completed bounties
        for (JsonElement element : bounties) {
            JsonObject bounty = element.getAsJsonObject();
            String status = bounty.has("status") ? bounty.get("status").getAsString() : "";

            if ("active".equalsIgnoreCase(status)) {
                activeBounties.add(bounty);
            } else if ("completed".equalsIgnoreCase(status) || "expired".equalsIgnoreCase(status)) {
                recentBounties.add(bounty);
            }
        }

        boolean hasContent = false;

        // Display active bounties first
        if (!activeBounties.isEmpty()) {
            hasContent = true;
            JLabel activeLabel = new JLabel("Active");
            activeLabel.setFont(smallFont);
            activeLabel.setForeground(new Color(0x00, 0xc8, 0x00)); // Green for active
            bountiesListPanel.add(activeLabel);

            for (JsonObject activeBounty : activeBounties) {
                addBountyToPanel(bountiesListPanel, activeBounty, true);

                // Alert user if this is a new bounty they haven't been alerted about
                int bountyId = activeBounty.get("id").getAsInt();
                if (isLoggedIn && !alertedBountyIds.contains(bountyId)) {
                    alertedBountyIds.add(bountyId);
                    sendBountyAlert(activeBounty);
                }
            }
        }

        // Display up to 2 most recent completed bounties
        if (!recentBounties.isEmpty()) {
            if (hasContent) {
                bountiesListPanel.add(Box.createVerticalStrut(6));
            }

            JLabel recentLabel = new JLabel("Recent");
            recentLabel.setFont(smallFont);
            recentLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            bountiesListPanel.add(recentLabel);

            int count = 0;
            for (JsonObject bounty : recentBounties) {
                if (count >= 2) break;
                addBountyToPanel(bountiesListPanel, bounty, false);
                count++;
            }
            hasContent = true;
        }

        if (!hasContent) {
            JLabel noBounties = new JLabel("No bounties");
            noBounties.setFont(smallFont);
            noBounties.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            bountiesListPanel.add(noBounties);
        }

        eventsContainer.revalidate();
        eventsContainer.repaint();
    }

    /**
     * Adds a single bounty entry to the given panel
     */
    private void addBountyToPanel(JPanel panel, JsonObject bounty, boolean isActive) {
        String name = bounty.has("name") ? bounty.get("name").getAsString() : "Unknown";
        String target = name.replaceFirst("Bounty #\\d+ - ", "");
        int bountyId = bounty.has("id") ? bounty.get("id").getAsInt() : 0;

        // Target name as clickable link
        JLabel targetLabel = new JLabel(target);
        targetLabel.setFont(smallFont);
        targetLabel.setForeground(Color.WHITE);
        targetLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (bountyId > 0) {
            targetLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
            targetLabel.setToolTipText("Click to view on embargo.gg");
            final int finalBountyId = bountyId;
            targetLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    LinkBrowser.browse("https://embargo.gg/bounties/" + finalBountyId);
                }
            });
        }
        panel.add(targetLabel);

        // Time remaining (for active) or completion status (for completed)
        if (isActive && bounty.has("endTime")) {
            try {
                String endTimeStr = bounty.get("endTime").getAsString();
                ZonedDateTime endTime = ZonedDateTime.parse(endTimeStr);
                long minutesRemaining = Instant.now().until(endTime.toInstant(), ChronoUnit.MINUTES);

                String timeText;
                if (minutesRemaining > 60) {
                    long hours = minutesRemaining / 60;
                    timeText = hours + "h " + (minutesRemaining % 60) + "m";
                } else if (minutesRemaining > 0) {
                    timeText = minutesRemaining + " min";
                } else {
                    timeText = "Ending soon";
                }

                JLabel timeLabel = new JLabel(htmlLabel("Time left:", " " + timeText));
                timeLabel.setFont(smallFont);
                timeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                timeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                panel.add(timeLabel);
            } catch (Exception e) {
                // Skip time label if parsing fails
            }
        }
    }

    /**
     * Sends a chat message alert for an active bounty
     */
    private void sendBountyAlert(JsonObject bounty) {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        String name = bounty.get("name").getAsString();
        String target = name.replaceFirst("Bounty #\\d+ - ", "");

        clientThread.invokeLater(() -> {
            client.addChatMessage(
                    net.runelite.api.ChatMessageType.GAMEMESSAGE,
                    "",
                    "<col=ff9000>[Embargo]</col> Active bounty: <col=ffffff>" + target + "</col>! Check the side panel for details.",
                    null
            );
        });
    }

    /**
     * Clears the alerted bounty IDs (call on logout to allow re-alerting on next login)
     */
    public void clearAlertedBounties() {
        alertedBountyIds.clear();
    }

    /**
     * Creates the collapsible header for missing requirements section
     */
    private JPanel createMissingRequirementsHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        header.setBorder(new EmptyBorder(0, 0, 8, 0));
        header.setCursor(new Cursor(Cursor.HAND_CURSOR));

        // Left side: title and collapse indicator
        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        titlePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        collapseIndicator = new JLabel("\u25BC "); // Down arrow
        collapseIndicator.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        collapseIndicator.setFont(new Font("SansSerif", Font.PLAIN, 10));
        titlePanel.add(collapseIndicator);

        JLabel title = new JLabel("Missing Requirements");
        title.setFont(new Font("SansSerif", Font.BOLD, 12));
        title.setForeground(Color.WHITE);
        titlePanel.add(title);

        header.add(titlePanel, BorderLayout.WEST);

        // Right side: item count
        missingItemCountLabel.setFont(smallFont);
        missingItemCountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        header.add(missingItemCountLabel, BorderLayout.EAST);

        // Add click listener for collapse/expand
        MouseAdapter collapseListener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggleMissingRequirementsCollapse();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                header.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
                titlePanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                titlePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            }
        };

        header.addMouseListener(collapseListener);

        return header;
    }

    /**
     * Toggles the collapsed state of the missing requirements section
     */
    private void toggleMissingRequirementsCollapse() {
        missingRequirementsCollapsed = !missingRequirementsCollapsed;
        collapsibleContent.setVisible(!missingRequirementsCollapsed);
        collapseIndicator.setText(missingRequirementsCollapsed ? "\u25B6 " : "\u25BC "); // Right arrow / Down arrow
        missingRequirementsContainer.revalidate();
        missingRequirementsContainer.repaint();
    }

    /**
     * Updates the missing item count label
     */
    private void updateMissingItemCount(int count) {
        if (count > 0) {
            missingItemCountLabel.setText(count + " item" + (count != 1 ? "s" : ""));
        } else {
            missingItemCountLabel.setText("");
        }
    }

    /**
     * Refreshes account data from the server
     */
    private void refreshAccountData() {
        if (client == null || client.getLocalPlayer() == null) {
            return;
        }

        // Start cooldown
        startRefreshCooldown();

        // Show loading state for manual refresh
        embargoScoreLabel.setText(htmlLabel("Embargo Score:", " Loading..."));
        accountScoreLabel.setText(htmlLabel("Account Score:", " Loading..."));
        communityScoreLabel.setText(htmlLabel("Community Score:", " Loading..."));
        currentRankLabel.setText(htmlLabel("Current Rank:", " Loading..."));
        currentCALabel.setText(htmlLabel("Current CA Tier:", " Loading..."));

        // Clear existing missing items
        missingRequirementsPanelX.clearItems();
        updateMissingItemCount(0);

        // Refresh events (includes Of The Week and Bounties)
        fetchAndUpdateEvents();
        fetchAndUpdateBounties();

        // Force refresh by calling updateLoggedIn with scheduled=true
        updateLoggedIn(true);
    }

    /**
     * Starts the refresh cooldown timer
     */
    private void startRefreshCooldown() {
        refreshOnCooldown = true;
        refreshButton.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        refreshButton.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
        refreshButton.setToolTipText("Refresh on cooldown...");

        // Schedule cooldown end
        executorService.schedule(() -> {
            SwingUtilities.invokeLater(() -> {
                refreshOnCooldown = false;
                refreshButton.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                refreshButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
                refreshButton.setToolTipText("Refresh account data");
            });
        }, REFRESH_COOLDOWN_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    void addSidePanel() {
        // Add the panels to the side plugin
        this.add(versionPanel, BorderLayout.NORTH);

        // Create center panel to hold missing requirements and events
        JPanel centerWrapper = new JPanel();
        centerWrapper.setLayout(new BoxLayout(centerWrapper, BoxLayout.Y_AXIS));
        centerWrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Setup and add missing items panel
        setupMissingItemsPanel();
        missingRequirementsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
        missingRequirementsContainer.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        // Remove from NORTH (setupMissingItemsPanel adds it there) and add to wrapper
        this.remove(missingRequirementsContainer);
        centerWrapper.add(missingRequirementsContainer);

        // Setup and add events panel (contains Of The Week and Bounties)
        setupEventsPanel();
        eventsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
        eventsContainer.setMaximumSize(new Dimension(Integer.MAX_VALUE, eventsContainer.getPreferredSize().height));
        centerWrapper.add(eventsContainer);

        this.add(centerWrapper, BorderLayout.CENTER);
        this.add(this.setUpQuickLinks(), BorderLayout.SOUTH);
    }

    void setupSidePanel() {
        this.setupVersionPanel();
        this.setUpQuickLinks();
        this.addSidePanel();

        // Update version panel with Embargo plugin information
        updateLoggedIn(false);
    }

    public void init() {
        this.setupSidePanel();
        logOut();
    }

    public void updateLoggedIn(boolean scheduled) {
        if (dataManager.stopTryingForAccount.get()) {
            emailLabel.setText("Account not registered with Embargo");
            missingRequirementsPanelX.removeAll();
            missingRequirementsContainer.removeAll();
            missingRequirementsPanel.removeAll();
            missingRequiredItemsLabel.removeAll();
            missingRequirementsContainer.removeAll();
            missingRequirementsContainer.revalidate();
            missingRequirementsContainer.repaint();
            return;
        }
        if (!isLoggedIn || scheduled) {
            if (client != null && client.getLocalPlayer() != null) {
                var username = client.getLocalPlayer().getName();

                // If username isn't available yet, bail out and let scheduled retry handle it
                if (username == null || username.isEmpty()) {
                    return;
                }

                // Only show loading state on first login, not on scheduled refreshes
                boolean isFirstLogin = !this.isLoggedIn;
                this.isLoggedIn = true;

                loggedLabel.setText(htmlLabel("Signed in as ", " " + username));

                dataManager.isUserRegisteredAsync(username, isRegistered -> {
                    if (!isRegistered) {
                        emailLabel.setText("Account not registered with Embargo");
                        return;
                    }
                });

                // Toggle section visibility
                loggedOutSection.setVisible(false);
                accountInfoSection.setVisible(true);

                // Only show "Loading..." on first login
                if (isFirstLogin) {
                    // Refresh events (Of The Week and Bounties) on login
                    fetchAndUpdateEvents();
                    fetchAndUpdateBounties();
                    embargoScoreLabel.setText(htmlLabel("Embargo Score:", " Loading..."));
                    accountScoreLabel.setText(htmlLabel("Account Score:", " Loading..."));
                    communityScoreLabel.setText(htmlLabel("Community Score:", " Loading..."));
                    currentRankLabel.setText(htmlLabel("Current Rank:", " Loading..."));
                    currentCALabel.setText(htmlLabel("Current CA Tier:", " Loading..."));
                }

                isRegisteredWithClanLabel.setText(htmlLabel("Account registered:", " Yes"));

                // get gear asynchronously
                dataManager.getProfileAsync(username, false).thenAccept(embargoProfileData -> {
                    // This code runs when the profile data is received
                    // We need to run UI updates on the client thread
                    clientThread.invokeLater(() -> {
                        // Check if profile data is valid before processing
                        if (embargoProfileData == null) {
                            return;
                        }

                        JsonElement currentAccountPoints = embargoProfileData.get("accountPoints");
                        JsonElement currentCommunityPoints = embargoProfileData.get("communityPoints");

                        // Parse points safely, defaulting to 0 if null
                        int accountPoints = (currentAccountPoints != null && !currentAccountPoints.isJsonNull())
                                ? currentAccountPoints.getAsInt() : 0;
                        int communityPoints = (currentCommunityPoints != null && !currentCommunityPoints.isJsonNull())
                                ? currentCommunityPoints.getAsInt() : 0;

                        embargoScoreLabel.setText(htmlLabel("Embargo Score:", " " + (accountPoints + communityPoints)));
                        accountScoreLabel.setText(htmlLabel("Account Score:", " " + accountPoints));
                        communityScoreLabel.setText(htmlLabel("Community Score:", " " + communityPoints));

                        JsonElement getCurrentCAName = embargoProfileData.get("currentHighestCAName");
                        JsonObject currentRank = embargoProfileData.getAsJsonObject("currentRank");

                        String currentRankDisplay = "N/A";
                        if (currentRank != null) {
                            JsonElement currentRankName = currentRank.get("name");
                            if (currentRankName != null && !currentRankName.isJsonNull()) {
                                currentRankDisplay = currentRankName.getAsString();
                            }
                        }
                        currentRankLabel.setText(htmlLabel("Current Rank:", " " + currentRankDisplay));

                        String displayCAName = "N/A";
                        if (getCurrentCAName != null && !getCurrentCAName.isJsonNull()) {
                            displayCAName = getCurrentCAName.getAsString().replace(" Combat Achievement", "");
                        }
                        currentCALabel.setText(htmlLabel("Current CA Tier:", " " + displayCAName));

                        JsonArray missingGearReqs = embargoProfileData.getAsJsonArray("missingGearRequirements");
                        JsonArray missingUntradableItemIdReqs = embargoProfileData
                                .getAsJsonArray("missingUntradableItemIds");

                        ArrayList<String> alreadyProcessed = new ArrayList<>();

                        // Build out the missing requirements panel
                        if (missingGearReqs.size() > 0 || missingUntradableItemIdReqs.size() > 0) {
                            // Process items off the client thread to avoid blocking chunk loading
                            // Use executorService to perform all item ID lookups asynchronously
                            executorService.execute(() -> {
                                // Pre-resolve all item IDs off the client thread (these are blocking calls)
                                java.util.List<Object[]> dynamicItemsData = new ArrayList<>();
                                java.util.List<Object[]> regularItemsData = new ArrayList<>();

                                for (JsonElement mi : missingGearReqs) {
                                    String itemName = mi.getAsString();
                                    alreadyProcessed.add(itemName);
                                    log.debug("Processing {} in missingGearReqs", itemName);

                                    if (itemName.contains("|")) {
                                        // DynamicMissingItem: pre-resolve all item IDs
                                        String[] dynamicNames = itemName.split("\\|");
                                        int[] itemIds = new int[dynamicNames.length];
                                        for (int i = 0; i < dynamicNames.length; i++) {
                                            itemIds[i] = missingRequirementsPanelX.findItemIdByName(dynamicNames[i].trim());
                                        }
                                        dynamicItemsData.add(new Object[]{dynamicNames, itemIds});
                                    } else {
                                        // Regular item: pre-resolve item ID
                                        int itemId = missingRequirementsPanelX.findItemIdByName(itemName);
                                        regularItemsData.add(new Object[]{itemName, itemId});
                                    }
                                }

                                java.util.List<Integer> untradableIds = new ArrayList<>();
                                for (JsonElement mu : missingUntradableItemIdReqs) {
                                    if (alreadyProcessed.contains(mu.getAsString())) {
                                        log.debug("{} already added, skipping missingUntradableItemIdReqs",
                                                mu.getAsString());
                                        continue;
                                    }
                                    untradableIds.add(mu.getAsInt());
                                }

                                // Now add all items on the client thread with batching enabled
                                clientThread.invokeLater(() -> {
                                    // Begin batching to prevent multiple panel rebuilds
                                    missingRequirementsPanelX.beginBatchUpdate();

                                    try {
                                        // Add all dynamic items
                                        for (Object[] data : dynamicItemsData) {
                                            String[] names = (String[]) data[0];
                                            int[] ids = (int[]) data[1];
                                            missingRequirementsPanelX.addDynamicMissingItem(names, ids, 3000);
                                        }

                                        // Add all regular items
                                        for (Object[] data : regularItemsData) {
                                            String name = (String) data[0];
                                            int id = (int) data[1];
                                            missingRequirementsPanelX.addMissingItem(name, id);
                                        }

                                        // Add untradable items
                                        for (int itemId : untradableIds) {
                                            missingRequirementsPanelX.addMissingItem("", itemId);
                                        }
                                    } finally {
                                        // End batching - this triggers a single panel rebuild
                                        missingRequirementsPanelX.endBatchUpdate();
                                    }

                                    // Update the container panel
                                    missingRequirementsPanel.removeAll();
                                    missingRequirementsPanel.add(missingRequirementsPanelX);
                                    missingRequirementsPanel.revalidate();
                                    missingRequirementsPanel.repaint();

                                    // Update item count
                                    int totalItems = dynamicItemsData.size() + regularItemsData.size() + untradableIds.size();
                                    updateMissingItemCount(totalItems);
                                });
                            });
                        } else {
                            missingRequiredItemsLabel.setText(htmlLabel("Missing Requirements: ", "None"));
                            updateMissingItemCount(0);
                        }
                    });
                }).exceptionally(ex -> {
                    log.error("Error fetching profile data", ex);
                    return null;
                });

                this.isLoggedIn = true;

            }
        }
    }

    public void logOut() {
        this.isLoggedIn = false;

        // Panel may not be initialized yet if logout event fires early
        if (loggedOutSection == null || accountInfoSection == null) {
            return;
        }

        // Update labels
        emailLabel.setContentType("text/html");
        emailLabel.setText("Sign in to send data to Embargo.");
        loggedLabel.setText("Not signed in");

        // Toggle section visibility
        loggedOutSection.setVisible(true);
        accountInfoSection.setVisible(false);

        // Reset missing gear requirements
        missingRequiredItemsLabel
                .setText(htmlLabel("Sign in to see what requirements", " you are missing for rank up"));
        missingRequiredItemsLabel.setFont(smallFont);
        missingRequiredItemsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        missingRequirementsPanelX.clearItems();
        updateMissingItemCount(0);

        // Rebuild missing requirements panel content (preserve header)
        missingRequirementsPanel.removeAll();
        missingRequirementsPanel.add(missingRequiredItemsLabel);

        // Reset collapse state
        missingRequirementsCollapsed = false;
        if (collapseIndicator != null) {
            collapseIndicator.setText("\u25BC "); // Down arrow
        }
        if (collapsibleContent != null) {
            collapsibleContent.setVisible(true);
        }

        // Set to NA
        isRegisteredWithClanLabel.setText(htmlLabel("Account registered:", " No"));
        embargoScoreLabel.setText(htmlLabel("Embargo Score:", " N/A"));
        currentRankLabel.setText(htmlLabel("Current Rank:", " N/A"));
        accountScoreLabel.setText(htmlLabel("Account Score:", " N/A"));
        communityScoreLabel.setText(htmlLabel("Community Score:", " N/A"));
        currentCALabel.setText(htmlLabel("Current TA Tier:", " N/A"));

        // Refresh UI
        versionPanel.revalidate();
        versionPanel.repaint();
        missingRequirementsPanel.revalidate();
        missingRequirementsPanel.repaint();
        missingRequirementsContainer.revalidate();
        missingRequirementsContainer.repaint();
        this.revalidate();
        this.repaint();
    }

    public void reset() {
        eventBus.unregister(this);
        missingRequirementsPanelX.shutdown();
        this.updateLoggedIn(false);
    }

    /**
     * Builds a link panel with a given icon, text and url to redirect to.
     */
    private static JPanel buildLinkPanel(ImageIcon icon, String topText, String bottomText, String url) {
        return buildLinkPanel(icon, topText, bottomText, () -> LinkBrowser.browse(url));
    }

    /**
     * Builds a link panel with a given icon, text and callable to call.
     */
    private static JPanel buildLinkPanel(ImageIcon icon, String topText, String bottomText, Runnable callback) {
        JPanel container = new JPanel();
        container.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        container.setLayout(new BorderLayout());
        container.setBorder(new EmptyBorder(10, 10, 10, 10));

        final Color hoverColor = ColorScheme.DARKER_GRAY_HOVER_COLOR;
        final Color pressedColor = ColorScheme.DARKER_GRAY_COLOR.brighter();

        JLabel iconLabel = new JLabel(icon);
        container.add(iconLabel, BorderLayout.WEST);

        JPanel textContainer = new JPanel();
        textContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        textContainer.setLayout(new GridLayout(2, 1));
        textContainer.setBorder(new EmptyBorder(5, 10, 5, 10));

        container.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent mouseEvent) {
                container.setBackground(pressedColor);
                textContainer.setBackground(pressedColor);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                callback.run();
                container.setBackground(hoverColor);
                textContainer.setBackground(hoverColor);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                container.setBackground(hoverColor);
                textContainer.setBackground(hoverColor);
                container.setCursor(new Cursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                container.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                textContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                container.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
            }
        });

        JLabel topLine = new JLabel(topText);
        topLine.setForeground(Color.WHITE);
        topLine.setFont(FontManager.getRunescapeSmallFont());

        JLabel bottomLine = new JLabel(bottomText);
        bottomLine.setForeground(Color.WHITE);
        bottomLine.setFont(FontManager.getRunescapeSmallFont());

        textContainer.add(topLine);
        textContainer.add(bottomLine);

        container.add(textContainer, BorderLayout.CENTER);

        JLabel arrowLabel = new JLabel(ARROW_RIGHT_ICON);
        container.add(arrowLabel, BorderLayout.EAST);

        return container;
    }
}
