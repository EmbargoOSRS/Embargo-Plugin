
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

import java.awt.*;
import java.util.Collections;
import java.util.List;

@Data
@Builder
public class BingoTeam {
    private final int id;
    private final int bingoBoardId;
    private final String name;
    private final String colorHex;
    @Builder.Default
    private final int totalPoints = 0;
    @Builder.Default
    private final int completedTiles = 0;
    @Builder.Default
    private final int partialTiles = 0;
    @Builder.Default
    private final List<String> members = Collections.emptyList();

    public Color getColor() {
        if (colorHex == null || colorHex.isEmpty()) {
            return Color.GRAY;
        }
        try {
            return Color.decode(colorHex);
        } catch (NumberFormatException e) {
            return Color.GRAY;
        }
    }

    public boolean hasMember(String rsn) {
        if (rsn == null || members == null) {
            return false;
        }
        return members.stream()
                .anyMatch(member -> member.equalsIgnoreCase(rsn));
    }
}
