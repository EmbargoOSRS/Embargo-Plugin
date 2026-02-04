package gg.embargo.bingo.dto;

/**
 * DTO for completion events from the API.
 */
public class BingoCompletionDto {
    public int id;
    public int bingoBoardId;
    public int tileId;
    public String tileTitle;
    public int teamId;
    public String teamName;
    public String completedByRsn;
    public String completedAt;
    public int pointsAwarded;
    public String completionType;
    public String screenshotUrl;
}
