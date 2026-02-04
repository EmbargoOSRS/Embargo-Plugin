package gg.embargo.bingo.dto;

import java.util.List;

/**
 * DTO for a bingo team from the API.
 */
public class BingoTeamDto {
    public int id;
    public int bingoBoardId;
    public String name;
    public String colorHex;
    public int totalPoints;
    public int completedTiles;
    public int partialTiles;
    public List<Object> members; // Can be strings or objects with rsn field
}
