package gg.embargo;

import net.runelite.api.Client;

/**
 * Resolves the local player's account name, with an optional hard-coded override
 * for local testing.
 *
 * <p>Set {@link #DEBUG_USERNAME_OVERRIDE} to a non-empty RSN to make the entire
 * plugin behave as if you were logged in as that account (registration checks,
 * untrackable/varbit submission, profile fetch, bingo, collection log, etc.) no
 * matter who you actually log in with. Leave it empty ("") for normal operation.
 *
 * <p><b>WARNING:</b> this must be left empty in any shipped release - a non-empty
 * value will send/overwrite data under someone else's account.
 */
public final class PlayerIdentity {

    private PlayerIdentity() {
        // utility - not instantiable
    }

    /**
     * Testing override. Example: {@code "Pesky Badger"}. Empty string disables it.
     */
    public static final String DEBUG_USERNAME_OVERRIDE = "";
    /**
     * @return the override RSN if one is set, otherwise the real local player's
     *         name (or {@code null} if not logged in).
     */
    public static String getUsername(Client client) {
        if (DEBUG_USERNAME_OVERRIDE != null && !DEBUG_USERNAME_OVERRIDE.isEmpty()) {
            return DEBUG_USERNAME_OVERRIDE;
        }
        if (client == null || client.getLocalPlayer() == null) {
            return null;
        }
        return client.getLocalPlayer().getName();
    }
}
