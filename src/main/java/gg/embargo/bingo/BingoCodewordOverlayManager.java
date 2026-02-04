
package gg.embargo.bingo;

import gg.embargo.EmbargoConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages the lifecycle of BingoCodewordOverlay instances.
 * Creates one overlay per active bingo that has a codeword.
 */
@Slf4j
@Singleton
public class BingoCodewordOverlayManager {

    private final Client client;
    private final ClientThread clientThread;
    private final BingoManager bingoManager;
    private final EmbargoConfig config;
    private final OverlayManager overlayManager;

    /**
     * Map of board ID to overlay instance
     */
    private final Map<Integer, BingoCodewordOverlay> activeOverlays = new HashMap<>();

    @Inject
    public BingoCodewordOverlayManager(Client client, ClientThread clientThread, BingoManager bingoManager,
                                        EmbargoConfig config, OverlayManager overlayManager) {
        this.client = client;
        this.clientThread = clientThread;
        this.bingoManager = bingoManager;
        this.config = config;
        this.overlayManager = overlayManager;
    }

    /**
     * Starts the overlay manager and registers for state changes.
     */
    public void startUp() {
        // Register for bingo state changes
        bingoManager.addStateChangeListener(this::onBingoStateChange);

        // Initial update based on current state
        updateOverlays();
    }

    /**
     * Shuts down the overlay manager and removes all overlays.
     */
    public void shutDown() {
        bingoManager.removeStateChangeListener(this::onBingoStateChange);
        removeAllOverlays();
    }

    /**
     * Called when bingo states change.
     * Uses clientThread to ensure overlay operations happen on the correct thread.
     */
    private void onBingoStateChange(List<BingoState> states) {
        clientThread.invokeLater(this::updateOverlays);
    }

    /**
     * Updates the overlays to match current active bingos with codewords.
     */
    public void updateOverlays() {
        List<BingoState> activeStates = bingoManager.getActiveEnrolledStates();

        // Determine which board IDs should have overlays (active + enrolled + has codeword)
        Set<Integer> desiredBoardIds = new HashSet<>();
        for (BingoState state : activeStates) {
            if (state.getCodeword() != null && !state.getCodeword().isEmpty()) {
                desiredBoardIds.add(state.getId());
            }
        }

        // Remove overlays for bingos that are no longer active/enrolled/have codeword
        Set<Integer> toRemove = new HashSet<>();
        for (Integer boardId : activeOverlays.keySet()) {
            if (!desiredBoardIds.contains(boardId)) {
                toRemove.add(boardId);
            }
        }
        for (Integer boardId : toRemove) {
            removeOverlay(boardId);
        }

        // Add overlays for new bingos
        for (Integer boardId : desiredBoardIds) {
            if (!activeOverlays.containsKey(boardId)) {
                addOverlay(boardId);
            }
        }
    }

    /**
     * Adds an overlay for a specific bingo board.
     */
    private void addOverlay(int boardId) {
        if (activeOverlays.containsKey(boardId)) {
            return;
        }

        BingoCodewordOverlay overlay = new BingoCodewordOverlay(client, bingoManager, config, boardId);
        activeOverlays.put(boardId, overlay);
        overlayManager.add(overlay);

        BingoState state = bingoManager.getStateByBoardId(boardId);
        String name = state != null ? state.getName() : "Board " + boardId;
        log.debug("Added codeword overlay for: {}", name);
    }

    /**
     * Removes an overlay for a specific bingo board.
     */
    private void removeOverlay(int boardId) {
        BingoCodewordOverlay overlay = activeOverlays.remove(boardId);
        if (overlay != null) {
            overlayManager.remove(overlay);
            log.debug("Removed codeword overlay for board: {}", boardId);
        }
    }

    /**
     * Removes all active overlays.
     */
    private void removeAllOverlays() {
        for (BingoCodewordOverlay overlay : activeOverlays.values()) {
            overlayManager.remove(overlay);
        }
        activeOverlays.clear();
    }
}
