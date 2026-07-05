package gg.embargo.collections;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ClanData {
    private String clanName;
    private int memberCount;
    // Local player's rank id and title within the clan, -1/null when unknown
    private int rank;
    private String title;
    // Local player's clan join date as epoch milliseconds, 0 when unknown
    private long joinedAt;
}
