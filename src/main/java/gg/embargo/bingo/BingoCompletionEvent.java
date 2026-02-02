
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class BingoCompletionEvent {
    private final int id;
    private final int bingoBoardId;
    private final int tileId;
    private final String tileTitle;
    private final int teamId;
    private final String teamName;
    private final String completedByRsn;
    private final Instant completedAt;
    private final int pointsAwarded;
    private final String completionType;
    private final String screenshotUrl;
    private boolean announced;
}
