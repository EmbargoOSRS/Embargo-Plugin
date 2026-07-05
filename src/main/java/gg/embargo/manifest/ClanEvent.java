package gg.embargo.manifest;

import lombok.Data;

/**
 * A scheduled clan event delivered through the manifest and rendered in the
 * side panel with a local-time display, countdown, and optional world hop.
 */
@Data
public class ClanEvent {
    private int id;
    private String title;
    private String description;
    private String host;
    private String location;
    // 0 means no specific world
    private int world;
    // Epoch seconds
    private long startsAt;
    private int durationMinutes;

    public boolean isExpired(long nowEpochSeconds) {
        long end = startsAt + (long) Math.max(durationMinutes, 0) * 60;
        return nowEpochSeconds > end;
    }
}
