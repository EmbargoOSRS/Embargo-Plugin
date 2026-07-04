package gg.embargo.collections;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DropActivity {
    private String itemName;
    private int itemId;
    private int quantity;
    private long geValue;
    private String source;
    private String sourceType;
    private long timestamp;
    private int world;
}
