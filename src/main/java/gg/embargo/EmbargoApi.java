package gg.embargo;

/**
 * Shared Embargo API constants. Centralizes the base URL so the domain only ever
 * needs to change in one place instead of being duplicated across managers.
 */
public final class EmbargoApi {

    private EmbargoApi() {
        // constants holder - not instantiable
    }

    /** Base URL for all Embargo API endpoints. Includes the trailing slash. */
    public static final String BASE_URL = "https://embargo.gg/api/";
}
