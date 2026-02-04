package gg.embargo.bingo.dto;

import java.util.List;

/**
 * DTO for team tile progress from the API.
 */
public class BingoTeamTileProgressDto {
    public int id;
    public int teamBingoBoardId;
    public int bingoTileId;
    public String status;
    public int currentCount;
    public List<String> proofUrls;
    public String notes;
    public String completedAt;
    public String completedByRsn;
}
