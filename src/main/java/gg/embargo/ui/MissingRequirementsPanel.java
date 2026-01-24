package gg.embargo.ui;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.http.api.item.ItemPrice;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class MissingRequirementsPanel extends PluginPanel {
    private static final String OSRS_WIKI_BASE_URL = "https://oldschool.runescape.wiki/w/";
    private static final int ITEMS_PER_ROW = 5;
    private static final int CELL_SIZE = 32;
    private static final int TOOLTIP_PADDING = 3;
    private static final Color HOVER_COLOR = ColorScheme.DARKER_GRAY_HOVER_COLOR;
    private static final Color NORMAL_COLOR = ColorScheme.DARKER_GRAY_COLOR;

    // Cache for item icons to avoid recreating them - bounded LRU cache to prevent memory bloat
    private static final int MAX_ICON_CACHE_SIZE = 100;
    private static final int MAX_LETTER_ICON_CACHE_SIZE = 50;
    private final Map<Integer, BufferedImage> iconCache = Collections.synchronizedMap(
            new LinkedHashMap<Integer, BufferedImage>(MAX_ICON_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, BufferedImage> eldest) {
                    return size() > MAX_ICON_CACHE_SIZE;
                }
            });
    private final Map<String, BufferedImage> letterIconCache = Collections.synchronizedMap(
            new LinkedHashMap<String, BufferedImage>(MAX_LETTER_ICON_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > MAX_LETTER_ICON_CACHE_SIZE;
                }
            });

    // Cache for item name -> item ID lookups to avoid repeated itemManager.search() calls
    private final Map<String, Integer> itemIdCache = new ConcurrentHashMap<>();

    // Use AtomicBoolean to properly track update state across threads
    private final AtomicBoolean isUpdating = new AtomicBoolean(false);

    // Flag to support batched updates - when true, updatePanel() is deferred
    private volatile boolean batchingUpdates = false;

    // Track scheduled futures for cleanup to prevent memory leaks
    private final List<ScheduledFuture<?>> scheduledFutures = Collections.synchronizedList(new ArrayList<>());

    private final ItemManager itemManager;
    private final ScheduledExecutorService executorService;
    private final JPanel itemsContainer;
    private final List<MissingItem> missingItems = new ArrayList<>();
    private final MouseAdapter itemMouseAdapter = createMouseAdapter();
    private final Object lock = new Object();

    private static final BufferedImage EHB_ICON = ImageUtil.loadImageResource(MissingRequirementsPanel.class,
            "/ehb_icon.png");
    private static final BufferedImage COMMUNITY_POINTS_ICON = ImageUtil.resizeImage(
            ImageUtil.loadImageResource(MissingRequirementsPanel.class, "/community_points_icon.png"), 24, 24);
    private static final BufferedImage ACCOUNT_POINTS_ICON =
            ImageUtil.loadImageResource(MissingRequirementsPanel.class,  "/account_points_icon.png");
    private static final BufferedImage OVERALL_ICON = ImageUtil.loadImageResource(MissingRequirementsPanel.class,
            "/overall_icon.png");

    @Getter
    public enum DynamicItems {
        ACCOUNT_POINTS("account points"),
        EHB("EHB"),
        COMMUNITY_POINTS("community points"),
        TOTAL_LEVEL("total level");

        private final String label;

        DynamicItems(String label) {
            this.label = label;
        }
    }

    @Inject
    public MissingRequirementsPanel(ItemManager itemManager, ScheduledExecutorService executorService) {
        super(false);
        this.itemManager = itemManager;
        this.executorService = executorService;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setBorder(new EmptyBorder(5, 0, 5, 0));

        // Create subtitle panel with instructions
        JPanel subtitlePanel = new JPanel(new BorderLayout());
        subtitlePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        subtitlePanel.setBorder(new EmptyBorder(0, 0, 8, 0));

        JLabel subtitle = new JLabel("Hover for details, click to open wiki");
        subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        subtitle.setFont(new Font("SansSerif", Font.BOLD, 10));
        subtitlePanel.add(subtitle, BorderLayout.WEST);

        add(subtitlePanel, BorderLayout.NORTH);

        // Create items container with increased gap for better spacing
        itemsContainer = new JPanel();
        itemsContainer.setLayout(new GridLayout(0, ITEMS_PER_ROW, 4, 4));
        itemsContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JScrollPane scrollPane = new JScrollPane(itemsContainer);
        scrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Adds a missing item to the panel if it doesn't already exist
     * 
     * @param itemName The name of the item
     * @param itemId   The item ID in RuneScape
     */
    public void addMissingItem(String itemName, int itemId) {
        synchronized (lock) {
            // Clean up the item name
            String cleanedName = itemName.replace("\"", "").replace(" (uncharged)", "");

            // Handle case where itemName is empty but we have an itemId
            if (itemName.isEmpty() && itemId != -1) {
                ItemComposition ic = itemManager.getItemComposition(itemId);
                String itemNameFromId = ic.getName();

                // Check if this item already exists in our list
                if (missingItems.stream().anyMatch(item -> Objects.equals(item.getItemName(), itemNameFromId))) {
                    log.debug("Item {} already exists, skipping", itemNameFromId);
                    return;
                }

                // Add the item with name from ItemComposition
                BufferedImage itemIcon = getItemIcon(itemId, itemNameFromId);
                log.debug("Adding untrackable item: {}", itemNameFromId);
                missingItems.add(new MissingItem(itemNameFromId, itemId, itemIcon));
                updatePanel();
                return;
            }

            // Check if this is a dynamic item that already exists by type, not exact name
            boolean isDynamicItem = false;
            EnumSet<DynamicItems> dynamicItemsSet = EnumSet.of(
                    DynamicItems.ACCOUNT_POINTS,
                    DynamicItems.EHB,
                    DynamicItems.TOTAL_LEVEL,
                    DynamicItems.COMMUNITY_POINTS);

            for (DynamicItems dynamicItem : dynamicItemsSet) {
                if (cleanedName.contains(dynamicItem.getLabel())) {
                    isDynamicItem = true;
                    // Use getSpecialIcon for dynamic items
                    BufferedImage specialIcon = getSpecialIcon(cleanedName);
                    boolean didRefreshItem;
                    if (specialIcon != null) {
                        // Remove any existing dynamic item of this type and add with special icon
                        missingItems.removeIf(item -> item.getItemName().contains(dynamicItem.getLabel()));
                        missingItems.add(new MissingItem(cleanedName, -1, specialIcon));
                        log.debug("Added new dynamic item with special icon: {}", cleanedName);
                        updatePanel();
                        didRefreshItem = true;
                    } else {
                        didRefreshItem = refreshDynamicItems(itemName, dynamicItemsSet);
                    }
                    if (didRefreshItem) {
                        return; // Successfully refreshed the dynamic item
                    }
                    break;
                }
            }

            // If not a dynamic item, check for exact match as before
            if (!isDynamicItem && !cleanedName.isEmpty() && missingItems.stream()
                    .anyMatch(item -> Objects.equals(item.getItemName(), cleanedName))) {
                log.debug("Item {} already exists, returning", cleanedName);
                return;
            }

            // Handle special cases for specific items
            int finalItemId = itemId;
            if (cleanedName.toLowerCase().contains("quiver")) {
                finalItemId = 28947;
            } else if (cleanedName.toLowerCase().contains("infernal cape")) {
                finalItemId = 21295;
            }

            // Process the item and add it to the panel
            BufferedImage itemIcon;
            if (itemName.contains("Combat Achievement")) {
                finalItemId = getHiltIdFromName(itemName);
                itemIcon = getHiltImageFromName(itemName.split(" ")[0]);
            } else {
                itemIcon = getItemIcon(finalItemId, cleanedName);
            }
            missingItems.add(new MissingItem(cleanedName, finalItemId, itemIcon));
            log.debug("Added new item to panel: {}", cleanedName);
            updatePanel();
        }
    }

    public boolean refreshDynamicItems(String itemName, EnumSet<DynamicItems> dynamicItemsEnumSet) {
        synchronized (lock) {
            String cleanedItemName = itemName.replaceAll("^\"|\"$", "");
            boolean itemRemoved = false;

            // First, remove any existing items that match the dynamic type
            Iterator<MissingItem> iterator = missingItems.iterator();
            while (iterator.hasNext()) {
                MissingItem item = iterator.next();

                // Check if this existing item matches any of our dynamic types
                for (DynamicItems dynamicItem : dynamicItemsEnumSet) {
                    if (item.itemName.contains(dynamicItem.getLabel()) &&
                            cleanedItemName.contains(dynamicItem.getLabel())) {
                        // Found a match - remove the old item
                        iterator.remove();
                        itemRemoved = true;
                        log.debug("Removed dynamic item {} to be replaced with {}",
                                item.itemName, cleanedItemName);
                        break;
                    }
                }
            }

            // Now add the new item if we removed an old one
            if (itemRemoved) {
                // Add the new item with updated value
                missingItems.add(new MissingItem(cleanedItemName, -1,
                        getItemIcon(-1, cleanedItemName)));
                log.debug("Added new dynamic item: {}", cleanedItemName);
                updatePanel(); // Make sure to update the panel to reflect changes
                return true;
            } else {
                return false;
            }
        }
    }

    @Getter
    public enum AchievementHilts {
        EASY(25926),
        MEDIUM(25928),
        HARD(25930),
        ELITE(25932),
        MASTER(25934),
        GRANDMASTER(25936);

        private final int itemId;

        AchievementHilts(int itemId) {
            this.itemId = itemId;
        }
    }

    public BufferedImage getHiltImageFromName(String name) {
        String lowercaseName = name.toLowerCase();
        // Strip out extra "'s
        lowercaseName = lowercaseName.replace("\"", "");

        for (AchievementHilts hilt : AchievementHilts.values()) {
            // Shortcut to bypass conflicting name with "Master" being in "Grandmaster"
            if (lowercaseName.contains("grand")) {
                return getItemIcon(AchievementHilts.GRANDMASTER.getItemId(), "Ghommal's_avernic_defender_6");
            }

            if (lowercaseName.contains(hilt.name().toLowerCase())) {
                return getItemIcon(hilt.getItemId(), "Ghommal's_avernic_defender_" + (hilt.ordinal() + 1));
            }
        }
        return getItemIcon(882, "Bronze arrow");
    }

    public int getHiltIdFromName(String name) {
        String lowercaseName = name.toLowerCase();

        // Special case for Grandmaster since conflicting master in name
        if (lowercaseName.contains("grand")) {
            return AchievementHilts.GRANDMASTER.getItemId();
        }

        // Check for other hilt types
        for (AchievementHilts hilt : AchievementHilts.values()) {
            if (lowercaseName.contains(hilt.name().toLowerCase())) {
                return hilt.getItemId();
            }
        }

        return 882;
    }

    /**
     * Clears all missing items from the panel
     */
    public void clearItems() {
        synchronized (lock) {
            cancelAllScheduledFutures();
            if (!missingItems.isEmpty()) {
                missingItems.clear();
                updatePanel();
            }
        }
    }

    /**
     * Cancels all scheduled futures to prevent memory leaks.
     * Must be called before recreating panels or during cleanup.
     */
    private void cancelAllScheduledFutures() {
        synchronized (scheduledFutures) {
            for (ScheduledFuture<?> future : scheduledFutures) {
                future.cancel(true);
            }
            scheduledFutures.clear();
        }
    }

    /**
     * Cleanup method to be called when the plugin is shutting down.
     * Cancels all scheduled futures and clears caches to prevent memory leaks.
     */
    public void shutdown() {
        synchronized (lock) {
            cancelAllScheduledFutures();
            missingItems.clear();
            iconCache.clear();
            letterIconCache.clear();
            itemIdCache.clear();
            itemsContainer.removeAll();
        }
    }

    /**
     * Begins a batch update session. While batching is active, individual
     * addMissingItem/addDynamicMissingItem calls will not trigger panel rebuilds.
     * Call {@link #endBatchUpdate()} when done adding items to trigger a single rebuild.
     */
    public void beginBatchUpdate() {
        synchronized (lock) {
            batchingUpdates = true;
        }
    }

    /**
     * Ends a batch update session and triggers a single panel rebuild.
     * This should be called after all items have been added via addMissingItem/addDynamicMissingItem.
     */
    public void endBatchUpdate() {
        synchronized (lock) {
            batchingUpdates = false;
            updatePanel();
        }
    }

    /**
     * Updates the panel with the current list of missing items.
     * Uses AtomicBoolean to properly track update state across threads.
     */
    private void updatePanel() {
        // Skip updates if we're in batching mode
        if (batchingUpdates) {
            return;
        }

        // Use compareAndSet for thread-safe check-and-set
        if (!isUpdating.compareAndSet(false, true)) {
            return; // Another update is already in progress
        }

        SwingUtilities.invokeLater(() -> {
            try {
                synchronized (lock) {
                    // Cancel existing scheduled futures before rebuilding panels to prevent leaks
                    cancelAllScheduledFutures();
                    itemsContainer.removeAll();
                    for (MissingItem item : missingItems) {
                        JPanel itemPanel = createItemPanel(item);
                        itemsContainer.add(itemPanel);
                    }
                    revalidate();
                    repaint();
                }
            } finally {
                // Reset the flag AFTER the work is done, inside the SwingUtilities.invokeLater
                isUpdating.set(false);
            }
        });
    }

    /**
     * Creates a panel for a single item with icon and hover/click functionality
     */
    private JPanel createItemPanel(MissingItem item) {
        if (item instanceof DynamicMissingItem) {
            DynamicMissingItem dyn = (DynamicMissingItem) item;
            JPanel dynamicPanel = createBaseItemPanel();

            JLabel iconLabel = new JLabel();
            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            iconLabel.setIcon(getIconForItemId(dyn.itemIds[0]));
            iconLabel.setToolTipText(buildTooltipText(new MissingItem(dyn.names[0], dyn.itemIds[0], dyn.icons.get(0))));
            dynamicPanel.add(iconLabel, BorderLayout.CENTER);

            // Track the current index for click events
            final AtomicInteger currentIdx = new AtomicInteger(0);

            ScheduledFuture<?> future = executorService.scheduleAtFixedRate(() -> {
                SwingUtilities.invokeLater(() -> {
                    int idx = currentIdx.updateAndGet(i -> (i + 1) % dyn.names.length);
                    iconLabel.setIcon(new ImageIcon(dyn.icons.get(idx)));
                    String tooltip = buildTooltipText(
                            new MissingItem(dyn.names[idx], dyn.itemIds[idx], dyn.icons.get(idx)));
                    iconLabel.setToolTipText(tooltip);
                    dynamicPanel.setToolTipText(tooltip);
                    dynamicPanel.revalidate();
                    dynamicPanel.repaint();
                });
            }, dyn.intervalMs, dyn.intervalMs, TimeUnit.MILLISECONDS);

            // Track future for cleanup when panel is rebuilt or cleared
            scheduledFutures.add(future);

            MouseAdapter hoverAndClick = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    dynamicPanel.setBackground(HOVER_COLOR);
                    dynamicPanel.setCursor(new Cursor(Cursor.HAND_CURSOR));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    dynamicPanel.setBackground(NORMAL_COLOR);
                    dynamicPanel.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    int idx = currentIdx.get();
                    int itemId = dyn.itemIds[idx];
                    String itemName = dyn.names[idx];
                    if (itemId == -1) {
                        return;
                    }
                    String wikiUrl = OSRS_WIKI_BASE_URL + itemName.replace(" ", "_");
                    LinkBrowser.browse(wikiUrl);
                }
            };

            // Attach to both panel and label
            dynamicPanel.addMouseListener(hoverAndClick);
            iconLabel.addMouseListener(hoverAndClick);

            return dynamicPanel;
        }

        JPanel panel = createBaseItemPanel();

        JLabel iconLabel = new JLabel(new ImageIcon(item.getIcon()));
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(iconLabel, BorderLayout.CENTER);
        panel.setToolTipText(buildTooltipText(item));

        // Add hover effect and click handler to the panel
        panel.addMouseListener(itemMouseAdapter);

        return panel;
    }

    private JPanel createBaseItemPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(NORMAL_COLOR);
        panel.setBorder(new LineBorder(Color.BLACK, 1));
        panel.setPreferredSize(new Dimension(CELL_SIZE, CELL_SIZE));
        panel.setMinimumSize(new Dimension(CELL_SIZE, CELL_SIZE));
        panel.setMaximumSize(new Dimension(CELL_SIZE, CELL_SIZE));
        return panel;
    }

    private MouseAdapter createMouseAdapter() {
        return new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                JPanel panel = (JPanel) e.getSource();
                panel.setBackground(HOVER_COLOR);
                panel.setCursor(new Cursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                JPanel panel = (JPanel) e.getSource();
                panel.setBackground(NORMAL_COLOR);
                panel.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                JPanel panel = (JPanel) e.getSource();
                Component[] components = itemsContainer.getComponents();
                int index = -1;

                for (int i = 0; i < components.length; i++) {
                    if (components[i] == panel) {
                        index = i;
                        break;
                    }
                }

                if (index >= 0 && index < missingItems.size()) {
                    MissingItem item = missingItems.get(index);
                    if (item.getItemId() == -1) {
                        return;
                    }
                    String wikiUrl = OSRS_WIKI_BASE_URL + item.getItemName().replace(" ", "_");
                    LinkBrowser.browse(wikiUrl);
                }
            }
        };
    }

    /**
     * Gets the item icon from the ItemManager or a default icon if not found
     */
    private BufferedImage getItemIcon(int itemId, String itemName) {
        // Check cache first
        if (itemId != -1) {
            BufferedImage cachedIcon = iconCache.get(itemId);
            if (cachedIcon != null) {
                return cachedIcon;
            }

            BufferedImage icon = itemManager.getImage(itemId);
            if (icon == null) {
                // Fallback to letter icon if image not available
                log.debug("No icon found for itemId: {}, using letter fallback", itemId);
                String letter = getLetterForItem(itemName);
                return createLetterIcon(letter);
            }
            BufferedImage resizedIcon = ImageUtil.resizeImage(icon, CELL_SIZE, CELL_SIZE);
            iconCache.put(itemId, resizedIcon);
            return resizedIcon;
        } else {
            // For letter-based icons, create a cache key
            String letter = getLetterForItem(itemName);
            String cacheKey = letter + "_" + CELL_SIZE;

            BufferedImage cachedIcon = letterIconCache.get(cacheKey);
            if (cachedIcon != null) {
                return cachedIcon;
            }

            BufferedImage icon = createLetterIcon(letter);
            letterIconCache.put(cacheKey, icon);
            return icon;
        }
    }

    private String getLetterForItem(String itemName) {
        if (itemName.contains("community")) {
            return "CP";
        } else if (itemName.contains("account")) {
            return "AP";
        } else if (itemName.contains("EHB")) {
            return "EHB";
        } else if (itemName.contains("EHP")) {
            return "EHP";
        }
        return itemName.substring(0, 1).toUpperCase();
    }

    private BufferedImage createLetterIcon(String letter) {
        BufferedImage icon = new BufferedImage(CELL_SIZE, CELL_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = icon.createGraphics();

        // Fill the entire image with the background color
        g2d.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
        g2d.fillRect(0, 0, CELL_SIZE, CELL_SIZE);
        g2d.setColor(Color.WHITE);

        // Calculate appropriate font size based on text length
        int fontSize = 16; // Default font size
        if (letter.length() > 1) {
            // Reduce font size for longer text
            fontSize = Math.max(8, 16 - (letter.length() - 1) * 2);
        }

        g2d.setFont(new Font("SansSerif", Font.BOLD, fontSize));

        // Ensure text fits within the cell
        FontMetrics fm = g2d.getFontMetrics();
        while (fm.stringWidth(letter) > CELL_SIZE - 2 && fontSize > 8) {
            fontSize--;
            g2d.setFont(new Font("SansSerif", Font.BOLD, fontSize));
            fm = g2d.getFontMetrics();
        }

        int x = (CELL_SIZE - fm.stringWidth(letter)) / 2;
        int y = ((CELL_SIZE - fm.getHeight()) / 2) + fm.getAscent();
        g2d.drawString(letter, x, y);
        g2d.dispose();

        return icon;
    }

    /**
     * Builds the tooltip text for an item
     */
    private String buildTooltipText(MissingItem item) {
        StringBuilder sb = new StringBuilder("<html><body style='padding: ");
        sb.append(TOOLTIP_PADDING).append("px;");

        if (item.getItemId() == -1) {
            sb.append(" text-align: center;");
        }

        sb.append("'><div style='font-weight: bold;");

        if (item.getItemId() != -1) {
            sb.append(" margin-bottom: 3px;");
        }

        sb.append("'>").append(item.getItemName()).append("</div>");

        if (item.getItemId() != -1) {
            sb.append("<div style='color: #99FFFF; font-style: italic;'>Click to open wiki</div>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * Attempts to find an item ID by name using the ItemManager.
     * Results are cached to avoid repeated blocking searches.
     */
    public int findItemIdByName(String itemName) {
        String searchName = itemName.replace("\"", "").trim();

        // Check cache first to avoid blocking search call
        Integer cachedId = itemIdCache.get(searchName.toLowerCase());
        if (cachedId != null) {
            return cachedId;
        }

        // Perform the search (blocking call)
        List<ItemPrice> itemPrices = itemManager.search(searchName);
        int itemId = itemPrices.isEmpty() ? -1 : itemPrices.get(0).getId();

        // Cache the result (including -1 for not found)
        itemIdCache.put(searchName.toLowerCase(), itemId);

        return itemId;
    }

    /**
     * Class to represent a missing item
     */
    @Getter
    private static class MissingItem {
        private final String itemName;
        private final int itemId;

        @Setter
        private BufferedImage icon;

        public MissingItem(String itemName, int itemId, BufferedImage icon) {
            this.itemName = itemName;
            this.itemId = itemId;
            this.icon = icon;
        }
    }

    /**
     * Adds a dynamic missing item that rotates its icon and name every intervalMs
     * milliseconds.
     * 
     * @param names      Array of item names to display.
     * @param itemIds    Array of item IDs corresponding to the names.
     * @param intervalMs Interval in milliseconds to rotate the icon/name.
     */
    public void addDynamicMissingItem(String[] names, int[] itemIds, int intervalMs) {
        synchronized (lock) {
            // Clean all names like addMissingItem does
            String[] cleanedNames = Arrays.stream(names)
                    .map(n -> n.replace("\"", "").replace(" (uncharged)", ""))
                    .toArray(String[]::new);

            // Remove any existing DynamicMissingItem with the same cleaned names
            Set<String> newNamesSet = new HashSet<>(Arrays.asList(cleanedNames));
            Iterator<MissingItem> iterator = missingItems.iterator();
            while (iterator.hasNext()) {
                MissingItem item = iterator.next();
                if (item instanceof DynamicMissingItem) {
                    DynamicMissingItem dyn = (DynamicMissingItem) item;
                    Set<String> existingNamesSet = new HashSet<>(Arrays.asList(dyn.names));
                    if (existingNamesSet.equals(newNamesSet)) {
                        iterator.remove();
                    }
                }
            }
            List<BufferedImage> icons = new ArrayList<>();
            for (int i = 0; i < itemIds.length; i++) {
                String name = names[i].trim().toLowerCase();
                BufferedImage special = null;
                // Only use special icon for exact matches
                if (name.equals("ehb") || name.equals("ehp") || name.equals("community points")
                        || name.equals("account points") || name.equals("total level")) {
                    special = getSpecialIcon(name);
                }
                if (special != null) {
                    icons.add(special);
                } else {
                    icons.add(getItemIcon(itemIds[i], names[i]));
                }
            }
            missingItems.add(new DynamicMissingItem(cleanedNames, itemIds, intervalMs, icons));
            updatePanel();
        }
    }

    // Helper method to get an icon for an item ID
    private Icon getIconForItemId(int itemId) {
        if (itemId == -1) {
            // Return a default icon or a placeholder if itemId is invalid
            BufferedImage icon = createLetterIcon("?");
            return new ImageIcon(icon);
        }
        // Use the same logic as getItemIcon to get and cache the icon
        BufferedImage cachedIcon = iconCache.get(itemId);
        if (cachedIcon != null) {

            return new ImageIcon(cachedIcon);
        }
        BufferedImage icon = itemManager.getImage(itemId);
        if (icon == null) {
            log.warn("No icon found for itemId: {}", itemId);
            icon = createLetterIcon("?");
        }
        BufferedImage resizedIcon = ImageUtil.resizeImage(icon, CELL_SIZE, CELL_SIZE);
        iconCache.put(itemId, resizedIcon);
        return new ImageIcon(resizedIcon);
    }

    private static class DynamicMissingItem extends MissingItem {
        private final String[] names;
        private final int[] itemIds;
        private final int intervalMs;
        private final List<BufferedImage> icons;

        public DynamicMissingItem(String[] names, int[] itemIds, int intervalMs, List<BufferedImage> icons) {
            super(names[0], itemIds[0], icons != null && !icons.isEmpty() ? icons.get(0) : null);
            this.names = names;
            this.itemIds = itemIds;
            this.intervalMs = intervalMs;
            this.icons = icons;
        }
    }

    private BufferedImage getSpecialIcon(String name) {
        String n = name.trim().toLowerCase();
        if (n.contains("ehb"))
            return EHB_ICON;
        if (n.contains("community points"))
            return COMMUNITY_POINTS_ICON;
        if (n.contains("account points"))
            return ACCOUNT_POINTS_ICON;
        if (n.contains("total level"))
            return OVERALL_ICON;
        return null;
    }
}
