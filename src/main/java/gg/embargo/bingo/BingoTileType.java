
package gg.embargo.bingo;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Defines the different types of bingo tiles and how they are completed.
 */
@Getter
@RequiredArgsConstructor
public enum BingoTileType {
    /**
     * A single specific item must be obtained (e.g., "Get a Dragon Warhammer")
     */
    @SerializedName("single")
    SINGLE("single"),

    /**
     * A quantity of items must be obtained (e.g., "Get 5 Barrows items")
     * Items matching any of the tile's item requirements count toward the total.
     */
    @SerializedName("quantity")
    QUANTITY("quantity"),

    /**
     * Any one of multiple items satisfies the tile (e.g., "Get any Zenyte jewelry")
     */
    @SerializedName("any_of")
    ANY_OF("any_of"),

    /**
     * A pet must be obtained (detected via chat message or collection log)
     */
    @SerializedName("pet")
    PET("pet"),

    /**
     * An XP milestone must be reached (tracked server-side, not by plugin)
     */
    @SerializedName("xp")
    XP("xp"),

    /**
     * A kill count milestone must be reached
     */
    @SerializedName("kc")
    KC("kc");

    private final String value;

    /**
     * Parses a string value to the corresponding BingoTileType.
     *
     * @param value the string representation of the tile type
     * @return the corresponding BingoTileType, or SINGLE as default
     */
    public static BingoTileType fromValue(String value) {
        if (value == null) {
            return SINGLE;
        }
        for (BingoTileType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return SINGLE;
    }
}
