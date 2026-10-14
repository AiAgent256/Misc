package net.aiagent256.stormhold;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Allegiance keys: "house:&lt;id&gt;" for NPC houses, "player:&lt;uuid&gt;" for players. */
public final class Sides {
    public static final String OUTLAWS = "house:outlaws";

    public static String house(String id) {
        return "house:" + id;
    }

    public static String player(UUID id) {
        return "player:" + id;
    }

    public static boolean isPlayer(@Nullable String side) {
        return side != null && side.startsWith("player:");
    }

    public static boolean isHouse(@Nullable String side) {
        return side != null && side.startsWith("house:");
    }

    @Nullable
    public static UUID playerId(@Nullable String side) {
        if (!isPlayer(side)) return null;
        try {
            return UUID.fromString(side.substring(7));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    public static String houseId(@Nullable String side) {
        return isHouse(side) ? side.substring(6) : null;
    }

    private Sides() {
    }
}
