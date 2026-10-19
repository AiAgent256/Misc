package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Walls fall to engines, not to powder. A castle's stone shrugs off TNT and fire; a trebuchet stone, a
 * mortar ball, a culverin shot or a ram's blow takes it down. The stand, the War Table and every chest and
 * barrel in the place come through: the banner still has somewhere to go, and the treasury is for the winner.
 */
public final class Breach {
    /** Set while the War Horn's trebuchet stone bursts: its explosion is an engine's. */
    static boolean engineBlast;

    /** An explosion thrown by a siege engine. */
    public static boolean byEngine(Explosion x) {
        if (engineBlast) return true;
        if (x.getClass().getName().startsWith("ru.magistu.siegemachines.")) return true;
        Entity src = x.getDirectSourceEntity();
        if (src == null) return false;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(src.getType());
        return id != null && id.getNamespace().equals("siegemachines");
    }

    /** Whether an engine's blast may take this block out of a castle's walls. */
    public static boolean breaks(ServerLevel level, Holding h, BlockPos pos, BlockState s) {
        if (!Config.SIEGE_BREACH.get()) return false;
        if (level.getBlockEntity(pos) != null) return false;                       // chests, barrels, the stand's banner, signs
        if (near(pos, h.bannerPos, 2) || near(pos, h.warTablePos, 1)) return false;
        return s.getDestroySpeed(level, pos) >= 0;                                  // never bedrock and the like
    }

    private static boolean near(BlockPos p, BlockPos q, int r) {
        return !q.equals(BlockPos.ZERO) && Math.abs(p.getX() - q.getX()) <= r && Math.abs(p.getY() - q.getY()) <= r + 1
                && Math.abs(p.getZ() - q.getZ()) <= r;
    }

    private Breach() {
    }
}
