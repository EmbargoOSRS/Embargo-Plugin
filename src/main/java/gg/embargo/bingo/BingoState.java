
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Data
@Builder
public class BingoState {
    private final int id;
    private final String name;
    private final String description;
    @Builder.Default
    private final int size = 5;
    private final Instant startDate;
    private final Instant endDate;
    private final String status;
    private final String codeword;
    @Builder.Default
    private final Map<Integer, BingoTile> tiles = Collections.emptyMap();
    private final BingoTeam userTeam;
    @Builder.Default
    private final Map<Integer, BingoTeamTileProgress> teamProgress = Collections.emptyMap();
    @Builder.Default
    private final Map<Integer, Set<Integer>> itemIdToTileIds = Collections.emptyMap();

    public boolean isEnrolled() {
        return userTeam != null;
    }

    public boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }

    public boolean hasEnded() {
        return endDate != null && Instant.now().isAfter(endDate);
    }

    public boolean hasStarted() {
        return startDate == null || Instant.now().isAfter(startDate);
    }

    public Duration getTimeRemaining() {
        if (endDate == null) {
            return Duration.ofDays(365);
        }
        Instant now = Instant.now();
        if (now.isAfter(endDate)) {
            return Duration.ZERO;
        }
        return Duration.between(now, endDate);
    }

    public BingoTile getTile(int tileId) {
        return tiles.get(tileId);
    }

    public BingoTeamTileProgress getProgress(int tileId) {
        return teamProgress.get(tileId);
    }

    public Set<Integer> getTileIdsForItem(int itemId) {
        return itemIdToTileIds.getOrDefault(itemId, Collections.emptySet());
    }

    public boolean hasItemRequirement(int itemId) {
        return itemIdToTileIds.containsKey(itemId);
    }

    public List<BingoTile> getTilesByPosition() {
        return tiles.values().stream()
                .sorted(Comparator.comparingInt(BingoTile::getPosition))
                .collect(Collectors.toList());
    }

    public int getCompletedTileCount() {
        return (int) teamProgress.values().stream()
                .filter(BingoTeamTileProgress::isCompleted)
                .count();
    }

    public List<BingoTile> getIncompleteTiles() {
        Set<Integer> completedTileIds = teamProgress.entrySet().stream()
                .filter(e -> e.getValue().isCompleted())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        return tiles.values().stream()
                .filter(tile -> !completedTileIds.contains(tile.getId()))
                .collect(Collectors.toList());
    }

    public String getFormattedTimeRemaining() {
        Duration remaining = getTimeRemaining();
        long totalMinutes = remaining.toMinutes();

        if (totalMinutes <= 0) {
            return "Ended";
        }

        long days = remaining.toDays();
        long hours = remaining.toHours() % 24;
        long minutes = totalMinutes % 60;

        if (days > 0) {
            return String.format("%dd %dh", days, hours);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%d min", minutes);
        }
    }

    public static Map<Integer, Set<Integer>> buildItemLookup(Map<Integer, BingoTile> tiles) {
        Map<Integer, Set<Integer>> lookup = new HashMap<>();
        if (tiles != null) {
            for (BingoTile tile : tiles.values()) {
                for (int itemId : tile.getValidItemIds()) {
                    lookup.computeIfAbsent(itemId, k -> new HashSet<>()).add(tile.getId());
                }
            }
        }
        return lookup;
    }
}
