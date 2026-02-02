
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Data
@Builder
public class BingoTile {
    private final int id;
    private final int bingoBoardId;
    private final int position;
    private final String title;
    private final String description;
    private final String imageUrl;
    private final String wikiKey;
    @Builder.Default
    private final int points = 1;
    @Builder.Default
    private final BingoTileType tileType = BingoTileType.SINGLE;
    @Builder.Default
    private final int requiredCount = 1;
    @Builder.Default
    private final List<BingoItemRequirement> itemRequirements = Collections.emptyList();
    @Builder.Default
    private final List<BingoItemGroup> itemGroups = Collections.emptyList();

    public Set<Integer> getValidItemIds() {
        Set<Integer> ids = itemRequirements.stream()
                .map(BingoItemRequirement::getItemId)
                .collect(Collectors.toSet());

        for (BingoItemGroup group : itemGroups) {
            ids.addAll(group.getItemIds());
        }

        return ids;
    }

    public boolean acceptsItem(int itemId) {
        if (itemRequirements.stream().anyMatch(req -> req.getItemId() == itemId)) {
            return true;
        }

        for (BingoItemGroup group : itemGroups) {
            if (group.containsItem(itemId)) {
                return true;
            }
        }

        return false;
    }

    public boolean isAutoTracked() {
        return tileType != BingoTileType.XP && tileType != BingoTileType.KC;
    }

    public int getRow(int boardSize) {
        return position / boardSize;
    }

    public int getColumn(int boardSize) {
        return position % boardSize;
    }
}
