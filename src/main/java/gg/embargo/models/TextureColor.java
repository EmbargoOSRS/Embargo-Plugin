package gg.embargo.models;

import net.runelite.api.Client;
import net.runelite.api.TextureProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for extracting average colors from OSRS textures.
 * Caches computed colors to avoid redundant calculations.
 */
public class TextureColor {

    // Cache of texture ID -> average RGB color
    private static final Map<Integer, Integer> textureColorCache = new ConcurrentHashMap<>();

    // Default color to use when texture is unavailable
    private static final int DEFAULT_COLOR = 0x808080; // Gray

    /**
     * Gets the average color for a texture ID.
     *
     * @param client    the game client
     * @param textureId the texture ID to get color for
     * @return packed RGB color (0x00RRGGBB)
     */
    public static int getTextureColor(Client client, int textureId) {
        if (textureId < 0) {
            return DEFAULT_COLOR;
        }

        // Check cache first
        Integer cached = textureColorCache.get(textureId);
        if (cached != null) {
            return cached;
        }

        // Compute and cache the color
        int color = computeTextureColor(client, textureId);
        textureColorCache.put(textureId, color);
        return color;
    }

    /**
     * Computes the average color of a texture by sampling its pixels.
     */
    private static int computeTextureColor(Client client, int textureId) {
        TextureProvider textureProvider = client.getTextureProvider();
        if (textureProvider == null) {
            return DEFAULT_COLOR;
        }

        // Load texture pixels directly
        int[] pixels = textureProvider.load(textureId);
        if (pixels == null || pixels.length == 0) {
            return DEFAULT_COLOR;
        }

        return computeAverageColor(pixels);
    }

    /**
     * Computes the average color from an array of ARGB pixels.
     * Skips transparent pixels (alpha = 0).
     */
    private static int computeAverageColor(int[] pixels) {
        long totalR = 0;
        long totalG = 0;
        long totalB = 0;
        int count = 0;

        for (int pixel : pixels) {
            // Skip fully transparent pixels (pixel value 0 is often used for transparency)
            if (pixel == 0) {
                continue;
            }

            totalR += (pixel >> 16) & 0xFF;
            totalG += (pixel >> 8) & 0xFF;
            totalB += pixel & 0xFF;
            count++;
        }

        if (count == 0) {
            return DEFAULT_COLOR;
        }

        int avgR = (int) (totalR / count);
        int avgG = (int) (totalG / count);
        int avgB = (int) (totalB / count);

        return (avgR << 16) | (avgG << 8) | avgB;
    }

    /**
     * Clears the texture color cache.
     * Call this if textures might have changed.
     */
    public static void clearCache() {
        textureColorCache.clear();
    }
}
