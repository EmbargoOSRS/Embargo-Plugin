package gg.embargo.bingo.dto;

import java.util.List;
import java.util.Map;

/**
 * API response wrapper for bingo state endpoint.
 */
public class BingoApiResponse {
    public Boolean active;
    public List<BingoStateDto> bingos;

    // Fields for single bingo response (backward compatibility)
    public Integer id;
    public String name;
    public String description;
    public Integer size;
    public String startDate;
    public String endDate;
    public String status;
    public String codeword;
    public Map<String, BingoTileDto> tiles;
    public BingoTeamDto userTeam;
    public List<BingoTeamTileProgressDto> teamProgress;
}
