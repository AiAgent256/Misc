package net.aiagent256.stormhold.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** What the War Table shows for one castle. */
public record HoldingInfo(UUID id, String name, int tier, String mode, int[] counts, int[] capacity, String stock,
                          BlockPos pos, @Nullable BlockPos attack, @Nullable BlockPos supply, String status) {

    public void write(FriendlyByteBuf b) {
        b.writeUUID(id);
        b.writeUtf(name);
        b.writeVarInt(tier);
        b.writeUtf(mode);
        b.writeVarIntArray(counts);
        b.writeVarIntArray(capacity);
        b.writeUtf(stock);
        b.writeBlockPos(pos);
        b.writeBoolean(attack != null);
        if (attack != null) b.writeBlockPos(attack);
        b.writeBoolean(supply != null);
        if (supply != null) b.writeBlockPos(supply);
        b.writeUtf(status);
    }

    public static HoldingInfo read(FriendlyByteBuf b) {
        UUID id = b.readUUID();
        String name = b.readUtf();
        int tier = b.readVarInt();
        String mode = b.readUtf();
        int[] counts = b.readVarIntArray();
        int[] cap = b.readVarIntArray();
        String stock = b.readUtf();
        BlockPos pos = b.readBlockPos();
        BlockPos attack = b.readBoolean() ? b.readBlockPos() : null;
        BlockPos supply = b.readBoolean() ? b.readBlockPos() : null;
        String status = b.readUtf();
        return new HoldingInfo(id, name, tier, mode, counts, cap, stock, pos, attack, supply, status);
    }
}
