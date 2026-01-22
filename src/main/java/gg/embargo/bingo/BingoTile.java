
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Represents a bingo tile (challenge/task) on the bingo board.
 */
@Data
@Builder
public class BingoTile {
    /**
     * Unique identifier for this tile
     */
    private final int id;

    /**
     * The bingo board this tile belongs to
     */
    private final int bingoBoardId;

    /**
     * Position on the board (0-indexed, left-to-right, top-to-bottom)
     */
    private final int position;

    /**
     * Display title of the tile (e.g., "Dragon Warhammer" or "5 Barrows Items")
     */
    private final String title;

    /**
     * Extended description of the tile requirement
     */
    private final String description;

    /**
     * URL to an image representing this tile (optional)
     */
    private final String imageUrl;

    /**
     * Wiki key for linking to OSRS Wiki (optional)
     */
    private final String wikiKey;

    /**
     * Points awarded for completing this tile
     */
    @Builder.Default
    private final int points = 1;

    /**
     * The type of this tile (single, quantity, any_of, pet, xp, kc)
     */
    @Builder.Default
    private final BingoTileType tileType = BingoTileType.SINGLE;

    /**
     * For quantity-based tiles, the total count required
     */
    @Builder.Default
    private final int requiredCount = 1;

    /**
     * The item requirements for this tile (what items can complete it)
     */
    @Builder.Default
    private final List<BingoItemRequirement> itemRequirements = Collections.emptyList();

    /**
     * Gets all item IDs that can contribute to this tile's completion.
     *
     * @return a set of item IDs
     */
    public Set<Integer> getValidItemIds() {
        return itemRequirements.stream()
                .map(BingoItemRequirement::getItemId)
                .collect(Collectors.toSet());
    }

    /**
     * Checks if a given item ID can contribute to this tile.
     *
     * @param itemId the item ID to check
     * @return true if the item can contribute to tile completion
     */
    public boolean acceptsItem(int itemId) {
        return itemRequirements.stream()
                .anyMatch(req -> req.getItemId() == itemId);
    }

    /**
     * Checks if this tile is tracked automatically by the plugin.
     * XP and KC tiles are tracked server-side, not by the plugin.
     *
     * @return true if the tile can be automatically detected
     */
    public boolean isAutoTracked() {
        return tileType != BingoTileType.XP && tileType != BingoTileType.KC;
    }

    /**
     * Gets the row position on the board (0-indexed).
     *
     * @param boardSize the size of the board (e.g., 5 for a 5x5 board)
     * @return the row index
     */
    public int getRow(int boardSize) {
        return position / boardSize;
    }

    /**
     * Gets the column position on the board (0-indexed).
     *
     * @param boardSize the size of the board (e.g., 5 for a 5x5 board)
     * @return the column index
     */
    public int getColumn(int boardSize) {
        return position % boardSize;
    }
}
