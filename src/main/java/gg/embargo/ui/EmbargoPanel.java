package gg.embargo.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gg.embargo.DataManager;
import gg.embargo.EmbargoConfig;
import gg.embargo.EmbargoPlugin;
import gg.embargo.bingo.BingoManager;
import gg.embargo.bingo.BingoState;
import gg.embargo.bingo.BingoTeam;
import gg.embargo.bingo.BingoTile;
import gg.embargo.bingo.BingoTileStatus;
import gg.embargo.bingo.BingoTeamTileProgress;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
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

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

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
import java.time.temporal.ChronoUnit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

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
    private MissingRequirementsPanel missingRequirementsComponent;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ScheduledExecutorService executorService;

    @Inject
    private EmbargoConfig config;

    @Inject
    private BingoManager bingoManager;

    @Inject
    private OkHttpClient okHttpClient;

    @Setter
    public boolean isLoggedIn = false;

    JPanel versionPanel = new JPanel();
    JPanel missingRequirementsPanel = new JPanel();
    private static final ImageIcon ARROW_RIGHT_ICON = new ImageIcon(
            ImageUtil.loadImageResource(EmbargoPanel.class, "/util/arrow_right.png"));
    private static final ImageIcon DISCORD_ICON = new ImageIcon(
            ImageUtil.loadImageResource(EmbargoPanel.class, "/discord_icon.png"));
    private static final ImageIcon GITHUB_ICON = new ImageIcon(ImageUtil.loadImageResource(EmbargoPanel.class, "/github_icon.png"));
    private static final ImageIcon WEBSITE_ICON = new ImageIcon(ImageUtil.loadImageResource(EmbargoPanel.class, "/website_icon.png"));
    private final JRichTextPane emailLabel = new JRichTextPane();
    private final JLabel loggedLabel = new JLabel();
    private final JLabel embargoScoreLabel = new JLabel(htmlLabel("Embargo Score:", " N/A"));
    private final JLabel accountScoreLabel = new JLabel(htmlLabel("Account Score:", " N/A"));
    private final JLabel communityScoreLabel = new JLabel(htmlLabel("Community Score:", " N/A"));
    private final JLabel currentRankLabel = new JLabel(htmlLabel("Current Rank:", " N/A"));
    private final JLabel isRegisteredWithClanLabel = new JLabel(htmlLabel("Account registered:", " No"));
    private final JLabel currentCALabel = new JLabel(htmlLabel("Current CA Tier:", " N/A"));
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

    // Polls subsection
    private JPanel pollsPanel;
    private final Set<Integer> alertedPollIds = new HashSet<>();

    // Of The Week event alerts
    private final Set<Integer> alertedEventIds = new HashSet<>();

    // Bingo subsection
    private JPanel bingoPanel;
    private final Set<Integer> alertedBingoIds = new HashSet<>();

    // Stored reference to allow removal on shutdown
    private Consumer<List<BingoState>> bingoStateChangeListener;

    // Periodic refresh for events/bounties/polls
    private static final int EVENTS_REFRESH_INTERVAL_MINUTES = 1;
    private ScheduledFuture<?> eventsRefreshTask;

    @Inject
    private EmbargoPanel() {
    }

    private String htmlLabel(String key, String value) {
        return "<html><body style = 'color:#a5a5a5'>" + key + "<span style = 'color:white'>" + value
                + "</span></body></html>";
    }

    private String getEmbargoTag() {
        java.awt.Color color = config.embargoMessageColor();
        String hex = String.format("%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
        return "<col=" + hex + ">[Embargo]</col>";
    }

    /**
     * Creates a styled label with smallFont, light gray color, and left alignment
     */
    private JLabel createSmallLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(smallFont);
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setHorizontalAlignment(SwingConstants.LEFT);
        return label;
    }

    /**
     * Creates a styled label with smallFont, specified color, and left alignment
     */
    private JLabel createSmallLabel(String text, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(smallFont);
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setHorizontalAlignment(SwingConstants.LEFT);
        return label;
    }

    /**
     * Creates a section header label (bold, 12pt for main headers, 11pt for
     * subsections)
     */
    private JLabel createHeader(String text, boolean isMain) {
        JLabel header = new JLabel(text);
        header.setFont(new Font("SansSerif", Font.BOLD, isMain ? 12 : 11));
        header.setForeground(isMain ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        return header;
    }

    /**
     * Creates a panel with vertical BoxLayout and dark background
     */
    private JPanel createVerticalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    /**
     * Makes a label clickable, opening the given URL on click
     */
    private void makeClickable(JLabel label, String url, String tooltip) {
        label.setCursor(new Cursor(Cursor.HAND_CURSOR));
        label.setToolTipText(tooltip);

        // Use a light blue color for links to indicate clickability
        final Color linkColor = new Color(0x5D, 0x9C, 0xEC);

        // Only apply link color to non-HTML labels (HTML labels have their own styling)
        String text = label.getText();
        boolean isHtml = text != null && text.toLowerCase().startsWith("<html>");
        if (!isHtml) {
            label.setForeground(linkColor);
        }

        // Use label's foreground color for underline, or link color for HTML labels
        final Color underlineColor = isHtml ? Color.WHITE : linkColor;

        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                LinkBrowser.browse(url);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                // Add underline border on hover
                label.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, underlineColor));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                // Remove underline border
                label.setBorder(null);
            }
        });
    }

    /**
     * Formats minutes remaining into a human-readable string (e.g., "2d 5h", "3h
     * 30m", "45 min")
     */
    private String formatTimeRemaining(long minutesRemaining) {
        if (minutesRemaining > 1440) { // More than 24 hours
            long days = minutesRemaining / 1440;
            return days + "d " + ((minutesRemaining % 1440) / 60) + "h";
        } else if (minutesRemaining > 60) {
            long hours = minutesRemaining / 60;
            return hours + "h " + (minutesRemaining % 60) + "m";
        } else if (minutesRemaining > 0) {
            return minutesRemaining + " min";
        } else {
            return "Ending soon";
        }
    }

    /**
     * Applies standard styling to a label (smallFont, light gray, left aligned)
     */
    private void styleLabel(JLabel label) {
        label.setFont(smallFont);
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setHorizontalAlignment(SwingConstants.LEFT);
    }

    void setupVersionPanel() {
        // Set up versionPanel with BoxLayout for better control
        versionPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        versionPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        versionPanel.setLayout(new BoxLayout(versionPanel, BoxLayout.Y_AXIS));

        // Set up Embargo Clan Version at top of Version panel
        JLabel version = new JLabel(htmlLabel("Embargo Clan Version: ", "1.5.5"));
        version.setFont(smallFont);
        version.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Apply standard styling to all info labels
        for (JLabel label : new JLabel[] { isRegisteredWithClanLabel, embargoScoreLabel, accountScoreLabel,
                communityScoreLabel, currentCALabel, loggedLabel, currentRankLabel }) {
            styleLabel(label);
        }

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
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(1, 0, 0, 0, ColorScheme.LIGHT_GRAY_COLOR),
                new EmptyBorder(10, 10, 10, 10)));

        // Links Header
        JLabel linksHeader = new JLabel("Links");
        linksHeader.setFont(new Font("SansSerif", Font.BOLD, 12));
        linksHeader.setForeground(Color.WHITE);
        linksHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.add(linksHeader);
        wrapper.add(Box.createVerticalStrut(8));

        JPanel actionsContainer = new JPanel();
        actionsContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        actionsContainer.setLayout(new GridLayout(0, 1, 0, 8));
        actionsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);

        actionsContainer
                .add(buildLinkPanel(DISCORD_ICON, "Join us on our", "Discord", "https://discord.gg/YDGGyP3VEq"));
        actionsContainer.add(buildLinkPanel(WEBSITE_ICON, "Go to our", "clan website", "https://embargo.gg/"));
        actionsContainer.add(buildLinkPanel(GITHUB_ICON, "Report a bug or", "inspect the plugin code",
                "https://github.com/EmbargoOSRS/Embargo-Plugin"));

        wrapper.add(actionsContainer);
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
        eventsContainer = createVerticalPanel();
        eventsContainer.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(1, 0, 0, 0, ColorScheme.LIGHT_GRAY_COLOR),
                new EmptyBorder(10, 10, 10, 10)));

        // Main Events Header
        eventsContainer.add(createHeader("Events", true));
        eventsContainer.add(Box.createVerticalStrut(8));

        // === Of The Week Subsection ===
        eventsContainer.add(createHeader("Of The Week", false));
        eventsContainer.add(Box.createVerticalStrut(4));

        ofTheWeekOngoingPanel = createVerticalPanel();
        ofTheWeekOngoingPanel.add(createSmallLabel("Loading..."));
        eventsContainer.add(ofTheWeekOngoingPanel);

        ofTheWeekUpcomingPanel = createVerticalPanel();
        ofTheWeekUpcomingPanel.setVisible(false);
        eventsContainer.add(ofTheWeekUpcomingPanel);

        eventsContainer.add(Box.createVerticalStrut(8));

        // === Bounties Subsection ===
        eventsContainer.add(createHeader("Bounties", false));
        eventsContainer.add(Box.createVerticalStrut(4));

        bountiesListPanel = createVerticalPanel();
        bountiesListPanel.add(createSmallLabel("Loading..."));
        eventsContainer.add(bountiesListPanel);

        eventsContainer.add(Box.createVerticalStrut(8));

        // === Polls Subsection ===
        eventsContainer.add(createHeader("Polls", false));
        eventsContainer.add(Box.createVerticalStrut(4));

        pollsPanel = createVerticalPanel();
        pollsPanel.add(createSmallLabel("Loading..."));
        eventsContainer.add(pollsPanel);

        eventsContainer.add(Box.createVerticalStrut(8));

        // === Bingo Subsection ===
        eventsContainer.add(createHeader("Bingo", false));
        eventsContainer.add(Box.createVerticalStrut(4));

        bingoPanel = createVerticalPanel();
        bingoPanel.add(createSmallLabel("Loading..."));
        eventsContainer.add(bingoPanel);

        // Stagger API calls to avoid network burst on panel init
        fetchAndUpdateEvents();
        executorService.schedule(this::fetchAndUpdateBounties, 100, TimeUnit.MILLISECONDS);
        executorService.schedule(this::fetchAndUpdatePoll, 200, TimeUnit.MILLISECONDS);
        executorService.schedule(this::fetchAndUpdateBingo, 300, TimeUnit.MILLISECONDS);
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

    private static final Color COLOR_GREEN = new Color(0x00, 0xc8, 0x00);
    private static final Color COLOR_ORANGE = new Color(0xff, 0xc0, 0x00);
    private static final Color COLOR_YELLOW = new Color(0xff, 0xff, 0x00);
    private static final Color COLOR_GRAY = new Color(0x99, 0x99, 0x99);

    // Static cache for bingo tile images (persists across panel refreshes)
    // Use bounded LRU cache to prevent unbounded memory growth
    private static final int MAX_TILE_IMAGE_CACHE_SIZE = 100;
    private static final Map<String, ImageIcon> TILE_IMAGE_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, ImageIcon>(MAX_TILE_IMAGE_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ImageIcon> eldest) {
                    return size() > MAX_TILE_IMAGE_CACHE_SIZE;
                }
            });

    // Limit concurrent tile image loads to prevent flooding network on panel load
    private static final int MAX_CONCURRENT_IMAGE_LOADS = 3;
    private static final Semaphore imageLoadSemaphore = new Semaphore(MAX_CONCURRENT_IMAGE_LOADS);

    /**
     * Updates the Of The Week panel with the API response
     */
    private void updateOfTheWeekPanel(JsonArray events) {
        ofTheWeekOngoingPanel.removeAll();
        ofTheWeekUpcomingPanel.removeAll();

        if (events == null || events.size() == 0) {
            ofTheWeekOngoingPanel.add(createSmallLabel("No events"));
            ofTheWeekUpcomingPanel.setVisible(false);
            eventsContainer.revalidate();
            eventsContainer.repaint();
            return;
        }

        // Separate ongoing and upcoming events
        List<JsonObject> ongoingEvents = new ArrayList<>();
        List<JsonObject> upcomingEvents = new ArrayList<>();

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
            ofTheWeekOngoingPanel.add(createSmallLabel("No ongoing events"));
        } else {
            ofTheWeekOngoingPanel.add(createSmallLabel("Ongoing", COLOR_GREEN));

            for (JsonObject event : ongoingEvents) {
                addEventToPanel(ofTheWeekOngoingPanel, event, true);

                // Alert user if this is a new ongoing event they haven't been alerted about
                int eventId = event.has("wiseOldManId") ? event.get("wiseOldManId").getAsInt()
                        : (event.has("id") ? event.get("id").getAsInt() : 0);
                if (eventId > 0 && isLoggedIn && !alertedEventIds.contains(eventId)) {
                    alertedEventIds.add(eventId);
                    sendEventAlert(event);
                }
            }
        }

        // Display upcoming events
        if (!upcomingEvents.isEmpty()) {
            ofTheWeekUpcomingPanel.add(Box.createVerticalStrut(6));
            ofTheWeekUpcomingPanel.add(createSmallLabel("Upcoming", COLOR_ORANGE));

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
        panel.add(Box.createVerticalStrut(4));

        String name = event.has("name") ? event.get("name").getAsString() : "Unknown";
        String metric = event.has("metric") ? event.get("metric").getAsString() : "";
        int participants = event.has("participantCount") ? event.get("participantCount").getAsInt() : 0;
        int eventId = event.has("wiseOldManId") ? event.get("wiseOldManId").getAsInt()
                : (event.has("id") ? event.get("id").getAsInt() : 0);

        // Shorten event name: "Boss Of The Week #X |" -> "BOTW |", "Skill Of The Week
        // #X |" -> "SOTW |"
        String displayName = name
                .replaceFirst("Boss Of The Week #\\d+\\s*\\|", "BOTW |")
                .replaceFirst("Skill Of The Week #\\d+\\s*\\|", "SOTW |")
                .trim();

        // Event name as clickable link
        JLabel nameLabel = createSmallLabel(displayName, Color.WHITE);
        if (eventId > 0) {
            makeClickable(nameLabel, "https://embargo.gg/competition/" + eventId, "Click to view on embargo.gg");
        }
        panel.add(nameLabel);

        // Metric (using "Metric:" as label since it could be skill or boss)
        if (!metric.isEmpty()) {
            String formattedMetric = metric.substring(0, 1).toUpperCase() + metric.substring(1).replace("_", " ");
            JLabel metricLabel = new JLabel(htmlLabel("Metric:", " " + formattedMetric));
            styleLabel(metricLabel);
            panel.add(metricLabel);
        }

        // Participants (only for ongoing)
        if (isOngoing) {
            JLabel participantsLabel = new JLabel(htmlLabel("Participants:", " " + participants));
            styleLabel(participantsLabel);
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
            bountiesListPanel.add(createSmallLabel("No bounties"));
            eventsContainer.revalidate();
            eventsContainer.repaint();
            return;
        }

        JsonArray bounties = response.getAsJsonArray("bounties");
        List<JsonObject> activeBounties = new ArrayList<>();
        List<JsonObject> recentBounties = new ArrayList<>();

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
            bountiesListPanel.add(createSmallLabel("Active", COLOR_GREEN));

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

            bountiesListPanel.add(createSmallLabel("Recent"));

            int count = 0;
            for (JsonObject bounty : recentBounties) {
                if (count >= 2)
                    break;
                addBountyToPanel(bountiesListPanel, bounty, false);
                count++;
            }
            hasContent = true;
        }

        if (!hasContent) {
            bountiesListPanel.add(createSmallLabel("No bounties"));
        }

        eventsContainer.revalidate();
        eventsContainer.repaint();
    }

    /**
     * Adds a single bounty entry to the given panel
     */
    private void addBountyToPanel(JPanel panel, JsonObject bounty, boolean isActive) {
        panel.add(Box.createVerticalStrut(4));

        String name = bounty.has("name") ? bounty.get("name").getAsString() : "Unknown";
        String target = name.replaceFirst("Bounty #\\d+ - ", "");
        int bountyId = bounty.has("id") ? bounty.get("id").getAsInt() : 0;

        // Target name as clickable link
        JLabel targetLabel = createSmallLabel(target, Color.WHITE);
        if (bountyId > 0) {
            makeClickable(targetLabel, "https://embargo.gg/bounties/" + bountyId, "Click to view on embargo.gg");
        }
        panel.add(targetLabel);

        // Time remaining (for active)
        if (isActive && bounty.has("endTime")) {
            try {
                String endTimeStr = bounty.get("endTime").getAsString();
                ZonedDateTime endTime = ZonedDateTime.parse(endTimeStr);
                long minutesRemaining = Instant.now().until(endTime.toInstant(), ChronoUnit.MINUTES);

                JLabel timeLabel = new JLabel(htmlLabel("Time left:", " " + formatTimeRemaining(minutesRemaining)));
                styleLabel(timeLabel);
                panel.add(timeLabel);
            } catch (Exception e) {
                log.debug("Failed to parse bounty end time", e);
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

        if (!config.enableBountyAlerts()) {
            return;
        }

        String name = bounty.get("name").getAsString();
        String target = name.replaceFirst("Bounty #\\d+ - ", "");

        clientThread.invokeLater(() -> {
            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    getEmbargoTag() + " Active bounty: <col=ffffff>" + target
                            + "</col>! Check the side panel for details.",
                    null);
        });
    }

    /**
     * Clears all alerted IDs (call on logout to allow re-alerting on next login)
     */
    public void clearAlertedIds() {
        alertedBountyIds.clear();
        alertedPollIds.clear();
        alertedEventIds.clear();
        alertedBingoIds.clear();
    }

    /**
     * Starts the periodic background refresh for events, bounties, and polls.
     * Runs every minute while the user is logged in.
     */
    private void startPeriodicEventsRefresh() {
        // Cancel any existing task first
        stopPeriodicEventsRefresh();

        eventsRefreshTask = executorService.scheduleAtFixedRate(() -> {
            if (isLoggedIn) {
                log.debug("Periodic refresh: fetching events, bounties, polls, and bingo");
                // Stagger API calls to avoid network burst
                fetchAndUpdateEvents();
                executorService.schedule(this::fetchAndUpdateBounties, 100, TimeUnit.MILLISECONDS);
                executorService.schedule(this::fetchAndUpdatePoll, 200, TimeUnit.MILLISECONDS);
                executorService.schedule(this::fetchAndUpdateBingo, 300, TimeUnit.MILLISECONDS);
            }
        }, EVENTS_REFRESH_INTERVAL_MINUTES, EVENTS_REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * Stops the periodic background refresh for events, bounties, and polls.
     */
    private void stopPeriodicEventsRefresh() {
        if (eventsRefreshTask != null && !eventsRefreshTask.isCancelled()) {
            eventsRefreshTask.cancel(false);
            eventsRefreshTask = null;
        }
    }

    /**
     * Fetches the last active poll from API and updates the panel
     */
    private void fetchAndUpdatePoll() {
        dataManager.getLastPollAsync().thenAccept(response -> {
            SwingUtilities.invokeLater(() -> {
                updatePollPanel(response);
            });
        });
    }

    /**
     * Updates the poll panel with the API response
     */
    private void updatePollPanel(JsonObject poll) {
        pollsPanel.removeAll();

        if (poll == null) {
            pollsPanel.add(createSmallLabel("No active polls"));
            eventsContainer.revalidate();
            eventsContainer.repaint();
            return;
        }

        // Alert user if this is a new poll they haven't been alerted about
        int pollId = poll.has("id") ? poll.get("id").getAsInt() : 0;
        if (pollId > 0 && isLoggedIn && !alertedPollIds.contains(pollId)) {
            alertedPollIds.add(pollId);
            sendPollAlert(poll);
        }

        pollsPanel.add(Box.createVerticalStrut(4));
        pollsPanel.add(createSmallLabel("Active", COLOR_GREEN));
        pollsPanel.add(Box.createVerticalStrut(4));

        // Poll title as clickable link
        String title = poll.has("title") ? poll.get("title").getAsString() : "Unknown Poll";
        String discordUrl = poll.has("discordUrl") ? poll.get("discordUrl").getAsString() : null;

        JLabel titleLabel = createSmallLabel(title, Color.WHITE);
        if (discordUrl != null && !discordUrl.isEmpty()) {
            makeClickable(titleLabel, discordUrl, "Click to view poll on Discord");
        }
        pollsPanel.add(titleLabel);

        // Time remaining
        if (poll.has("endsAt")) {
            try {
                String endsAtStr = poll.get("endsAt").getAsString();
                ZonedDateTime endsAt = ZonedDateTime.parse(endsAtStr);
                long minutesRemaining = Instant.now().until(endsAt.toInstant(), ChronoUnit.MINUTES);

                JLabel timeLabel = new JLabel(htmlLabel("Ends in:", " " + formatTimeRemaining(minutesRemaining)));
                styleLabel(timeLabel);
                pollsPanel.add(timeLabel);
            } catch (Exception e) {
                log.debug("Failed to parse poll end time", e);
            }
        }

        eventsContainer.revalidate();
        eventsContainer.repaint();
    }

    /**
     * Sends a chat message alert for an active poll
     */
    private void sendPollAlert(JsonObject poll) {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        if (!config.enablePollAlerts()) {
            return;
        }

        String title = poll.has("title") ? poll.get("title").getAsString() : "New Poll";

        clientThread.invokeLater(() -> {
            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    getEmbargoTag() + " New poll: <col=ffffff>" + title
                            + "</col>! Check the side panel or Discord to vote.",
                    null);
        });
    }

    /**
     * Fetches bingo state and updates the panel
     */
    private void fetchAndUpdateBingo() {
        if (!config.enableBingo()) {
            SwingUtilities.invokeLater(this::updateBingoPanel);
            return;
        }
        // Trigger a refresh of bingo state, then update the UI
        bingoManager.refreshBingoState();
        // Update UI after a short delay to allow async fetch to complete
        executorService.schedule(() -> SwingUtilities.invokeLater(this::updateBingoPanel), 500, TimeUnit.MILLISECONDS);
    }

    /**
     * Updates the bingo panel with the current bingo states
     */
    private void updateBingoPanel() {
        bingoPanel.removeAll();

        // Check if bingo is disabled via config
        if (!config.enableBingo()) {
            bingoPanel.add(createSmallLabel("Bingo disabled"));
            eventsContainer.revalidate();
            eventsContainer.repaint();
            return;
        }

        List<BingoState> states = bingoManager.getCurrentStates();

        if (states == null || states.isEmpty()) {
            bingoPanel.add(createSmallLabel("No active bingo"));
            eventsContainer.revalidate();
            eventsContainer.repaint();
            return;
        }

        // Filter to only active bingos
        List<BingoState> activeStates = states.stream()
                .filter(BingoState::isActive)
                .collect(Collectors.toList());

        if (activeStates.isEmpty()) {
            bingoPanel.add(createSmallLabel("No active bingo"));
            eventsContainer.revalidate();
            eventsContainer.repaint();
            return;
        }

        // Display each active bingo
        boolean firstBingo = true;
        for (BingoState state : activeStates) {
            if (!firstBingo) {
                bingoPanel.add(Box.createVerticalStrut(8));
                bingoPanel.add(new JSeparator());
                bingoPanel.add(Box.createVerticalStrut(4));
            }
            firstBingo = false;

            addBingoStateToPanel(state);
        }

        eventsContainer.revalidate();
        eventsContainer.repaint();
    }

    /**
     * Adds a single bingo state's information to the bingo panel
     */
    private void addBingoStateToPanel(BingoState state) {
        // Show bingo name and status
        String bingoName = state.getName();
        int bingoId = state.getId();

        // Alert user if this is a new bingo they haven't been alerted about
        if (isLoggedIn && !alertedBingoIds.contains(bingoId)) {
            alertedBingoIds.add(bingoId);
            // BingoManager handles alerts, so we don't need to send one here
        }

        // Bingo name with status - "Event Name - Active"
        JLabel nameLabel = new JLabel("<html><body style='color:white'>" + bingoName +
                " - <span style='color:#00ff00'>Active</span></body></html>");
        nameLabel.setFont(smallFont);
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameLabel.setHorizontalAlignment(SwingConstants.LEFT);
        makeClickable(nameLabel, "https://embargo.gg/bingo/" + bingoId, "Click to view on embargo.gg");
        bingoPanel.add(nameLabel);

        // Time remaining
        String timeRemaining = state.getFormattedTimeRemaining();
        JLabel timeLabel = new JLabel(htmlLabel("Ends in:", " " + timeRemaining));
        styleLabel(timeLabel);
        bingoPanel.add(timeLabel);

        // Check if enrolled
        if (state.isEnrolled()) {
            BingoTeam team = state.getUserTeam();

            bingoPanel.add(Box.createVerticalStrut(4));

            // Team name
            if (team != null) {
                JLabel teamLabel = new JLabel(htmlLabel("Team:", " " + team.getName()));
                styleLabel(teamLabel);
                bingoPanel.add(teamLabel);

                // Tiles completed
                int completed = state.getCompletedTileCount();
                int total = state.getTiles().size();
                JLabel tilesLabel = new JLabel(htmlLabel("Tiles:", " " + completed + "/" + total));
                styleLabel(tilesLabel);
                bingoPanel.add(tilesLabel);

                // Show visual bingo board grid
                addBingoBoardGrid(state);
            }
        } else {
            // Not enrolled
            bingoPanel.add(Box.createVerticalStrut(4));
            bingoPanel.add(createSmallLabel("Not enrolled", COLOR_ORANGE));
            bingoPanel.add(createSmallLabel("Visit embargo.gg to join"));
        }
    }

    /**
     * Adds a visual bingo board grid showing tile completion statuses with icons
     */
    private void addBingoBoardGrid(BingoState state) {
        int boardSize = state.getSize();
        int totalTiles = boardSize * boardSize;

        bingoPanel.add(Box.createVerticalStrut(8));

        // Create grid panel
        JPanel gridPanel = new JPanel(new GridLayout(boardSize, boardSize, 2, 2));
        gridPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        gridPanel.setBorder(new EmptyBorder(2, 2, 2, 2));
        gridPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Get tiles sorted by position
        List<BingoTile> sortedTiles = state.getTilesByPosition();

        // Calculate tile size based on panel width (aim for ~180px total width)
        int tileSize = Math.max(28, (180 - (boardSize + 1) * 2) / boardSize);
        int iconSize = tileSize - 6; // Leave room for border

        // Set grid size constraints
        int gridWidth = boardSize * tileSize + (boardSize + 1) * 2;
        int gridHeight = boardSize * tileSize + (boardSize + 1) * 2;
        gridPanel.setPreferredSize(new Dimension(gridWidth, gridHeight));
        gridPanel.setMaximumSize(new Dimension(gridWidth, gridHeight));

        for (int i = 0; i < totalTiles; i++) {
            // Find tile at this position
            BingoTile tile = null;
            for (BingoTile t : sortedTiles) {
                if (t.getPosition() == i) {
                    tile = t;
                    break;
                }
            }

            JPanel tilePanel = createBingoTileCell(tile, state, tileSize, iconSize);
            gridPanel.add(tilePanel);
        }

        bingoPanel.add(gridPanel);

        // Add legend
        bingoPanel.add(Box.createVerticalStrut(4));
        JPanel legendPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        legendPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        legendPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        legendPanel.add(createLegendItem(new Color(0x22, 0x8B, 0x22), "Done"));
        legendPanel.add(createLegendItem(new Color(0xDA, 0xA5, 0x20), "Partial"));
        legendPanel.add(createLegendItem(new Color(0x3C, 0x3C, 0x3C), "Todo"));

        bingoPanel.add(legendPanel);
    }

    /**
     * Creates a single bingo tile cell with icon and status border
     */
    private JPanel createBingoTileCell(BingoTile tile, BingoState state, int tileSize, int iconSize) {
        JPanel tilePanel = new JPanel(new BorderLayout());
        tilePanel.setPreferredSize(new Dimension(tileSize, tileSize));

        if (tile == null) {
            tilePanel.setBackground(new Color(0x2A, 0x2A, 0x2A)); // Empty slot
            return tilePanel;
        }

        BingoTeamTileProgress progress = state.getProgress(tile.getId());
        BingoTileStatus status = progress != null ? progress.getStatus() : BingoTileStatus.PENDING;

        // Set border color based on status
        Color borderColor;
        switch (status) {
            case COMPLETED:
                borderColor = new Color(0x22, 0x8B, 0x22); // Forest green
                break;
            case PARTIAL:
                borderColor = new Color(0xDA, 0xA5, 0x20); // Goldenrod
                break;
            default:
                borderColor = new Color(0x55, 0x55, 0x55); // Gray
                break;
        }

        tilePanel.setBackground(new Color(0x1E, 0x1E, 0x1E));
        tilePanel.setBorder(BorderFactory.createLineBorder(borderColor, 2));

        // Add tooltip with tile name
        String tooltipText = tile.getTitle();
        if (tooltipText != null && !tooltipText.isEmpty()) {
            if (progress != null && status == BingoTileStatus.PARTIAL) {
                tooltipText += " (" + progress.getCurrentCount() + "/" + tile.getRequiredCount() + ")";
            }
            tilePanel.setToolTipText(tooltipText);
        }

        // Try to load and display image
        String imageUrl = tile.getImageUrl();
        if (imageUrl != null && !imageUrl.isEmpty()) {
            loadTileImage(tilePanel, imageUrl, iconSize);
        }

        return tilePanel;
    }

    /**
     * Loads a tile image and adds it to the panel. Uses cache for speed.
     */
    private void loadTileImage(JPanel tilePanel, String imageUrl, int iconSize) {
        // Create cache key with size for proper scaling
        String cacheKey = imageUrl + "@" + iconSize;

        // Check cache first (on current thread for instant display)
        ImageIcon cachedIcon = TILE_IMAGE_CACHE.get(cacheKey);
        if (cachedIcon != null) {
            JLabel iconLabel = new JLabel(cachedIcon);
            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            tilePanel.add(iconLabel, BorderLayout.CENTER);
            return;
        }

        // Rate-limit concurrent image loads to prevent stuttering on panel load
        if (!imageLoadSemaphore.tryAcquire()) {
            // Queue the load for later if too many concurrent loads
            executorService.execute(() -> {
                try {
                    imageLoadSemaphore.acquire();
                    loadTileImageInternal(tilePanel, imageUrl, iconSize, cacheKey);
                } catch (InterruptedException e) {
                    // Don't interrupt shared executor threads - just skip this image load
                    log.debug("Image load interrupted for: {}", imageUrl);
                }
            });
            return;
        }

        loadTileImageInternal(tilePanel, imageUrl, iconSize, cacheKey);
    }

    private void loadTileImageInternal(JPanel tilePanel, String imageUrl, int iconSize, String cacheKey) {
        Request request = new Request.Builder()
                .url(imageUrl)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                imageLoadSemaphore.release();
                log.debug("Failed to load bingo tile image: {}", imageUrl);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        return;
                    }

                    BufferedImage originalImage = ImageIO.read(response.body().byteStream());

                    if (originalImage != null) {
                        // Scale image using faster method
                        Image scaledImage = originalImage.getScaledInstance(
                                iconSize, iconSize, Image.SCALE_SMOOTH);

                        // Convert to BufferedImage for better performance
                        BufferedImage bufferedScaled = new BufferedImage(iconSize, iconSize,
                                BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g2d = bufferedScaled.createGraphics();
                        g2d.drawImage(scaledImage, 0, 0, null);
                        g2d.dispose();

                        ImageIcon scaledIcon = new ImageIcon(bufferedScaled);

                        // Cache the scaled icon
                        TILE_IMAGE_CACHE.put(cacheKey, scaledIcon);

                        SwingUtilities.invokeLater(() -> {
                            JLabel iconLabel = new JLabel(scaledIcon);
                            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
                            tilePanel.add(iconLabel, BorderLayout.CENTER);
                            tilePanel.revalidate();
                            tilePanel.repaint();
                        });
                    }
                } catch (Exception e) {
                    log.debug("Failed to process bingo tile image: {}", imageUrl, e);
                } finally {
                    imageLoadSemaphore.release();
                }
            }
        });
    }

    /**
     * Creates a small legend item with a colored square and label
     */
    private JPanel createLegendItem(Color color, String text) {
        JPanel item = new JPanel();
        item.setLayout(new BoxLayout(item, BoxLayout.X_AXIS));
        item.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JPanel colorBox = new JPanel();
        colorBox.setPreferredSize(new Dimension(8, 8));
        colorBox.setMaximumSize(new Dimension(8, 8));
        colorBox.setMinimumSize(new Dimension(8, 8));
        colorBox.setBackground(color);
        item.add(colorBox);

        item.add(Box.createHorizontalStrut(3));

        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(Color.LIGHT_GRAY);
        item.add(label);

        return item;
    }

    /**
     * Sends a chat message alert for an ongoing Of The Week event
     */
    private void sendEventAlert(JsonObject event) {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        if (!config.enableEventAlerts()) {
            return;
        }

        String name = event.has("name") ? event.get("name").getAsString() : "New Event";
        String metric = event.has("metric") ? event.get("metric").getAsString() : "";
        String formattedMetric = metric.isEmpty() ? "Unknown"
                : metric.substring(0, 1).toUpperCase() + metric.substring(1).replace("_", " ");

        // Determine event type (BOTW or SOTW)
        String eventType = name.contains("Boss") ? "BOTW" : "SOTW";

        clientThread.invokeLater(() -> {
            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    getEmbargoTag() + " Active " + eventType + ": <col=ffffff>" + formattedMetric
                            + "</col>. Check the side panel for details.",
                    null);
        });
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
        missingRequirementsComponent.clearItems();
        updateMissingItemCount(0);

        // Stagger API calls to avoid network burst on manual refresh
        fetchAndUpdateEvents();
        executorService.schedule(this::fetchAndUpdateBounties, 100, TimeUnit.MILLISECONDS);
        executorService.schedule(this::fetchAndUpdateBingo, 200, TimeUnit.MILLISECONDS);

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

        // Register listener for bingo state changes to update UI
        // Store the listener so we can remove it on shutdown
        bingoStateChangeListener = states -> SwingUtilities.invokeLater(this::updateBingoPanel);
        bingoManager.addStateChangeListener(bingoStateChangeListener);
    }

    public void updateLoggedIn(boolean scheduled) {
        if (dataManager.stopTryingForAccount.get()) {
            emailLabel.setText("Account not registered with Embargo");
            missingRequirementsComponent.removeAll();
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
                    // Stagger API calls to avoid network burst on login
                    // Each call is delayed to prevent overwhelming the network
                    fetchAndUpdateEvents();
                    executorService.schedule(this::fetchAndUpdateBounties, 100, TimeUnit.MILLISECONDS);
                    executorService.schedule(this::fetchAndUpdatePoll, 200, TimeUnit.MILLISECONDS);
                    executorService.schedule(this::fetchAndUpdateBingo, 300, TimeUnit.MILLISECONDS);

                    // Start periodic refresh for events/bounties/polls
                    startPeriodicEventsRefresh();

                    embargoScoreLabel.setText(htmlLabel("Embargo Score:", " Loading..."));
                    accountScoreLabel.setText(htmlLabel("Account Score:", " Loading..."));
                    communityScoreLabel.setText(htmlLabel("Community Score:", " Loading..."));
                    currentRankLabel.setText(htmlLabel("Current Rank:", " Loading..."));
                    currentCALabel.setText(htmlLabel("Current CA Tier:", " Loading..."));
                }

                isRegisteredWithClanLabel.setText(htmlLabel("Account registered:", " Yes"));

                // get gear asynchronously
                dataManager.getProfileAsync(username, false).thenAcceptAsync(embargoProfileData -> {
                    // This code runs on a background thread - do all JSON parsing here
                    if (embargoProfileData == null) {
                        return;
                    }

                    // Parse all JSON data on background thread
                    JsonElement currentAccountPoints = embargoProfileData.get("accountPoints");
                    JsonElement currentCommunityPoints = embargoProfileData.get("communityPoints");

                    final int accountPoints = (currentAccountPoints != null && !currentAccountPoints.isJsonNull())
                            ? currentAccountPoints.getAsInt()
                            : 0;
                    final int communityPoints = (currentCommunityPoints != null && !currentCommunityPoints.isJsonNull())
                            ? currentCommunityPoints.getAsInt()
                            : 0;

                    JsonElement getCurrentCAName = embargoProfileData.get("currentHighestCAName");
                    JsonObject currentRank = embargoProfileData.getAsJsonObject("currentRank");

                    final String currentRankDisplay;
                    if (currentRank != null) {
                        JsonElement currentRankName = currentRank.get("name");
                        if (currentRankName != null && !currentRankName.isJsonNull()) {
                            currentRankDisplay = currentRankName.getAsString();
                        } else {
                            currentRankDisplay = "N/A";
                        }
                    } else {
                        currentRankDisplay = "N/A";
                    }

                    final String displayCAName;
                    if (getCurrentCAName != null && !getCurrentCAName.isJsonNull()) {
                        displayCAName = getCurrentCAName.getAsString().replace(" Combat Achievement", "");
                    } else {
                        displayCAName = "N/A";
                    }

                    JsonArray missingGearReqs = embargoProfileData.getAsJsonArray("missingGearRequirements");
                    JsonArray missingUntradableItemIdReqs = embargoProfileData
                            .getAsJsonArray("missingUntradableItemIds");

                    // Update simple labels on EDT (no game thread needed for Swing)
                    SwingUtilities.invokeLater(() -> {
                        embargoScoreLabel.setText(htmlLabel("Embargo Score:", " " + (accountPoints + communityPoints)));
                        accountScoreLabel.setText(htmlLabel("Account Score:", " " + accountPoints));
                        communityScoreLabel.setText(htmlLabel("Community Score:", " " + communityPoints));
                        currentRankLabel.setText(htmlLabel("Current Rank:", " " + currentRankDisplay));
                        currentCALabel.setText(htmlLabel("Current CA Tier:", " " + displayCAName));
                    });

                    ArrayList<String> alreadyProcessed = new ArrayList<>();

                    // Build out the missing requirements panel
                    if (missingGearReqs.size() > 0 || missingUntradableItemIdReqs.size() > 0) {
                        // Already on background thread, do item ID lookups here
                        List<Object[]> dynamicItemsData = new ArrayList<>();
                        List<Object[]> regularItemsData = new ArrayList<>();

                        for (JsonElement mi : missingGearReqs) {
                            String itemName = mi.getAsString();
                            alreadyProcessed.add(itemName);
                            log.debug("Processing {} in missingGearReqs", itemName);

                            if (itemName.contains("|")) {
                                // DynamicMissingItem: pre-resolve all item IDs
                                String[] dynamicNames = itemName.split("\\|");
                                int[] itemIds = new int[dynamicNames.length];
                                for (int i = 0; i < dynamicNames.length; i++) {
                                    itemIds[i] = missingRequirementsComponent
                                            .findItemIdByName(dynamicNames[i].trim());
                                }
                                dynamicItemsData.add(new Object[] { dynamicNames, itemIds });
                            } else {
                                // Regular item: pre-resolve item ID
                                int itemId = missingRequirementsComponent.findItemIdByName(itemName);
                                regularItemsData.add(new Object[] { itemName, itemId });
                            }
                        }

                        List<Integer> untradableIds = new ArrayList<>();
                        for (JsonElement mu : missingUntradableItemIdReqs) {
                            if (alreadyProcessed.contains(mu.getAsString())) {
                                log.debug("{} already added, skipping missingUntradableItemIdReqs",
                                        mu.getAsString());
                                continue;
                            }
                            untradableIds.add(mu.getAsInt());
                        }

                        // Use clientThread for item additions since they may load images via
                        // ItemManager
                        clientThread.invokeLater(() -> {
                            // Begin batching to prevent multiple panel rebuilds
                            missingRequirementsComponent.beginBatchUpdate();

                            try {
                                // Add all dynamic items
                                for (Object[] data : dynamicItemsData) {
                                    String[] names = (String[]) data[0];
                                    int[] ids = (int[]) data[1];
                                    missingRequirementsComponent.addDynamicMissingItem(names, ids, 3000);
                                }

                                // Add all regular items
                                for (Object[] data : regularItemsData) {
                                    String name = (String) data[0];
                                    int id = (int) data[1];
                                    missingRequirementsComponent.addMissingItem(name, id);
                                }

                                // Add untradable items
                                for (int itemId : untradableIds) {
                                    missingRequirementsComponent.addMissingItem("", itemId);
                                }
                            } finally {
                                // End batching - this triggers a single panel rebuild
                                missingRequirementsComponent.endBatchUpdate();
                            }

                            // Update the container panel on EDT
                            SwingUtilities.invokeLater(() -> {
                                missingRequirementsPanel.removeAll();
                                missingRequirementsPanel.add(missingRequirementsComponent);
                                missingRequirementsPanel.revalidate();
                                missingRequirementsPanel.repaint();

                                // Update item count
                                int totalItems = dynamicItemsData.size() + regularItemsData.size()
                                        + untradableIds.size();
                                updateMissingItemCount(totalItems);
                            });
                        });
                    } else {
                        SwingUtilities.invokeLater(() -> {
                            missingRequiredItemsLabel.setText(htmlLabel("Missing Requirements: ", "None"));
                            updateMissingItemCount(0);
                        });
                    }
                }, executorService).exceptionally(ex -> {
                    log.error("Error fetching profile data", ex);
                    return null;
                });
            }
        }
    }

    public void logOut() {
        this.isLoggedIn = false;

        // Stop periodic refresh
        stopPeriodicEventsRefresh();

        // Clear alerted IDs so users get re-alerted on next login
        clearAlertedIds();

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
        missingRequirementsComponent.clearItems();
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
        currentCALabel.setText(htmlLabel("Current CA Tier:", " N/A"));

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
        stopPeriodicEventsRefresh();
        eventBus.unregister(this);

        // Remove bingo state change listener to prevent memory leak
        if (bingoStateChangeListener != null) {
            bingoManager.removeStateChangeListener(bingoStateChangeListener);
            bingoStateChangeListener = null;
        }

        missingRequirementsComponent.shutdown();
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
