
package gg.embargo.bingo;

import gg.embargo.EmbargoConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.LineComponent;

import java.awt.*;

/**
 * Compact overlay that displays a single bingo's codeword.
 * Each active bingo with a codeword gets its own overlay instance.
 */
@Slf4j
public class BingoCodewordOverlay extends OverlayPanel {
    private static final Color CODEWORD_COLOR = new Color(255, 144, 0); // Embargo orange

    private final Client client;
    private final BingoManager bingoManager;
    private final EmbargoConfig config;

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

        setPosition(OverlayPosition.TOP_CENTER);
        setPriority(OverlayPriority.LOW);
        setMovable(true);
        setSnappable(true);
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

        // Build compact single-line display
        panelComponent.getChildren().add(LineComponent.builder()
                .left(codeword)
                .leftColor(CODEWORD_COLOR)
                .build());

        // Size the panel to fit the text
        panelComponent.setPreferredSize(new Dimension(
                graphics.getFontMetrics().stringWidth(codeword) + 10, 0));

        return super.render(graphics);
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
