
package gg.embargo.bingo;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Represents the completion status of a bingo tile for a team.
 */
@Getter
@RequiredArgsConstructor
public enum BingoTileStatus {
    /**
     * Tile has not been started - no progress made
     */
    @SerializedName("pending")
    PENDING("pending"),

    /**
     * Tile has partial progress (for quantity-based tiles)
     */
    @SerializedName("partial")
    PARTIAL("partial"),

    /**
     * Tile has been fully completed
     */
    @SerializedName("completed")
    COMPLETED("completed");

    private final String value;

    /**
     * Parses a string value to the corresponding BingoTileStatus.
     *
     * @param value the string representation of the status
     * @return the corresponding BingoTileStatus, or PENDING as default
     */
    public static BingoTileStatus fromValue(String value) {
        if (value == null) {
            return PENDING;
        }
        for (BingoTileStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        return PENDING;
    }
}
