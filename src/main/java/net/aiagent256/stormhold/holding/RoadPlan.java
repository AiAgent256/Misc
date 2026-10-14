package net.aiagent256.stormhold.holding;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.ChunkPos;

/** A road from a castle gate to a village, painted chunk by chunk as the world loads. */
public class RoadPlan {
    public BlockPos from = BlockPos.ZERO;
    public BlockPos to = BlockPos.ZERO;
    public long seed;
    public String theme = "north";
    public final LongOpenHashSet painted = new LongOpenHashSet();

    /** Point along the road with a gentle meander, t in [0,1]. */
    public double[] at(double t) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        double nx = -dz / len, nz = dx / len;
        double phase = (seed & 1023) / 1023.0 * Math.PI * 2;
        double wobble = Math.sin(t * Math.PI) * (Math.sin(t * len / 45.0 + phase) * 9 + Math.sin(t * len / 17.0 + phase * 2) * 3);
        return new double[]{from.getX() + dx * t + nx * wobble, from.getZ() + dz * t + nz * wobble};
    }

    public double length() {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public boolean touches(ChunkPos c) {
        int minX = Math.min(from.getX(), to.getX()) - 24, maxX = Math.max(from.getX(), to.getX()) + 24;
        int minZ = Math.min(from.getZ(), to.getZ()) - 24, maxZ = Math.max(from.getZ(), to.getZ()) + 24;
        return c.getMaxBlockX() >= minX && c.getMinBlockX() <= maxX && c.getMaxBlockZ() >= minZ && c.getMinBlockZ() <= maxZ;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.put("From", NbtUtils.writeBlockPos(from));
        t.put("To", NbtUtils.writeBlockPos(to));
        t.putLong("Seed", seed);
        t.putString("Theme", theme);
        t.putLongArray("Painted", painted.toLongArray());
        return t;
    }

    public static RoadPlan load(CompoundTag t) {
        RoadPlan r = new RoadPlan();
        r.from = NbtUtils.readBlockPos(t.getCompound("From"));
        r.to = NbtUtils.readBlockPos(t.getCompound("To"));
        r.seed = t.getLong("Seed");
        r.theme = t.getString("Theme");
        for (long l : t.getLongArray("Painted")) r.painted.add(l);
        return r;
    }
}
