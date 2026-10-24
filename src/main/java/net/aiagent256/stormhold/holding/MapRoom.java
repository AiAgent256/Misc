package net.aiagent256.stormhold.holding;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The map room. The first time scouts come home, the castle takes the broadest bare wall near its War Table
 * for their maps and sets a cartography table at its foot. Every map they draw goes up in its true place: a
 * scouts' map is a square of the world 1024 blocks a side (a scale-3 map), and those squares line up on the
 * world's own grid, so the castle's own square hangs in the middle, north at the top and east to the right,
 * and journey by journey the wall fills in to one map of the country. A square ridden again gets the newer map.
 */
public final class MapRoom {
    /** Walls tried, broadest first: frames across, frames down. Odd both ways, so home sits in the middle. */
    static final int[][] SIZES = {{9, 5}, {7, 5}, {7, 3}, {5, 3}, {3, 3}};
    /** How far from the War Table the wall may be. */
    static final int REACH = 16;
    /** A scouts' map's side in blocks (scale 3). */
    public static final int CELL = 1024;
    /** A castle with no bare wall looks again this often (ticks): someone may have cleared one. */
    private static final long RETRY = 6000;

    /** The map square a block coordinate lies in, as Minecraft lays its scale-3 maps out. */
    public static int cell(int coord) {
        return Math.floorDiv(coord + 64, CELL);
    }

    /** Set the map room up if it isn't yet. False if there's no wall for one (yet), or the hall isn't loaded. */
    public static boolean ensure(ServerLevel level, HoldingManager mgr, Holding h) {
        if (h.mapRoom != null) return true;
        long now = level.getGameTime();
        if (h.mapRoomTriedAt != 0 && now - h.mapRoomTriedAt < RETRY) return false;
        BlockPos anchor = anchor(h);
        if (!level.isLoaded(anchor)) return false;
        int[] r = find(level, h, anchor);
        if (r == null) {
            h.mapRoomTriedAt = now;
            return false;
        }
        h.mapRoom = r;
        mgr.setDirty();
        Wall w = wall(h);
        // the table at the foot of the wall, under the middle of the bottom row
        BlockPos table = w.frame(w.across / 2, w.down - 1).below();
        if (level.getBlockState(table).isAir()) level.setBlock(table, Blocks.CARTOGRAPHY_TABLE.defaultBlockState(), 3);
        // home first: the castle's own square in the middle
        hang(level, h, homeMap(level, h));
        HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("The scouts take a wall of " + h.name + " for a map room ("
                + table.getX() + ", " + table.getY() + ", " + table.getZ() + "): " + w.across + " maps across and " + w.down
                + " down, home in the middle, north at the top. Every map they draw goes up there in its place.").withStyle(ChatFormatting.GOLD));
        return true;
    }

    /**
     * Hang a scouts' map in its place on the map-room wall, over any older map of the same square. False if the
     * room isn't set up, the map isn't a scouts' map, its square lies off the wall, or the wall isn't loaded.
     */
    public static boolean hang(ServerLevel level, Holding h, ItemStack map) {
        if (h.mapRoom == null) return false;
        MapItemSavedData data = MapItem.getSavedData(map, level);
        if (data == null || data.scale != 3) return false;
        Wall w = wall(h);
        int col = w.across / 2 + (cell(data.centerX) - w.homeX);
        int row = w.down / 2 + (cell(data.centerZ) - w.homeZ);
        if (col < 0 || col >= w.across || row < 0 || row >= w.down) return false;
        BlockPos at = w.frame(col, row);
        if (!level.isLoaded(at)) return false;
        List<ItemFrame> there = level.getEntitiesOfClass(ItemFrame.class, new AABB(at), f -> f.getDirection() == w.facing);
        if (!there.isEmpty()) {
            there.get(0).setItem(map.copy(), false);
            return true;
        }
        if (!level.getBlockState(at).isAir()) return false;
        ItemFrame frame = new ItemFrame(level, at, w.facing);
        if (!frame.survives()) return false;
        frame.setItem(map.copy(), false);
        level.addFreshEntity(frame);
        return true;
    }

    /** Where on the wall a map of the square at block x, z goes, as [across, down]; null if off the wall or no room. */
    @Nullable
    public static int[] slot(Holding h, int x, int z) {
        if (h.mapRoom == null) return null;
        Wall w = wall(h);
        int col = w.across / 2 + (cell(x) - w.homeX), row = w.down / 2 + (cell(z) - w.homeZ);
        return col < 0 || col >= w.across || row < 0 || row >= w.down ? null : new int[]{col, row};
    }

    /** Whether {@code p} is one of the map-room wall's frame cells: other maps and frames keep off it. */
    public static boolean onWall(Holding h, BlockPos p) {
        if (h.mapRoom == null) return false;
        Wall w = wall(h);
        for (int c = 0; c < w.across; c++) for (int r = 0; r < w.down; r++) if (w.frame(c, r).equals(p)) return true;
        return false;
    }

    /** The frame cell of a slot, for the tests and the guide. */
    public static BlockPos frameAt(Holding h, int across, int down) {
        return wall(h).frame(across, down);
    }

    // ------------------------------------------------------------ the wall

    record Wall(BlockPos topLeft, Direction facing, int across, int down, int homeX, int homeZ) {
        /** The viewer's right as he faces the wall: east when the wall is on the north side of the room. */
        Direction right() {
            return facing.getOpposite().getClockWise();
        }

        BlockPos frame(int col, int row) {
            return topLeft.relative(right(), col).below(row);
        }
    }

    static Wall wall(Holding h) {
        int[] r = h.mapRoom;
        return new Wall(new BlockPos(r[0], r[1], r[2]), Direction.from3DDataValue(r[3]), r[4], r[5], r[6], r[7]);
    }

    static BlockPos anchor(Holding h) {
        return !h.warTablePos.equals(BlockPos.ZERO) ? h.warTablePos : !h.bannerPos.equals(BlockPos.ZERO) ? h.bannerPos : h.center();
    }

    /**
     * The broadest bare wall within REACH of the anchor, nearest first: solid behind every frame, air in every
     * frame cell and in front of it, nothing hung there already, and a floor one block under the bottom row
     * (the table stands there). As the mapRoom array, or null.
     */
    @Nullable
    static int[] find(ServerLevel level, Holding h, BlockPos anchor) {
        int homeX = cell(h.center().getX()), homeZ = cell(h.center().getZ());
        for (int[] sz : SIZES) {
            int across = sz[0], down = sz[1];
            BlockPos best = null;
            Direction bestD = null;
            double bestDist = Double.MAX_VALUE;
            for (int dy = -3; dy <= 6; dy++) {
                for (int dx = -REACH; dx <= REACH; dx++) {
                    for (int dz = -REACH; dz <= REACH; dz++) {
                        // the bottom-left frame cell
                        BlockPos bl = anchor.offset(dx, dy, dz);
                        for (Direction d : Direction.Plane.HORIZONTAL) {
                            double dist = bl.distSqr(anchor);
                            if (dist >= bestDist) continue;
                            if (fits(level, h, bl, d, across, down)) {
                                best = bl;
                                bestD = d;
                                bestDist = dist;
                            }
                        }
                    }
                }
            }
            if (best != null) {
                BlockPos tl = best.above(down - 1);
                return new int[]{tl.getX(), tl.getY(), tl.getZ(), bestD.get3DDataValue(), across, down, homeX, homeZ, 0};
            }
        }
        return null;
    }

    static boolean fits(ServerLevel level, Holding h, BlockPos bl, Direction d, int across, int down) {
        Direction right = d.getOpposite().getClockWise();
        // the floor under the middle of the bottom row, where the table goes
        BlockPos table = bl.relative(right, across / 2).below();
        if (!h.box.isInside(table) || !level.isLoaded(table) || !level.getBlockState(table).isAir()) return false;
        BlockState floor = level.getBlockState(table.below());
        if (!floor.isFaceSturdy(level, table.below(), Direction.UP)) return false;
        for (int c = 0; c < across; c++) {
            for (int r = 0; r < down; r++) {
                BlockPos f = bl.relative(right, c).above(r);
                if (!h.box.isInside(f) || !level.isLoaded(f) || !level.getBlockState(f).isAir() || Chart.onWall(h, f)) return false;
                if (!level.getBlockState(f.relative(d)).isAir()) return false;
                BlockPos wall = f.relative(d.getOpposite());
                BlockState ws = level.getBlockState(wall);
                if (!ws.isSolidRender(level, wall) || !ws.isFaceSturdy(level, wall, d)) return false;
            }
        }
        BlockPos tr = bl.relative(right, across - 1).above(down - 1);
        AABB area = new AABB(bl).minmax(new AABB(tr)).inflate(0.1);
        return level.getEntitiesOfClass(HangingEntity.class, area).isEmpty();
    }

    /** The castle's own square, drawn when the room is set up, with its banner on it. */
    static ItemStack homeMap(ServerLevel level, Holding h) {
        BlockPos c = h.center();
        ItemStack map = MapItem.create(level, c.getX(), c.getZ(), (byte) 3, true, true);
        MapItem.renderBiomePreviewMap(level, map);
        MapItemSavedData.addTargetDecoration(map, c, "stormhold_home", MapDecoration.Type.BANNER_LIME);
        map.setHoverName(Component.literal("Scouts' map: " + h.name + " and its country").withStyle(ChatFormatting.GOLD));
        return map;
    }

    private MapRoom() {
    }
}
