
package gg.embargo.bingo;

import gg.embargo.EmbargoConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.List;
import java.util.Map;

/**
 * Overlay that displays the secret bingo codeword on screen.
 * <p>
 * This overlay is only visible when:
 * <ul>
 * <li>There is an active bingo</li>
 * <li>The user is enrolled in the bingo</li>
 * <li>The bingo has a codeword set</li>
 * <li>The user has not disabled the codeword display in config</li>
 * </ul>
 * <p>
 * The overlay renders in the top-right corner by default but can be
 * moved by the user like any other RuneLite overlay.
 */
@Slf4j
@Singleton
public class BingoCodewordOverlay extends Overlay {
    private static final Color BACKGROUND_COLOR = new Color(45, 45, 45, 220);
    private static final Color BORDER_COLOR = new Color(255, 144, 0); // Embargo orange
    private static final Color TITLE_COLOR = new Color(255, 144, 0);
    private static final Color CODEWORD_COLOR = Color.WHITE;

    private final Client client;
    private final BingoManager bingoManager;
    private final EmbargoConfig config;
    private final PanelComponent panelComponent = new PanelComponent();

    @Inject
    public BingoCodewordOverlay(Client client, BingoManager bingoManager, EmbargoConfig config) {
        this.client = client;
        this.bingoManager = bingoManager;
        this.config = config;

        setPosition(OverlayPosition.TOP_RIGHT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.LOW);

        // Make the overlay movable and resizable
        setMovable(true);
        setResizable(false);
        setSnappable(true);

        panelComponent.setBackgroundColor(BACKGROUND_COLOR);
        panelComponent.setBorder(new Rectangle(2, 2, 2, 2));
        panelComponent.setPreferredSize(new Dimension(ComponentConstants.STANDARD_WIDTH, 0));
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        // Check if we should display the overlay
        if (!shouldRender()) {
            return null;
        }

        List<BingoState> activeStates = bingoManager.getActiveEnrolledStates();
        if (activeStates.isEmpty()) {
            return null;
        }

        panelComponent.getChildren().clear();

        // Add title
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Bingo Codes")
                .color(TITLE_COLOR)
                .build());

        // Add codeword and time remaining for each active bingo
        for (BingoState state : activeStates) {
            String codeword = state.getCodeword();
            if (codeword == null || codeword.isEmpty()) {
                continue;
            }

            // Show bingo name if multiple bingos
            if (activeStates.size() > 1) {
                panelComponent.getChildren().add(LineComponent.builder()
                        .left(state.getName() + ":")
                        .leftColor(Color.LIGHT_GRAY)
                        .right(codeword)
                        .rightColor(CODEWORD_COLOR)
                        .build());
            } else {
                // Single bingo - just show codeword prominently
                panelComponent.getChildren().add(TitleComponent.builder()
                        .text(codeword)
                        .color(CODEWORD_COLOR)
                        .build());
            }

            // Add time remaining
            if (state.isActive()) {
                String timeRemaining = state.getFormattedTimeRemaining();
                panelComponent.getChildren().add(TitleComponent.builder()
                        .text("Ends: " + timeRemaining)
                        .color(Color.LIGHT_GRAY)
                        .build());
            }
        }

        return panelComponent.render(graphics);
    }

    /**
     * Determines if the overlay should be rendered.
     *
     * @return true if the overlay should be visible
     */
    private boolean shouldRender() {
        // Check config setting
        if (!config.showBingoCodeword()) {
            return false;
        }

        // Check if enrolled in active bingo
        if (!bingoManager.isEnrolledAndActive()) {
            return false;
        }

        // Check if there's at least one codeword
        Map<String, String> codewords = bingoManager.getCodewords();
        return !codewords.isEmpty();
    }
}
