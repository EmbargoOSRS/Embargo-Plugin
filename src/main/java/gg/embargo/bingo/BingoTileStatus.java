
package gg.embargo.bingo;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BingoTileStatus {
    @SerializedName("pending")
    PENDING("pending"),

    @SerializedName("partial")
    PARTIAL("partial"),

    @SerializedName("completed")
    COMPLETED("completed");

    private final String value;

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
