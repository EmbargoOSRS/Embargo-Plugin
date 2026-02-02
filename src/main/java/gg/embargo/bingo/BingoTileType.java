
package gg.embargo.bingo;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BingoTileType {
    @SerializedName("single")
    SINGLE("single"),

    @SerializedName("quantity")
    QUANTITY("quantity"),

    @SerializedName("any_of")
    ANY_OF("any_of"),

    @SerializedName("pet")
    PET("pet"),

    @SerializedName("xp")
    XP("xp"),

    @SerializedName("kc")
    KC("kc"),

    @SerializedName("grouped")
    GROUPED("grouped");

    private final String value;

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
