package net.aiagent256.stormhold.compat;

import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.holding.Garrisons;
import net.aiagent256.stormhold.holding.Holding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A waystone at every castle, when the Waystones mod is in the pack: set by the road just outside the gate (beside it,
 * so it doesn't stand in the way of a ram or an army), named for the castle, once, the first time someone comes near.
 * Outside the walls, so a waystone is a way to the gate, not past it. Everything goes through the mod's own API by
 * reflection, so the mod isn't needed to build or run this one.
 */
public final class WaystonesCompat {
    private static final String API = "net.blay09.mods.waystones.api.WaystonesAPI", STYLE = "net.blay09.mods.waystones.api.WaystoneStyle",
            STYLES = "net.blay09.mods.waystones.api.WaystoneStyles", WAYSTONE = "net.blay09.mods.waystones.api.IWaystone",
            MUTABLE = "net.blay09.mods.waystones.api.IMutableWaystone", MANAGER = "net.blay09.mods.waystones.core.WaystoneManager";
    private static Boolean loaded;
    private static boolean broken;

    public static boolean loaded() {
        if (loaded == null) loaded = ModList.get() != null && ModList.get().isLoaded("waystones");
        return loaded && !broken;
    }

    /** The castle's waystone, set if it hasn't one. True if one was set now. */
    public static boolean ensure(ServerLevel level, Holding h) {
        if (!loaded() || h.waystone != null || h.box == null) return false;
        BlockPos at = spot(level, h);
        if (at == null) return false;
        if (!place(level, at, h.name.isEmpty() ? "Castle" : h.name)) return false;
        h.waystone = at;
        return true;
    }

    /** Beside the road a few blocks out from the gate (or by the middle of a place with no gate), where two blocks of air stand on solid ground. */
    @Nullable
    public static BlockPos spot(ServerLevel level, Holding h) {
        BlockPos c = h.center();
        BlockPos base;
        int sx = 0, sz = 0;
        if (h.gatePos.equals(BlockPos.ZERO)) {
            base = c;
            sx = 3;
        } else {
            double dx = h.gatePos.getX() - c.getX(), dz = h.gatePos.getZ() - c.getZ();
            double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
            base = h.gatePos.offset((int) Math.round(dx / len * 6), 0, (int) Math.round(dz / len * 6));
            // to one side of the road: square to the way out
            sx = (int) Math.round(-dz / len * 4);
            sz = (int) Math.round(dx / len * 4);
        }
        for (BlockPos p : new BlockPos[]{base.offset(sx, 0, sz), base.offset(-sx, 0, -sz), base.offset(2 * sx, 0, 2 * sz), base.offset(-2 * sx, 0, -2 * sz)}) {
            if (!level.isLoaded(p)) continue;
            // two blocks of air on solid ground for the stone, and air over its top
            BlockPos s = Garrisons.standable(level, p);
            if (s != null && level.getBlockState(s.above()).isAir() && level.getBlockState(s.above(2)).isAir()) return s;
        }
        return null;
    }

    private static boolean place(ServerLevel level, BlockPos at, String name) {
        try {
            Class<?> api = Class.forName(API), style = Class.forName(STYLE);
            Object def = Class.forName(STYLES).getField("DEFAULT").get(null);
            Object got = api.getMethod("placeWaystone", Level.class, BlockPos.class, style).invoke(null, level, at, def);
            if (!(got instanceof Optional<?> opt) || opt.isEmpty()) return false;
            Object ws = opt.get();
            Class<?> mutable = Class.forName(MUTABLE);
            if (mutable.isInstance(ws)) {
                mutable.getMethod("setName", String.class).invoke(ws, name);
                Class<?> mgr = Class.forName(MANAGER);
                Object m = mgr.getMethod("get", MinecraftServer.class).invoke(null, level.getServer());
                mgr.getMethod("updateWaystone", Class.forName(WAYSTONE)).invoke(m, ws);
            }
            return true;
        } catch (Throwable t) {
            broken = true;
            Stormhold.LOGGER.warn("Stormhold: couldn't set a waystone through the Waystones mod ({}); castles won't get them this session", t.toString());
            return false;
        }
    }

    private WaystonesCompat() {
    }
}
