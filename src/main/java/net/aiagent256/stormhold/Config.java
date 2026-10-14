package net.aiagent256.stormhold;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class Config {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ATTACH;
    public static final ForgeConfigSpec.DoubleValue GARRISON_SCALE;
    public static final ForgeConfigSpec.BooleanValue PROTECT_HOUSE_CASTLES;
    public static final ForgeConfigSpec.BooleanValue SEAL_TREASURY;
    public static final ForgeConfigSpec.BooleanValue DISABLE_FOREIGN_SPAWNERS;
    public static final ForgeConfigSpec.BooleanValue CLEAR_FOREIGN_MOBS;
    public static final ForgeConfigSpec.IntValue CAPTURE_SECONDS;
    public static final ForgeConfigSpec.IntValue RAID_MIN_DAYS;
    public static final ForgeConfigSpec.IntValue RAID_MAX_DAYS;
    public static final ForgeConfigSpec.BooleanValue RAIDS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue ABSTRACT_RAIDS;
    public static final ForgeConfigSpec.IntValue DETECT_RADIUS_CHUNKS;
    public static final ForgeConfigSpec.IntValue MATERIALIZE_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue ROADS_ENABLED;
    public static final ForgeConfigSpec.IntValue ROAD_MAX_CHUNKS;
    public static final ForgeConfigSpec.BooleanValue VILLAGERS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue EPIC_KNIGHTS_GEAR;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("holdings");
        ATTACH = b.comment("Structures from other mods that become garrisoned holdings: \"namespace:path=tier\" (tier 1-3).",
                        "Stormhold's own castles always attach.")
                .defineListAllowEmpty(List.of("attach"), () -> List.of(
                        "dungeons_arise:keep_kayra=3",
                        "idas:castle=3",
                        "idas:pillager_fortress=3",
                        "dungeons_arise:illager_fort=2",
                        "dungeons_arise:thornborn_towers=2",
                        "illagerinvasion:illager_fort=2",
                        "nova_structures:donjon=2",
                        "nova_structures:stray_fort=2",
                        "dungeons_arise:bandit_towers=1",
                        "idas:ruined_fort=1"), o -> o instanceof String s && s.contains("="));
        GARRISON_SCALE = b.comment("Multiplier on garrison sizes. Lower this if sieges lag.")
                .defineInRange("garrisonScale", 1.0, 0.1, 4.0);
        PROTECT_HOUSE_CASTLES = b.comment("Walls of castles held by a house cannot be broken or built on (ladders, scaffolding, torches and TNT allowed; portcullises, doors and glass can be broken).")
                .define("protectHouseCastles", true);
        SEAL_TREASURY = b.comment("Containers inside a house castle stay sealed while its lord lives.")
                .define("sealTreasury", true);
        DISABLE_FOREIGN_SPAWNERS = b.comment("Remove mob spawners inside attached structures from other mods.")
                .define("disableForeignSpawners", true);
        CLEAR_FOREIGN_MOBS = b.comment("Remove hostile mobs inside attached structures when the garrison arrives.")
                .define("clearForeignMobs", true);
        CAPTURE_SECONDS = b.comment("Seconds you must hold the banner stand to take a castle.")
                .defineInRange("captureSeconds", 90, 5, 1200);
        DETECT_RADIUS_CHUNKS = b.defineInRange("detectRadiusChunks", 7, 2, 16);
        MATERIALIZE_DISTANCE = b.comment("Garrisons appear when a player is this many blocks from a castle's edge.")
                .defineInRange("materializeDistance", 72, 24, 256);
        VILLAGERS_ENABLED = b.comment("Villagers live and work inside castles.").define("villagers", true);
        EPIC_KNIGHTS_GEAR = b.comment("Use Epic Knights armor for garrisons when that mod is installed.").define("epicKnightsGear", true);
        b.pop();
        b.push("raids");
        RAIDS_ENABLED = b.define("enabled", true);
        RAID_MIN_DAYS = b.defineInRange("minDays", 3, 1, 100);
        RAID_MAX_DAYS = b.defineInRange("maxDays", 5, 1, 100);
        ABSTRACT_RAIDS = b.comment("If true, raids on castles you are far from are fought out abstractly. If false they wait until you are near.")
                .define("resolveWhileAway", true);
        b.pop();
        b.push("roads");
        ROADS_ENABLED = b.comment("Paint a road from each castle to the nearest village as chunks load.").define("enabled", true);
        ROAD_MAX_CHUNKS = b.defineInRange("maxLengthChunks", 40, 4, 128);
        b.pop();
        SPEC = b.build();
    }

    private Config() {
    }
}
