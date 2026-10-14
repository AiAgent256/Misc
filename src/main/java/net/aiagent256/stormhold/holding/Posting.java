package net.aiagent256.stormhold.holding;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** A job on a city's notice board: clear a bandit camp, break a raider warband. */
public class Posting {
    public enum Kind {BANDIT_CAMP, WARBAND}

    public UUID id = UUID.randomUUID();
    public Kind kind = Kind.BANDIT_CAMP;
    public String title = "";
    public BlockPos at = BlockPos.ZERO;
    public int reward;
    public int standing;
    @Nullable public UUID takenBy;
    @Nullable public UUID campaign;
    public long expires;

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", id);
        t.putString("Kind", kind.name());
        t.putString("Title", title);
        t.put("At", NbtUtils.writeBlockPos(at));
        t.putInt("Reward", reward);
        t.putInt("Standing", standing);
        if (takenBy != null) t.putUUID("TakenBy", takenBy);
        if (campaign != null) t.putUUID("Campaign", campaign);
        t.putLong("Expires", expires);
        return t;
    }

    public static Posting load(CompoundTag t) {
        Posting p = new Posting();
        p.id = t.getUUID("Id");
        try {
            p.kind = Kind.valueOf(t.getString("Kind"));
        } catch (IllegalArgumentException ignored) {
        }
        p.title = t.getString("Title");
        p.at = NbtUtils.readBlockPos(t.getCompound("At"));
        p.reward = t.getInt("Reward");
        p.standing = t.getInt("Standing");
        if (t.hasUUID("TakenBy")) p.takenBy = t.getUUID("TakenBy");
        if (t.hasUUID("Campaign")) p.campaign = t.getUUID("Campaign");
        p.expires = t.getLong("Expires");
        return p;
    }
}
