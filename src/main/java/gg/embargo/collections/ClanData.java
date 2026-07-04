package gg.embargo.collections;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ClanData {
    private String clanName;
    private int memberCount;
}
