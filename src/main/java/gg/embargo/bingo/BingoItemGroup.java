
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Represents a group of items for a bingo tile.
 * Groups allow complex tile requirements like "4 from Blood Moon AND 4 from Eclipse Moon".
 * <p>
 * Each group has a required count and contains items (via BingoItemRequirement).
 * Items within a group use OR logic (any item in the group satisfies the requirement).
 * Groups within a tile use AND logic (all groups must be satisfied).
 */
@Data
@Builder
public class BingoItemGroup {
    /**
     * Unique identifier for this group
     */
    private final int id;

    /**
     * The bingo tile this group belongs to
     */
    private final int bingoTileId;

    /**
     * Display name for the group (e.g., "Blood Moon", "Eclipse Moon")
     */
    private final String groupName;

    /**
     * Number of items from this group required
     */
    @Builder.Default
    private final int requiredCount = 1;

    /**
     * Display order of the group
     */
    @Builder.Default
    private final int sortOrder = 0;

    /**
     * The items in this group (any of these can count toward the required count)
     */
    @Builder.Default
    private final List<BingoItemRequirement> items = Collections.emptyList();

    /**
     * Gets all item IDs in this group.
     *
     * @return a set of item IDs
     */
    public Set<Integer> getItemIds() {
        return items.stream()
                .map(BingoItemRequirement::getItemId)
                .collect(Collectors.toSet());
    }

    /**
     * Checks if a given item ID is in this group.
     *
     * @param itemId the item ID to check
     * @return true if the item is in this group
     */
    public boolean containsItem(int itemId) {
        return items.stream()
                .anyMatch(req -> req.getItemId() == itemId);
    }
}
