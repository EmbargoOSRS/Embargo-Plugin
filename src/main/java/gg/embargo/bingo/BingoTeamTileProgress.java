
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Data
@Builder
public class BingoTeamTileProgress {
    private final int id;
    private final int teamBingoBoardId;
    private final int bingoTileId;
    @Builder.Default
    private final BingoTileStatus status = BingoTileStatus.PENDING;
    @Builder.Default
    private final int currentCount = 0;
    @Builder.Default
    private final List<String> proofUrls = Collections.emptyList();
    private final String notes;
    private final Instant completedAt;
    private final String completedByRsn;

    public boolean isCompleted() {
        return status == BingoTileStatus.COMPLETED;
    }

    public boolean hasProgress() {
        return status != BingoTileStatus.PENDING || currentCount > 0;
    }

    public int getProgressPercentage(int requiredCount) {
        if (requiredCount <= 0) {
            return isCompleted() ? 100 : 0;
        }
        return Math.min(100, (currentCount * 100) / requiredCount);
    }
}
