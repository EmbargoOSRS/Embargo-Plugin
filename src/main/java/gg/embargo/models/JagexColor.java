package gg.embargo.models;

/**
 * Utility class for converting Jagex HSL colors to RGB.
 * OSRS uses packed 16-bit HSL values for model vertex colors.
 */
public class JagexColor {

    private static final double DEFAULT_BRIGHTNESS = 0.8;

    /**
     * Converts a Jagex packed HSL value to RGB.
     *
     * @param hsl packed 16-bit HSL value from the game
     * @return packed RGB value (0x00RRGGBB)
     */
    public static int HSLtoRGB(short hsl) {
        return HSLtoRGB(hsl, DEFAULT_BRIGHTNESS);
    }

    /**
     * Converts a Jagex packed HSL value to RGB with custom brightness.
     *
     * OSRS HSL packing:
     * - bits 10-15: Hue (6 bits, 0-63)
     * - bits 7-9: Saturation (3 bits, 0-7)
     * - bits 0-6: Luminance (7 bits, 0-127)
     *
     * @param hsl packed 16-bit HSL value from the game
     * @param brightness brightness multiplier (typically 0.8)
     * @return packed RGB value (0x00RRGGBB)
     */
    public static int HSLtoRGB(short hsl, double brightness) {
        // Handle special case for black/invalid colors
        if (hsl == -1 || hsl == -2) {
            return 0x000000;
        }

        // Unpack HSL components
        double hue = ((hsl >> 10) & 0x3F) / 64.0;        // bits 10-15
        double saturation = ((hsl >> 7) & 0x7) / 8.0;    // bits 7-9
        double luminance = (hsl & 0x7F) / 128.0;         // bits 0-6

        // Standard HSL to RGB conversion
        double chroma = (1 - Math.abs(2 * luminance - 1)) * saturation;
        double x = chroma * (1 - Math.abs((hue * 6) % 2 - 1));
        double m = luminance - chroma / 2;

        double r, g, b;
        int sector = (int) (hue * 6);
        switch (sector % 6) {
            case 0:
                r = chroma;
                g = x;
                b = 0;
                break;
            case 1:
                r = x;
                g = chroma;
                b = 0;
                break;
            case 2:
                r = 0;
                g = chroma;
                b = x;
                break;
            case 3:
                r = 0;
                g = x;
                b = chroma;
                break;
            case 4:
                r = x;
                g = 0;
                b = chroma;
                break;
            default:
                r = chroma;
                g = 0;
                b = x;
                break;
        }

        // Apply brightness and convert to 0-255
        int red = clamp((int) (Math.pow(r + m, brightness) * 255));
        int green = clamp((int) (Math.pow(g + m, brightness) * 255));
        int blue = clamp((int) (Math.pow(b + m, brightness) * 255));

        return (red << 16) | (green << 8) | blue;
    }

    /**
     * Extracts the red component from a packed RGB value.
     */
    public static int getRed(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    /**
     * Extracts the green component from a packed RGB value.
     */
    public static int getGreen(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    /**
     * Extracts the blue component from a packed RGB value.
     */
    public static int getBlue(int rgb) {
        return rgb & 0xFF;
    }

    /**
     * Clamps a value to the 0-255 range.
     */
    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
