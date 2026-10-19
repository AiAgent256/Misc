package net.aiagent256.stormhold.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** What the War Table shows for one castle. */
public record HoldingInfo(UUID id, String name, int tier, String mode, int[] counts, int[] capacity, String stock,
                          BlockPos pos, @Nullable BlockPos attack, @Nullable BlockPos supply, String status, String fief, String realm,
                          int miners, int smiths, String labour, String odds) {

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
        b.writeUtf(fief);
        b.writeUtf(realm);
        b.writeVarInt(miners);
        b.writeVarInt(smiths);
        b.writeUtf(labour);
        b.writeUtf(odds);
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
        String fief = b.readUtf();
        String realm = b.readUtf();
        int miners = b.readVarInt(), smiths = b.readVarInt();
        String labour = b.readUtf();
        String odds = b.readUtf();
        return new HoldingInfo(id, name, tier, mode, counts, cap, stock, pos, attack, supply, status, fief, realm, miners, smiths, labour, odds);
    }
}
