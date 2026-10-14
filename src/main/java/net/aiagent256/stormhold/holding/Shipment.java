package net.aiagent256.stormhold.holding;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Goods walking from a castle to a chest, a colony hut, or another castle. */
public class Shipment {
    public UUID id = UUID.randomUUID();
    public UUID origin = new UUID(0, 0);
    @Nullable public UUID targetHolding;
    public BlockPos from = BlockPos.ZERO;
    public BlockPos to = BlockPos.ZERO;
    public double progress;
    public boolean ambushRolled;
    public final List<ItemStack> cargo = new ArrayList<>();
    @Nullable public UUID courier;

    public double length() {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        return Math.max(1, Math.sqrt(dx * dx + dz * dz));
    }

    public BlockPos position() {
        double t = Math.max(0, Math.min(1, progress));
        return new BlockPos((int) Math.round(from.getX() + (to.getX() - from.getX()) * t), to.getY(),
                (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t));
    }

    public int items() {
        int n = 0;
        for (ItemStack s : cargo) n += s.getCount();
        return n;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", id);
        t.putUUID("Origin", origin);
        if (targetHolding != null) t.putUUID("TargetHolding", targetHolding);
        t.put("From", NbtUtils.writeBlockPos(from));
        t.put("To", NbtUtils.writeBlockPos(to));
        t.putDouble("Progress", progress);
        t.putBoolean("Ambush", ambushRolled);
        ListTag l = new ListTag();
        for (ItemStack s : cargo) l.add(s.save(new CompoundTag()));
        t.put("Cargo", l);
        return t;
    }

    public static Shipment load(CompoundTag t) {
        Shipment s = new Shipment();
        s.id = t.getUUID("Id");
        s.origin = t.getUUID("Origin");
        if (t.hasUUID("TargetHolding")) s.targetHolding = t.getUUID("TargetHolding");
        s.from = NbtUtils.readBlockPos(t.getCompound("From"));
        s.to = NbtUtils.readBlockPos(t.getCompound("To"));
        s.progress = t.getDouble("Progress");
        s.ambushRolled = t.getBoolean("Ambush");
        ListTag l = t.getList("Cargo", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) s.cargo.add(ItemStack.of(l.getCompound(i)));
        return s;
    }
}
