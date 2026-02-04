package gg.embargo.bingo.dto;

import java.util.List;

/**
 * DTO for a bingo tile from the API.
 */
public class BingoTileDto {
    public int id;
    public int bingoBoardId;
    public int position;
    public String title;
    public String description;
    public String imageUrl;
    public String wikiKey;
    public int points = 1;
    public String tileType;
    public int requiredCount = 1;
    public List<BingoItemRequirementDto> itemRequirements;
    public List<BingoItemGroupDto> itemGroups;
}
