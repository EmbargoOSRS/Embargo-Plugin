
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Represents a bingo drop submission to be sent to the server.
 * <p>
 * This is the payload structure for the drop submission API endpoint.
 */
@Data
@Builder
public class BingoDropSubmission {
    /**
     * The bingo board ID
     */
    private final int bingoBoardId;

    /**
     * The tile ID this drop applies to
     */
    private final int tileId;

    /**
     * The player's RSN
     */
    private final String playerName;

    /**
     * The item ID that was obtained
     */
    private final int itemId;

    /**
     * The item name (for display/logging)
     */
    private final String itemName;

    /**
     * Quantity of items obtained
     */
    @Builder.Default
    private final int quantity = 1;

    /**
     * Source of the drop (NPC name, activity, etc.)
     */
    private final String source;

    /**
     * When the drop occurred
     */
    @Builder.Default
    private final Instant timestamp = Instant.now();

    /**
     * Base64-encoded screenshot (optional)
     */
    private final String screenshotBase64;

    /**
     * Whether this was detected via collection log message
     */
    @Builder.Default
    private final boolean fromCollectionLog = false;

    /**
     * Whether this is a pet drop
     */
    @Builder.Default
    private final boolean isPet = false;

    /**
     * The current world the player is on
     */
    private final int world;
}
