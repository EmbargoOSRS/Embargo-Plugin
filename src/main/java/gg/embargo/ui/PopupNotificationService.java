package gg.embargo.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.WidgetNode;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Shows native OSRS notification popups - the same widget the game uses for
 * collection log unlocks (interface 660, clientscript 3343).
 * <p>
 * Popups are queued and drained one per game tick from the plugin's GameTick
 * subscriber so they never stack on top of each other.
 */
@Slf4j
@Singleton
public class PopupNotificationService {

    private static final int NOTIFICATION_DISPLAY_INIT_SCRIPT = 3343;
    private static final int NOTIFICATION_INTERFACE_ID = 660;

    private static final int RESIZABLE_CLASSIC_LAYOUT = WidgetUtil.packComponentId(161, 13);
    private static final int RESIZABLE_MODERN_LAYOUT = WidgetUtil.packComponentId(164, 13);
    private static final int FIXED_CLASSIC_LAYOUT = WidgetUtil.packComponentId(548, 42);

    private static final Color EMBARGO_GOLD = new Color(0xFF, 0x98, 0x1F);

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    private final Queue<PopupData> popupQueue = new ConcurrentLinkedQueue<>();

    public void showPopup(String title, String message) {
        popupQueue.offer(new PopupData(title, message, EMBARGO_GOLD));
    }

    public void showPopup(String title, String message, Color color) {
        popupQueue.offer(new PopupData(title, message, color != null ? color : EMBARGO_GOLD));
    }

    /**
     * Drains at most one queued popup. Must be called from the client thread
     * (the plugin's GameTick subscriber).
     */
    public void processQueue() {
        // Wait for any currently visible popup to close first
        if (client.getWidget(NOTIFICATION_INTERFACE_ID, 1) != null) {
            return;
        }

        PopupData data = popupQueue.poll();
        if (data == null) {
            return;
        }

        try {
            WidgetNode widgetNode = client.openInterface(getComponentId(), NOTIFICATION_INTERFACE_ID,
                    WidgetModalMode.MODAL_CLICKTHROUGH);
            client.runScript(NOTIFICATION_DISPLAY_INIT_SCRIPT, data.title, data.message, toRgbInt(data.color));

            // Close the interface once the popup animation finishes so the
            // next queued popup can display
            Widget widget = client.getWidget(NOTIFICATION_INTERFACE_ID, 1);
            clientThread.invokeLater(() -> {
                if (widget != null && widget.getWidth() > 0) {
                    return false;
                }
                client.closeInterface(widgetNode, true);
                return true;
            });
        } catch (Exception e) {
            log.debug("Failed to show popup notification", e);
        }
    }

    public void clear() {
        popupQueue.clear();
    }

    private int getComponentId() {
        return client.isResized()
                ? (client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1
                        ? RESIZABLE_MODERN_LAYOUT
                        : RESIZABLE_CLASSIC_LAYOUT)
                : FIXED_CLASSIC_LAYOUT;
    }

    private static int toRgbInt(Color color) {
        return color.getRed() << 16 | color.getGreen() << 8 | color.getBlue();
    }

    private static class PopupData {
        final String title;
        final String message;
        final Color color;

        PopupData(String title, String message, Color color) {
            this.title = title;
            this.message = message;
            this.color = color;
        }
    }
}
