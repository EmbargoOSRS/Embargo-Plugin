
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Represents a bingo tile completion event received from the server.
 * <p>
 * These events are fetched periodically to announce tile completions
 * (including XP tiles that are tracked server-side).
 */
@Data
@Builder
public class BingoCompletionEvent {
    /**
     * Unique identifier for this completion event
     */
    private final int id;

    /**
     * The bingo board ID
     */
    private final int bingoBoardId;

    /**
     * The tile that was completed
     */
    private final int tileId;

    /**
     * Title of the completed tile
     */
    private final String tileTitle;

    /**
     * The team that completed the tile
     */
    private final int teamId;

    /**
     * Name of the team
     */
    private final String teamName;

    /**
     * RSN of the player who completed it
     */
    private final String completedByRsn;

    /**
     * When the completion occurred
     */
    private final Instant completedAt;

    /**
     * Points awarded for this completion
     */
    private final int pointsAwarded;

    /**
     * The type of completion (drop, xp, kc, pet)
     */
    private final String completionType;

    /**
     * URL to proof screenshot (if available)
     */
    private final String screenshotUrl;

    /**
     * Whether this completion has been announced locally
     */
    private boolean announced;
}
