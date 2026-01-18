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
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
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

    @Inject
    private EmbargoPanel() {
    }

    private String htmlLabel(String key, String value) {
        return "<html><body style = 'color:#a5a5a5'>" + key + "<span style = 'color:white'>" + value
                + "</span></body></html>";
    }

    void setupVersionPanel() {
        // Set up Embargo Clan Version at top of Version panel
        JLabel version = new JLabel(htmlLabel("Embargo Clan Version: ", "1.4.8"));
        version.setFont(smallFont);

        // Set version's font
        JLabel revision = new JLabel();
        revision.setFont(smallFont);

        // Set up versionPanel
        versionPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        versionPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        versionPanel.setLayout(new GridLayout(0, 1));

        // Set up custom embargo labels
        isRegisteredWithClanLabel.setFont(smallFont);
        isRegisteredWithClanLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        embargoScoreLabel.setFont(smallFont);
        embargoScoreLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        accountScoreLabel.setFont(smallFont);
        accountScoreLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        communityScoreLabel.setFont(smallFont);
        communityScoreLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        currentCALabel.setFont(smallFont);
        currentCALabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        loggedLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        loggedLabel.setFont(smallFont);

        currentRankLabel.setFont(smallFont);
        currentRankLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        emailLabel.setForeground(Color.WHITE);
        emailLabel.setFont(smallFont);

        versionPanel.add(version);
        versionPanel.add(Box.createGlue());
        versionPanel.add(loggedLabel);
        versionPanel.add(emailLabel);
        versionPanel.add(isRegisteredWithClanLabel);
        versionPanel.add(embargoScoreLabel);
        versionPanel.add(accountScoreLabel);
        versionPanel.add(communityScoreLabel);
        versionPanel.add(currentCALabel);
    }

    JPanel setUpQuickLinks() {
        JPanel actionsContainer = new JPanel();
        actionsContainer.setBorder(new EmptyBorder(10, 0, 0, 0));
        actionsContainer.setLayout(new GridLayout(0, 1, 0, 10));

        actionsContainer.add(buildLinkPanel(DISCORD_ICON, "Join us on our", "Discord", "https://discord.gg/YDGGyP3VEq"));
        actionsContainer.add(buildLinkPanel(WEBSITE_ICON, "Go to our", "clan website", "https://embargo.gg/"));
        actionsContainer.add(buildLinkPanel(GITHUB_ICON, "Report a bug or", "inspect the plugin code",
                "https://github.com/Sharpienero/Embargo-Plugin"));

        return actionsContainer;
    }

    void setupMissingItemsPanel() {
        // Clear any existing content
        missingRequirementsContainer.removeAll();
        missingRequirementsPanel.removeAll();

        // Set up container styling
        missingRequirementsContainer.setBorder(new EmptyBorder(7, 7, 7, 7));
        missingRequirementsContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        missingRequirementsContainer.setFont(FontManager.getRunescapeSmallFont());
        missingRequirementsContainer.setForeground(Color.WHITE);

        // Set up panel styling
        missingRequirementsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        missingRequirementsPanel.setBorder(new EmptyBorder(10, 0, 10, 0));
        missingRequirementsPanel.setLayout(new GridLayout(1, 1));

        // Always add the default message initially
        missingRequiredItemsLabel
                .setText(htmlLabel("Sign in to see what requirements", " you are missing for rank up"));
        missingRequiredItemsLabel.setFont(smallFont);
        missingRequiredItemsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        missingRequirementsPanel.add(missingRequiredItemsLabel);

        // Add panel to container
        missingRequirementsContainer.add(missingRequirementsPanel, BorderLayout.CENTER);

        // Add container to main panel
        this.add(missingRequirementsContainer, BorderLayout.NORTH);
        this.revalidate();
    }

    void addSidePanel() {
        // Add the panels to the side plugin
        this.add(versionPanel, BorderLayout.NORTH);
        setupMissingItemsPanel();
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
                this.isLoggedIn = true;
                var username = client.getLocalPlayer().getName();

                loggedLabel.setText(htmlLabel("Signed in as ", " " + username));

                dataManager.isUserRegisteredAsync(username, isRegistered -> {
                    if (!isRegistered) {
                        emailLabel.setText("Account not registered with Embargo");
                        return;
                    }
                });

                // remove "Sign in to send..."
                versionPanel.remove(emailLabel);

                // re-register labels with panel
                versionPanel.add(isRegisteredWithClanLabel);
                versionPanel.add(embargoScoreLabel);
                versionPanel.add(accountScoreLabel);
                versionPanel.add(communityScoreLabel);
                versionPanel.add(currentRankLabel);
                versionPanel.add(currentCALabel);

                isRegisteredWithClanLabel.setText(htmlLabel("Account registered:", " Yes"));

                // get gear asynchronously
                dataManager.getProfileAsync(username, false).thenAccept(embargoProfileData -> {
                    // This code runs when the profile data is received
                    // We need to run UI updates on the client thread
                    clientThread.invokeLater(() -> {
                        JsonElement currentAccountPoints = embargoProfileData.get("accountPoints");
                        JsonElement currentCommunityPoints = embargoProfileData
                                .getAsJsonPrimitive("communityPoints");

                        embargoScoreLabel.setText((htmlLabel("Embargo Score:", " " +
                                (Integer.parseInt(String.valueOf(currentAccountPoints)) +
                                        Integer.parseInt(String.valueOf(currentCommunityPoints))))));

                        JsonElement getCurrentCAName = embargoProfileData.get("currentHighestCAName");
                        accountScoreLabel.setText(htmlLabel("Account Score: ",
                                String.valueOf(Integer.parseInt(String.valueOf(currentAccountPoints)))));

                        communityScoreLabel.setText(htmlLabel("Community Score: ",
                                String.valueOf(Integer.parseInt(String.valueOf(currentCommunityPoints)))));

                        JsonArray missingGearReqs = embargoProfileData.getAsJsonArray("missingGearRequirements");
                        JsonArray missingUntradableItemIdReqs = embargoProfileData
                                .getAsJsonArray("missingUntradableItemIds");

                        JsonObject currentRank = embargoProfileData.getAsJsonObject("currentRank");
                        JsonElement currentRankName = currentRank.get("name");

                        var currentRankDisplay = String.valueOf(currentRankName).replace("\"", "");
                        currentRankLabel.setText(htmlLabel("Current Rank:", " " + currentRankDisplay));

                        var displayCAName = String.valueOf(getCurrentCAName).replace("\"", "");
                        displayCAName = displayCAName.replace(" Combat Achievement", "");

                        currentCALabel.setText(htmlLabel("Current CA Tier:", " " + displayCAName));

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
                                });
                            });
                        } else {
                            missingRequiredItemsLabel.setText(htmlLabel("Missing Requirements: ", "None"));
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
        // log.debug("inside of logOut()");
        this.isLoggedIn = false;

        // Update labels
        emailLabel.setContentType("text/html");
        emailLabel.setText("Sign in to send data to Embargo.");
        loggedLabel.setText("Not signed in");

        // Reset missing gear requirements
        missingRequiredItemsLabel
                .setText(htmlLabel("Sign in to see what requirements", " you are missing for rank up"));
        missingRequiredItemsLabel.setFont(smallFont);
        missingRequiredItemsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        missingRequirementsPanelX.clearItems();

        // Rebuild missing requirements panel
        missingRequirementsContainer.removeAll();
        missingRequirementsPanel.removeAll();
        missingRequirementsPanel.add(missingRequiredItemsLabel);
        missingRequirementsContainer.add(missingRequirementsPanel, BorderLayout.CENTER);

        // Set to NA
        isRegisteredWithClanLabel.setText(htmlLabel("Account registered:", " No"));
        embargoScoreLabel.setText(htmlLabel("Embargo Score:", " N/A"));
        currentRankLabel.setText(htmlLabel("Current Rank:", " N/A"));
        accountScoreLabel.setText(htmlLabel("Account Score:", " N/A"));
        communityScoreLabel.setText(htmlLabel("Community Score:", " N/A"));
        currentCALabel.setText(htmlLabel("Current TA Tier:", " N/A"));

        // Rebuild version panel
        versionPanel.remove(isRegisteredWithClanLabel);
        versionPanel.remove(embargoScoreLabel);
        versionPanel.remove(accountScoreLabel);
        versionPanel.remove(communityScoreLabel);
        versionPanel.remove(currentRankLabel);
        versionPanel.remove(currentCALabel);

        // Make sure email label is added
        if (!containsComponent(versionPanel, emailLabel)) {
            versionPanel.add(emailLabel);
        }

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

    // Helper method to check if a container contains a component
    private boolean containsComponent(Container container, Component component) {
        Component[] components = container.getComponents();
        for (Component c : components) {
            if (c.equals(component)) {
                return true;
            }
        }
        return false;
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
