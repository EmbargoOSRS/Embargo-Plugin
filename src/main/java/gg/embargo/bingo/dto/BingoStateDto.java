package gg.embargo.bingo.dto;

import java.util.List;
import java.util.Map;

/**
 * DTO for a single bingo board state from the API.
 * Note: tiles can come as either a Map (keyed by tile ID) or a List from the API.
 */
public class BingoStateDto {
    public int id;
    public String name;
    public String description;
    public int size = 5;
    public String startDate;
    public String endDate;
    public String status;
    public String codeword;
    public Map<String, BingoTileDto> tiles;
    public BingoTeamDto userTeam;
    public List<BingoTeamTileProgressDto> teamProgress;
}
