
package gg.embargo.bingo;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents the complete state of an active bingo event from the user's
 * perspective.
 * <p>
 * This is the main data structure that holds all bingo information needed by
 * the plugin,
 * including the board definition, tiles, the user's team, and progress.
 */
@Data
@Builder
public class BingoState {
    /**
     * Unique identifier for the bingo board
     */
    private final int id;

    /**
     * Display name of the bingo (e.g., "Bingo #2")
     */
    private final String name;

    /**
     * Extended description of the bingo event
     */
    private final String description;

    /**
     * Size of the board (e.g., 5 for a 5x5 grid)
     */
    @Builder.Default
    private final int size = 5;

    /**
     * When the bingo starts
     */
    private final Instant startDate;

    /**
     * When the bingo ends
     */
    private final Instant endDate;

    /**
     * Current status of the bingo
     */
    private final String status;

    /**
     * Secret codeword displayed to enrolled participants
     */
    private final String codeword;

    /**
     * All tiles on the board, keyed by tile ID for quick lookup
     */
    @Builder.Default
    private final Map<Integer, BingoTile> tiles = Collections.emptyMap();

    /**
     * The user's team (null if not enrolled)
     */
    private final BingoTeam userTeam;

    /**
     * Team's progress on each tile, keyed by tile ID
     */
    @Builder.Default
    private final Map<Integer, BingoTeamTileProgress> teamProgress = Collections.emptyMap();

    /**
     * All item IDs that can trigger a tile completion, mapped to their tile IDs.
     * This is built from all tiles' item requirements for efficient lookup during
     * drops.
     */
    @Builder.Default
    private final Map<Integer, Set<Integer>> itemIdToTileIds = Collections.emptyMap();

    /**
     * Checks if the user is enrolled in this bingo.
     *
     * @return true if the user has a team assignment
     */
    public boolean isEnrolled() {
        return userTeam != null;
    }

    /**
     * Checks if the bingo is currently active.
     * Trusts the server's status field since the server validates the dates.
     *
     * @return true if the status is "active"
     */
    public boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }

    /**
     * Checks if the bingo has ended.
     *
     * @return true if the current time is past the end date
     */
    public boolean hasEnded() {
        return endDate != null && Instant.now().isAfter(endDate);
    }

    /**
     * Checks if the bingo has started.
     *
     * @return true if the current time is past the start date
     */
    public boolean hasStarted() {
        return startDate == null || Instant.now().isAfter(startDate);
    }

    /**
     * Gets the time remaining until the bingo ends.
     *
     * @return the duration remaining, or Duration.ZERO if already ended
     */
    public Duration getTimeRemaining() {
        if (endDate == null) {
            return Duration.ofDays(365); // No end date means effectively infinite
        }
        Instant now = Instant.now();
        if (now.isAfter(endDate)) {
            return Duration.ZERO;
        }
        return Duration.between(now, endDate);
    }

    /**
     * Gets a tile by its ID.
     *
     * @param tileId the tile ID
     * @return the tile, or null if not found
     */
    public BingoTile getTile(int tileId) {
        return tiles.get(tileId);
    }

    /**
     * Gets the team's progress on a specific tile.
     *
     * @param tileId the tile ID
     * @return the progress, or null if no progress recorded
     */
    public BingoTeamTileProgress getProgress(int tileId) {
        return teamProgress.get(tileId);
    }

    /**
     * Gets all tile IDs that accept a given item ID.
     *
     * @param itemId the item ID to check
     * @return a set of tile IDs that accept this item, or empty set if none
     */
    public Set<Integer> getTileIdsForItem(int itemId) {
        return itemIdToTileIds.getOrDefault(itemId, Collections.emptySet());
    }

    /**
     * Checks if any tile on the board accepts the given item ID.
     *
     * @param itemId the item ID to check
     * @return true if any tile accepts this item
     */
    public boolean hasItemRequirement(int itemId) {
        return itemIdToTileIds.containsKey(itemId);
    }

    /**
     * Gets tiles sorted by position for display.
     *
     * @return list of tiles in position order
     */
    public List<BingoTile> getTilesByPosition() {
        return tiles.values().stream()
                .sorted(Comparator.comparingInt(BingoTile::getPosition))
                .collect(Collectors.toList());
    }

    /**
     * Gets the number of completed tiles for the user's team.
     *
     * @return count of completed tiles
     */
    public int getCompletedTileCount() {
        return (int) teamProgress.values().stream()
                .filter(BingoTeamTileProgress::isCompleted)
                .count();
    }

    /**
     * Gets tiles that are not yet completed.
     *
     * @return list of incomplete tiles
     */
    public List<BingoTile> getIncompleteTiles() {
        Set<Integer> completedTileIds = teamProgress.entrySet().stream()
                .filter(e -> e.getValue().isCompleted())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        return tiles.values().stream()
                .filter(tile -> !completedTileIds.contains(tile.getId()))
                .collect(Collectors.toList());
    }

    /**
     * Formats the time remaining as a human-readable string.
     *
     * @return formatted time string (e.g., "3d 5h" or "2h 30m")
     */
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

    /**
     * Builds the item-to-tile lookup map from the tiles.
     *
     * @param tiles the tiles map
     * @return the lookup map
     */
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
