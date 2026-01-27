package gg.embargo.collections;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlayerAppearance {
    private int[] equipmentIds;
    private int[] kitIds;
    private int[] colors;
    private boolean isFemale;
    private int npcTransformId;
}
