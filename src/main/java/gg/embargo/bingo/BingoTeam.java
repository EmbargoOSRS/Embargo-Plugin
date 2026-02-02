
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

import java.awt.*;
import java.util.Collections;
import java.util.List;

/**
 * Represents a team participating in a bingo event.
 */
@Data
@Builder
public class BingoTeam {
    /**
     * Unique identifier for this team
     */
    private final int id;

    /**
     * The bingo board this team is participating in
     */
    private final int bingoBoardId;

    /**
     * Display name of the team
     */
    private final String name;

    /**
     * Hex color code for the team (e.g., "#FF0000" for red)
     */
    private final String colorHex;

    /**
     * Total points earned by the team
     */
    @Builder.Default
    private final int totalPoints = 0;

    /**
     * Number of fully completed tiles
     */
    @Builder.Default
    private final int completedTiles = 0;

    /**
     * Number of tiles with partial progress
     */
    @Builder.Default
    private final int partialTiles = 0;

    /**
     * List of team member RSNs
     */
    @Builder.Default
    private final List<String> members = Collections.emptyList();

    /**
     * Gets the team's color as a Java AWT Color object.
     *
     * @return the Color, or a default gray if parsing fails
     */
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

    /**
     * Checks if a given RSN is a member of this team.
     *
     * @param rsn the RuneScape name to check
     * @return true if the RSN is on this team
     */
    public boolean hasMember(String rsn) {
        if (rsn == null || members == null) {
            return false;
        }
        return members.stream()
                .anyMatch(member -> member.equalsIgnoreCase(rsn));
    }
}
