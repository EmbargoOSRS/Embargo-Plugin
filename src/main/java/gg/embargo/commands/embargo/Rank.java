package gg.embargo.commands.embargo;

import lombok.Getter;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public enum Rank {
    BRONZE("Bronze", Color.orange),
    IRON("Iron", Color.darkGray),
    STEEL("Steel", Color.lightGray),
    MITHRIL("Mithril", Color.blue),
    ADAMANT("Adamant", Color.green),
    RUNE("Rune", Color.cyan),
    DRAGON("Dragon", Color.red),
    BEAST("Beast", Color.yellow);

    private static final Map<String, Color> NAME_TO_COLOR_MAP = new HashMap<>();

    static {
        for (Rank rank : values()) {
            NAME_TO_COLOR_MAP.put(rank.displayName.toLowerCase(), rank.color);
        }
    }

    private final String displayName;
    @Getter
    private final Color color;

    Rank(String displayName, Color color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static Color getColorByName(String name) {
        if (name == null) {
            return Color.WHITE;
        }
        return NAME_TO_COLOR_MAP.getOrDefault(name.toLowerCase(), Color.WHITE);
    }
}