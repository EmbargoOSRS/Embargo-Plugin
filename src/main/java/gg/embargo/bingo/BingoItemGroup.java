
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Data
@Builder
public class BingoItemGroup {
    private final int id;
    private final int bingoTileId;
    private final String groupName;
    @Builder.Default
    private final int requiredCount = 1;
    @Builder.Default
    private final int sortOrder = 0;
    @Builder.Default
    private final List<BingoItemRequirement> items = Collections.emptyList();

    public Set<Integer> getItemIds() {
        return items.stream()
                .map(BingoItemRequirement::getItemId)
                .collect(Collectors.toSet());
    }

    public boolean containsItem(int itemId) {
        return items.stream()
                .anyMatch(req -> req.getItemId() == itemId);
    }
}
