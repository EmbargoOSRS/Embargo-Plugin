
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class BingoDropSubmission {
    private final int bingoBoardId;
    private final int tileId;
    private final String playerName;
    private final int itemId;
    private final String itemName;
    @Builder.Default
    private final int quantity = 1;
    private final String source;
    @Builder.Default
    private final Instant timestamp = Instant.now();
    private final String screenshotBase64;
    @Builder.Default
    private final boolean fromCollectionLog = false;
    @Builder.Default
    private final boolean isPet = false;
    private final int world;
}
