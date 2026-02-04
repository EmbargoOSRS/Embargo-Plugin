package gg.embargo.bingo.dto;

import java.util.List;

/**
 * DTO for item groups from the API.
 */
public class BingoItemGroupDto {
    public int id;
    public int bingoTileId;
    public String groupName;
    public int requiredCount = 1;
    public int sortOrder;
    public List<BingoItemRequirementDto> items;
}
