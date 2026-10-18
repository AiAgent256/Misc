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
