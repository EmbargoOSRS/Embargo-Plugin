package gg.embargo.manifest;

import lombok.Data;

/**
 * A staff-authored announcement delivered through the manifest and shown
 * in-game as a broadcast chat line while within its display window.
 */
@Data
public class Announcement {
    private int id;
    private String message;
    // Epoch seconds; 0 means no bound
    private long startsAt;
    private long expiresAt;

    public boolean isActive(long nowEpochSeconds) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        if (startsAt > 0 && nowEpochSeconds < startsAt) {
            return false;
        }
        return expiresAt <= 0 || nowEpochSeconds <= expiresAt;
    }
}
