
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

/**
 * Represents an item requirement for a bingo tile.
 * <p>
 * A tile can have multiple item requirements. For "any_of" tiles, obtaining any
 * one
 * of the requirements completes the tile. For "quantity" tiles, all items
 * matching
 * any requirement count toward the total.
 */
@Data
@Builder
public class BingoItemRequirement {
    /**
     * The unique identifier of this requirement
     */
    private final int id;

    /**
     * The item group this requirement belongs to (for grouped tiles).
     * Null or 0 if not part of a group.
     */
    @Builder.Default
    private final Integer itemGroupId = null;

    /**
     * The OSRS item ID that satisfies this requirement
     */
    private final int itemId;

    /**
     * Human-readable name of the item
     */
    private final String itemName;

    /**
     * The quantity of this specific item required (usually 1)
     */
    @Builder.Default
    private final int requiredQuantity = 1;

    /**
     * Whether this item is an alternative (OR condition) with other requirements.
     * For "any_of" tiles, all requirements are alternatives.
     * For "quantity" tiles, multiple items may count toward the same total.
     */
    @Builder.Default
    private final boolean isAlternative = false;

    /**
     * The source/boss where this item typically drops from (optional, for display
     * purposes)
     */
    private final String source;
}
