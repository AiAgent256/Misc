package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.entity.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** An army on the move: a house raid against a player's castle, or a player's war party. */
public class Campaign {
    public enum Kind {RAID, PARTY, CAMP, COLUMN, ILLAGERS, SCOUT}

    public enum Stage {MARCH, SIEGE, RETURN, DONE}

    public UUID id = UUID.randomUUID();
    public Kind kind = Kind.RAID;
    public Stage stage = Stage.MARCH;
    public String side = "";
    @Nullable public UUID origin;
    @Nullable public UUID target;
    /** COLUMN: the player the men are riding to join. */
    @Nullable public UUID player;
    public BlockPos from = BlockPos.ZERO;
    public BlockPos to = BlockPos.ZERO;
    public double progress;
    public long siegeUntil;
    public int tier = 2;
    public int renown = 2;
    public int color;
    public final EnumMap<Role, Integer> counts = new EnumMap<>(Role.class);
    public final Map<UUID, Role> live = new HashMap<>();
    /** Not saved: how long the live party has made no headway while a player watches. */
    public double lastProgress = -1;
    public int stallSeconds;
    /** Not saved: the raiders have already broken this castle's gate. */
    public boolean rammed;
    /** ILLAGERS: the warband by kind (entity id, or "captain"), what's on the field now, and its spoils. */
    public final Map<String, Integer> band = new java.util.LinkedHashMap<>();
    public final Map<UUID, String> bandLive = new HashMap<>();
    public int spoils;
    /** Not saved: how the warband's siege is going. */
    public int siegeSeconds, sackSeconds;
    public boolean rammedOpen, laddered, sapped;
    /** A raid or warband before the walls with nobody there: how long it has waited for the lord to come and fight it. */
    public int waitSeconds;
    /** Not saved: a player came, so it's fought for real, and stays so until he's well away again. */
    public boolean engaged;
    /** Not saved: the bar over the fight for the players at it, and the size it started at. */
    @Nullable public net.minecraft.server.level.ServerBossEvent bar;
    public int barMax;

    /** A house's ram before the gate (see {@link SiegeWorks}): where its head stands, which way it faces, blows struck. */
    @Nullable public BlockPos ramHead;
    public net.minecraft.core.Direction ramIn = net.minecraft.core.Direction.NORTH;
    public int ramHits;
    /** The ram is wrecked, or there was no room for one. */
    public boolean ramDone;
    /** Rams brought up this attack, the second the next may come, the second the last one came; the Siege Machines ram, if it's one. */
    public int ramsUsed, nextRamAt, ramRaisedAt;
    /** How many marched out, to tell when four in five have fallen; seconds since they did. */
    public int mustered, routSeconds;
    @Nullable public java.util.UUID ramMachine;
    /** The heir who leads this attack to take back his father's castle (see {@link Heirs}); empty for an ordinary raid. */
    public String heir = "";
    /** A column a supply line sent (cancel the line and it turns back), not one you asked for. */
    public boolean supply;

    public int total() {
        int n = 0;
        for (int v : counts.values()) n += v;
        for (int v : band.values()) n += v;
        return n;
    }

    public double strength() {
        double s = 0;
        for (var e : counts.entrySet()) s += e.getValue() * e.getKey().strength();
        for (var e : band.entrySet()) s += e.getValue() * Warbands.strength(e.getKey());
        return s;
    }

    public void add(Role r, int n) {
        counts.put(r, Math.max(0, counts.getOrDefault(r, 0) + n));
    }

    public BlockPos position() {
        double t = Math.max(0, Math.min(1, progress));
        return new BlockPos((int) Math.round(from.getX() + (to.getX() - from.getX()) * t), to.getY(),
                (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t));
    }

    public double length() {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        return Math.max(1, Math.sqrt(dx * dx + dz * dz));
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", id);
        t.putString("Kind", kind.name());
        t.putString("Stage", stage.name());
        t.putString("Side", side);
        if (origin != null) t.putUUID("Origin", origin);
        if (target != null) t.putUUID("Target", target);
        if (player != null) t.putUUID("Player", player);
        t.put("From", NbtUtils.writeBlockPos(from));
        t.put("To", NbtUtils.writeBlockPos(to));
        t.putDouble("Progress", progress);
        t.putLong("SiegeUntil", siegeUntil);
        t.putInt("Tier", tier);
        t.putInt("Renown", renown);
        t.putInt("Color", color);
        CompoundTag c = new CompoundTag();
        counts.forEach((r, n) -> c.putInt(r.name(), n));
        t.put("Counts", c);
        CompoundTag b = new CompoundTag();
        band.forEach(b::putInt);
        t.put("Band", b);
        t.putInt("Spoils", spoils);
        t.putInt("Wait", waitSeconds);
        if (ramHead != null) {
            t.put("RamHead", NbtUtils.writeBlockPos(ramHead));
            t.putInt("RamIn", ramIn.get3DDataValue());
            t.putInt("RamHits", ramHits);
        }
        t.putBoolean("RamDone", ramDone);
        t.putInt("RamsUsed", ramsUsed);
        t.putInt("Mustered", mustered);
        if (ramMachine != null) t.putUUID("RamMachine", ramMachine);
        if (!heir.isEmpty()) t.putString("Heir", heir);
        if (supply) t.putBoolean("Supply", true);
        return t;
    }

    public static Campaign load(CompoundTag t) {
        Campaign c = new Campaign();
        c.id = t.getUUID("Id");
        c.kind = Kind.valueOf(t.getString("Kind"));
        c.stage = Stage.valueOf(t.getString("Stage"));
        c.side = t.getString("Side");
        if (t.hasUUID("Origin")) c.origin = t.getUUID("Origin");
        if (t.hasUUID("Target")) c.target = t.getUUID("Target");
        if (t.hasUUID("Player")) c.player = t.getUUID("Player");
        c.from = NbtUtils.readBlockPos(t.getCompound("From"));
        c.to = NbtUtils.readBlockPos(t.getCompound("To"));
        c.progress = t.getDouble("Progress");
        c.siegeUntil = t.getLong("SiegeUntil");
        c.tier = t.getInt("Tier");
        if (t.contains("Renown")) c.renown = t.getInt("Renown");
        c.color = t.getInt("Color");
        c.waitSeconds = t.getInt("Wait");
        if (t.contains("RamHead")) {
            c.ramHead = NbtUtils.readBlockPos(t.getCompound("RamHead"));
            c.ramIn = net.minecraft.core.Direction.from3DDataValue(t.getInt("RamIn"));
            c.ramHits = t.getInt("RamHits");
        }
        c.ramDone = t.getBoolean("RamDone");
        c.ramsUsed = t.getInt("RamsUsed");
        c.mustered = t.getInt("Mustered");
        if (t.hasUUID("RamMachine")) c.ramMachine = t.getUUID("RamMachine");
        c.heir = t.getString("Heir");
        c.supply = t.getBoolean("Supply");
        CompoundTag cc = t.getCompound("Counts");
        for (String k : cc.getAllKeys()) {
            try {
                c.counts.put(Role.valueOf(k), cc.getInt(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
        CompoundTag b = t.getCompound("Band");
        for (String k : b.getAllKeys()) c.band.put(k, b.getInt(k));
        c.spoils = t.getInt("Spoils");
        return c;
    }
}
