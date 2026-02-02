
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BingoItemRequirement {
    private final int id;
    @Builder.Default
    private final Integer itemGroupId = null;
    private final int itemId;
    private final String itemName;
    @Builder.Default
    private final int requiredQuantity = 1;
    @Builder.Default
    private final boolean isAlternative = false;
    private final String source;
}
