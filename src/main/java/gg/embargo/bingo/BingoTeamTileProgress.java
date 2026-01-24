
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Represents a team's progress on a specific bingo tile.
 */
@Data
@Builder
public class BingoTeamTileProgress {
    /**
     * Unique identifier for this progress entry
     */
    private final int id;

    /**
     * The team's bingo board ID
     */
    private final int teamBingoBoardId;

    /**
     * The tile this progress is for
     */
    private final int bingoTileId;

    /**
     * Current completion status
     */
    @Builder.Default
    private final BingoTileStatus status = BingoTileStatus.PENDING;

    /**
     * Current progress count (for quantity-based tiles)
     */
    @Builder.Default
    private final int currentCount = 0;

    /**
     * URLs to proof screenshots
     */
    @Builder.Default
    private final List<String> proofUrls = Collections.emptyList();

    /**
     * Additional notes about the completion
     */
    private final String notes;

    /**
     * When the tile was completed (null if not completed)
     */
    private final Instant completedAt;

    /**
     * RSN of the player who completed the tile (for display purposes)
     */
    private final String completedByRsn;

    /**
     * Checks if this tile has been fully completed.
     *
     * @return true if the tile is completed
     */
    public boolean isCompleted() {
        return status == BingoTileStatus.COMPLETED;
    }

    /**
     * Checks if this tile has any progress.
     *
     * @return true if progress has been made
     */
    public boolean hasProgress() {
        return status != BingoTileStatus.PENDING || currentCount > 0;
    }

    /**
     * Gets the progress as a percentage (0-100) for quantity-based tiles.
     *
     * @param requiredCount the total count required
     * @return the percentage complete
     */
    public int getProgressPercentage(int requiredCount) {
        if (requiredCount <= 0) {
            return isCompleted() ? 100 : 0;
        }
        return Math.min(100, (currentCount * 100) / requiredCount);
    }
}
