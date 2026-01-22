
package gg.embargo.bingo;

import gg.embargo.EmbargoConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import java.awt.*;

/**
 * Individual overlay that displays a single bingo's codeword.
 * Each active bingo with a codeword gets its own overlay instance.
 */
@Slf4j
public class BingoCodewordOverlay extends Overlay {
    private static final Color BACKGROUND_COLOR = new Color(45, 45, 45, 220);
    private static final Color TITLE_COLOR = new Color(255, 144, 0); // Embargo orange
    private static final Color CODEWORD_COLOR = Color.WHITE;
    private static final Color SEPARATOR_COLOR = new Color(100, 100, 100);

    private final Client client;
    private final BingoManager bingoManager;
    private final EmbargoConfig config;
    private final PanelComponent panelComponent = new PanelComponent();

    @Getter
    private final int boardId;

    /**
     * Creates a new codeword overlay for a specific bingo board.
     *
     * @param client       the game client
     * @param bingoManager the bingo manager
     * @param config       the plugin config
     * @param boardId      the bingo board ID this overlay is for
     */
    public BingoCodewordOverlay(Client client, BingoManager bingoManager, EmbargoConfig config, int boardId) {
        this.client = client;
        this.bingoManager = bingoManager;
        this.config = config;
        this.boardId = boardId;

        setPosition(OverlayPosition.TOP_RIGHT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.LOW);

        // Make the overlay movable and resizable
        setMovable(true);
        setResizable(true);
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

        BingoState state = bingoManager.getStateByBoardId(boardId);
        if (state == null) {
            return null;
        }

        String codeword = state.getCodeword();
        if (codeword == null || codeword.isEmpty()) {
            return null;
        }

        panelComponent.getChildren().clear();

        // Get team name
        String teamName = state.getUserTeam() != null ? state.getUserTeam().getName() : state.getName();

        // Add team name as title
        panelComponent.getChildren().add(TitleComponent.builder()
                .text(teamName)
                .color(TITLE_COLOR)
                .build());

        // Add separator line
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("────────")
                .color(SEPARATOR_COLOR)
                .build());

        // Add codeword prominently
        panelComponent.getChildren().add(TitleComponent.builder()
                .text(codeword)
                .color(CODEWORD_COLOR)
                .build());

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

        // Check if this specific bingo state exists and is valid
        BingoState state = bingoManager.getStateByBoardId(boardId);
        if (state == null || !state.isEnrolled() || !state.isActive()) {
            return false;
        }

        // Check if this bingo has a codeword
        String codeword = state.getCodeword();
        return codeword != null && !codeword.isEmpty();
    }
}
