package net.aiagent256.stormhold.holding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Houses made by rule, not copied from a drawing: each one rolled from its lot, its trade and the country it stands
 * in. A footprint fills the lot; one to three floors (a ground floor of stone or plaster, timber-framed floors above);
 * a gabled roof along its longer side with eaves and gable overhangs (or, in Dorne, a flat roof behind a parapet);
 * windows spaced along each wall, a door on the street, a chimney on some, beds and the tools of the trade inside, a
 * garden of wheat behind a farmhouse. No two come out alike, and every one faces its street.
 */
public final class HouseGen {
    public enum Kind { HOUSE, FARMHOUSE, BAKERY, SMITHY, INN, STALL, WORKSHOP }

    /** A lot: its min corner, its size along x and z, and the side that faces the street. The floor goes at {@code y}. */
    public record Lot(int x0, int z0, int w, int d, int y, Direction front) {
        public BoundingBox box() {
            return new BoundingBox(x0, y - 1, z0, x0 + w - 1, y + 16, z0 + d - 1);
        }
    }

    /** What went up: its walls' box, the step outside its door, its beds, and its garden if it has one. */
    public record Built(BoundingBox box, BlockPos door, List<BlockPos> beds, @Nullable BoundingBox garden, int blocks) {
    }

    /** A country's way of building: materials for each part, rolled once per house. */
    static final class Palette {
        Block foundation = Blocks.COBBLESTONE, lower = Blocks.COBBLESTONE, frame = Blocks.SPRUCE_LOG, infill = Blocks.WHITE_TERRACOTTA,
                floor = Blocks.SPRUCE_PLANKS, roofStair = Blocks.SPRUCE_STAIRS, roofSlab = Blocks.SPRUCE_SLAB, roofBlock = Blocks.SPRUCE_PLANKS,
                gable = Blocks.SPRUCE_PLANKS, door = Blocks.SPRUCE_DOOR, trap = Blocks.SPRUCE_TRAPDOOR, chimney = Blocks.COBBLESTONE,
                parapet = Blocks.SANDSTONE_WALL, fence = Blocks.SPRUCE_FENCE;
        boolean flat, framedLower;
    }

    private static <T> T pick(RandomSource r, List<T> xs) {
        return xs.get(r.nextInt(xs.size()));
    }

    static Palette palette(String theme, RandomSource r) {
        Palette p = new Palette();
        switch (theme == null ? "north" : theme) {
            case "dorne" -> {
                p.flat = true;
                p.foundation = Blocks.SANDSTONE;
                p.lower = pick(r, List.of(Blocks.SMOOTH_SANDSTONE, Blocks.SANDSTONE, Blocks.CUT_SANDSTONE));
                p.infill = pick(r, List.of(p.lower, Blocks.SMOOTH_SANDSTONE, Blocks.WHITE_TERRACOTTA));
                p.frame = pick(r, List.of(Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE, Blocks.SMOOTH_SANDSTONE));
                p.floor = Blocks.ACACIA_PLANKS;
                p.roofSlab = Blocks.SMOOTH_SANDSTONE_SLAB;
                p.roofStair = Blocks.SMOOTH_SANDSTONE_STAIRS;
                p.roofBlock = Blocks.SMOOTH_SANDSTONE;
                p.gable = p.infill;
                p.parapet = pick(r, List.of(Blocks.SANDSTONE_WALL, Blocks.RED_SANDSTONE_WALL));
                p.door = Blocks.ACACIA_DOOR;
                p.trap = Blocks.ACACIA_TRAPDOOR;
                p.chimney = Blocks.CUT_SANDSTONE;
                p.fence = Blocks.ACACIA_FENCE;
            }
            case "riverlands" -> {
                p.foundation = Blocks.STONE_BRICKS;
                p.lower = pick(r, List.of(Blocks.BRICKS, Blocks.BRICKS, Blocks.MUD_BRICKS, Blocks.STONE_BRICKS));
                p.frame = pick(r, List.of(Blocks.DARK_OAK_LOG, Blocks.OAK_LOG));
                p.infill = pick(r, List.of(Blocks.WHITE_TERRACOTTA, Blocks.CALCITE, Blocks.BRICKS));
                p.floor = Blocks.DARK_OAK_PLANKS;
                boolean brick = r.nextBoolean();
                p.roofStair = brick ? Blocks.BRICK_STAIRS : Blocks.DARK_OAK_STAIRS;
                p.roofSlab = brick ? Blocks.BRICK_SLAB : Blocks.DARK_OAK_SLAB;
                p.roofBlock = brick ? Blocks.BRICKS : Blocks.DARK_OAK_PLANKS;
                p.gable = p.infill;
                p.door = pick(r, List.of(Blocks.DARK_OAK_DOOR, Blocks.OAK_DOOR));
                p.trap = Blocks.DARK_OAK_TRAPDOOR;
                p.chimney = Blocks.BRICKS;
                p.fence = Blocks.DARK_OAK_FENCE;
            }
            case "reach" -> {
                p.foundation = Blocks.POLISHED_DIORITE;
                p.lower = pick(r, List.of(Blocks.CALCITE, Blocks.POLISHED_DIORITE, Blocks.SMOOTH_QUARTZ));
                p.frame = pick(r, List.of(Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.STRIPPED_OAK_LOG));
                p.infill = pick(r, List.of(Blocks.CALCITE, Blocks.WHITE_TERRACOTTA, Blocks.OAK_PLANKS));
                p.floor = Blocks.OAK_PLANKS;
                boolean brick = r.nextInt(3) > 0;
                p.roofStair = brick ? Blocks.BRICK_STAIRS : Blocks.OAK_STAIRS;
                p.roofSlab = brick ? Blocks.BRICK_SLAB : Blocks.OAK_SLAB;
                p.roofBlock = brick ? Blocks.BRICKS : Blocks.OAK_PLANKS;
                p.gable = p.infill;
                p.door = Blocks.OAK_DOOR;
                p.trap = Blocks.OAK_TRAPDOOR;
                p.chimney = Blocks.BRICKS;
                p.fence = Blocks.OAK_FENCE;
            }
            case "stormlands" -> {
                p.foundation = Blocks.COBBLED_DEEPSLATE;
                p.lower = pick(r, List.of(Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE_BRICKS, Blocks.COBBLESTONE));
                p.frame = pick(r, List.of(Blocks.DARK_OAK_LOG, Blocks.SPRUCE_LOG));
                p.infill = pick(r, List.of(Blocks.SPRUCE_PLANKS, Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.WHITE_TERRACOTTA));
                p.floor = Blocks.SPRUCE_PLANKS;
                boolean slate = r.nextBoolean();
                p.roofStair = slate ? Blocks.DEEPSLATE_TILE_STAIRS : Blocks.DARK_OAK_STAIRS;
                p.roofSlab = slate ? Blocks.DEEPSLATE_TILE_SLAB : Blocks.DARK_OAK_SLAB;
                p.roofBlock = slate ? Blocks.DEEPSLATE_TILES : Blocks.DARK_OAK_PLANKS;
                p.gable = p.infill;
                p.door = Blocks.DARK_OAK_DOOR;
                p.trap = Blocks.DARK_OAK_TRAPDOOR;
                p.chimney = Blocks.COBBLED_DEEPSLATE;
                p.fence = Blocks.DARK_OAK_FENCE;
            }
            default -> {
                p.foundation = Blocks.COBBLESTONE;
                p.lower = pick(r, List.of(Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.SPRUCE_PLANKS));
                p.frame = pick(r, List.of(Blocks.SPRUCE_LOG, Blocks.DARK_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG));
                p.infill = pick(r, List.of(Blocks.WHITE_TERRACOTTA, Blocks.SPRUCE_PLANKS, Blocks.CALCITE));
                p.floor = Blocks.SPRUCE_PLANKS;
                int roof = r.nextInt(3);
                p.roofStair = roof == 0 ? Blocks.DEEPSLATE_TILE_STAIRS : roof == 1 ? Blocks.DARK_OAK_STAIRS : Blocks.SPRUCE_STAIRS;
                p.roofSlab = roof == 0 ? Blocks.DEEPSLATE_TILE_SLAB : roof == 1 ? Blocks.DARK_OAK_SLAB : Blocks.SPRUCE_SLAB;
                p.roofBlock = roof == 0 ? Blocks.DEEPSLATE_TILES : roof == 1 ? Blocks.DARK_OAK_PLANKS : Blocks.SPRUCE_PLANKS;
                p.gable = r.nextBoolean() ? p.infill : Blocks.SPRUCE_PLANKS;
                p.door = pick(r, List.of(Blocks.SPRUCE_DOOR, Blocks.DARK_OAK_DOOR));
                p.trap = Blocks.SPRUCE_TRAPDOOR;
                p.chimney = Blocks.COBBLESTONE;
                p.fence = Blocks.SPRUCE_FENCE;
            }
        }
        p.framedLower = !p.flat && r.nextInt(3) == 0;
        return p;
    }

    // ------------------------------------------------------------ the builder's slate

    /** Blocks to set, in order; a later block at the same place replaces an earlier one. */
    private static final class Slate {
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();

        void put(BlockPos p, BlockState s) {
            blocks.remove(p);
            blocks.put(p.immutable(), s);
        }

        void put(int x, int y, int z, BlockState s) {
            put(new BlockPos(x, y, z), s);
        }

        void put(int x, int y, int z, Block b) {
            put(new BlockPos(x, y, z), b.defaultBlockState());
        }

        @Nullable
        BlockState at(int x, int y, int z) {
            return blocks.get(new BlockPos(x, y, z));
        }

        int flush(ServerLevel level) {
            for (var e : blocks.entrySet()) level.setBlock(e.getKey(), e.getValue(), 2 | 16);
            // stairs meet at the corners, panes and fences join up: let each take its shape from its neighbours
            for (var e : blocks.entrySet()) {
                BlockState s = e.getValue();
                Block b = s.getBlock();
                if (b instanceof StairBlock || b instanceof CrossCollisionBlock || b instanceof WallBlock || b instanceof IronBarsBlock
                        || b instanceof FenceBlock) {
                    BlockState fixed = Block.updateFromNeighbourShapes(level.getBlockState(e.getKey()), level, e.getKey());
                    if (fixed != level.getBlockState(e.getKey())) level.setBlock(e.getKey(), fixed, 2 | 16);
                }
            }
            return blocks.size();
        }
    }

    // ------------------------------------------------------------ the house

    /** Build it. The lot must already be cleared to its floor (see Town). */
    public static Built build(ServerLevel level, Lot lot, Kind kind, String theme, RandomSource r) {
        Palette p = palette(theme, r);
        Slate s = new Slate();
        if (kind == Kind.STALL) return stall(level, lot, p, r, s);
        // the house's own footprint: the lot, or the front of it for a farmhouse with its garden behind
        int gardenDepth = kind == Kind.FARMHOUSE ? Math.min(5, depthOf(lot) - 5) : 0;
        int x0 = lot.x0(), z0 = lot.z0(), x1 = lot.x0() + lot.w() - 1, z1 = lot.z0() + lot.d() - 1;
        BoundingBox garden = null;
        if (gardenDepth >= 3) {
            switch (lot.front()) {
                case NORTH -> { garden = new BoundingBox(x0, lot.y(), z1 - gardenDepth + 1, x1, lot.y() + 1, z1); z1 -= gardenDepth; }
                case SOUTH -> { garden = new BoundingBox(x0, lot.y(), z0, x1, lot.y() + 1, z0 + gardenDepth - 1); z0 += gardenDepth; }
                case WEST -> { garden = new BoundingBox(x1 - gardenDepth + 1, lot.y(), z0, x1, lot.y() + 1, z1); x1 -= gardenDepth; }
                default -> { garden = new BoundingBox(x0, lot.y(), z0, x0 + gardenDepth - 1, lot.y() + 1, z1); x0 += gardenDepth; }
            }
        }
        int w = x1 - x0 + 1, d = z1 - z0 + 1;
        int floors = switch (kind) {
            case INN -> 2 + (Math.min(w, d) >= 8 && r.nextBoolean() ? 1 : 0);
            case SMITHY, BAKERY, STALL -> 1;
            default -> Math.min(w, d) >= 6 && r.nextInt(5) < 2 ? 2 : 1;
        };
        int y = lot.y();
        int wallTop = y + 4 * floors - 1;
        boolean ridgeX = w >= d;
        Direction front = lot.front();
        // the door: on the street side, middle or a little off it
        int doorAlong = 1 + (Math.max(1, (front.getAxis() == Direction.Axis.Z ? w : d) - 2) / 2) + (r.nextInt(3) - 1);
        BlockPos door = doorCell(x0, z0, x1, z1, y + 1, front, doorAlong);
        // ground under it: foundation down to the land
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                s.put(x, y, z, edge ? p.foundation : p.floor);
                for (int k = 1; k <= 8; k++) {
                    BlockState below = level.getBlockState(new BlockPos(x, y - k, z));
                    if (!below.isAir() && below.getFluidState().isEmpty() && !below.canBeReplaced()) break;
                    s.put(x, y - k, z, edge ? p.foundation : Blocks.DIRT);
                }
            }
        }
        // the walls, floor by floor
        for (int f = 0; f < floors; f++) {
            int base = y + 4 * f;
            if (f > 0) {
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                        if (edge) {
                            Direction.Axis ax = (z == z0 || z == z1) ? Direction.Axis.X : Direction.Axis.Z;
                            s.put(x, base, z, axis(p.frame, ax));
                        } else {
                            s.put(x, base, z, p.floor);
                        }
                    }
                }
            }
            boolean framed = f > 0 || p.framedLower;
            Block wall = f == 0 ? p.lower : p.infill;
            for (int h = 1; h <= 3; h++) {
                int yy = base + h;
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                        if (!edge) {
                            s.put(x, yy, z, Blocks.AIR);
                            continue;
                        }
                        boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                        int along = (z == z0 || z == z1) ? x - x0 : z - z0;
                        boolean post = corner || framed && along % 3 == 0;
                        s.put(x, yy, z, post ? (p.flat ? p.frame.defaultBlockState() : axis(p.frame, Direction.Axis.Y)) : wall.defaultBlockState());
                    }
                }
            }
            // windows: every other cell along each wall at eye height, off the posts and clear of the door
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                    boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                    if (!edge || corner) continue;
                    int along = (z == z0 || z == z1) ? x - x0 : z - z0;
                    if (along % 2 != 0 || framed && along % 3 == 0) continue;
                    BlockPos here = new BlockPos(x, base + 1, z);
                    if (f == 0 && here.distManhattan(door) <= 1) continue;
                    if (kind == Kind.SMITHY && onSide(x, z, x0, z0, x1, z1, front)) continue;
                    s.put(x, base + 2, z, Blocks.GLASS_PANE);
                    if (kind == Kind.INN || r.nextInt(3) == 0) s.put(x, base + 3, z, Blocks.GLASS_PANE);
                }
            }
        }
        // a smithy is open to the street: posts and nothing between them
        if (kind == Kind.SMITHY) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (!onSide(x, z, x0, z0, x1, z1, front)) continue;
                    boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                    if (corner) continue;
                    for (int h = 1; h <= 3; h++) s.put(x, y + h, z, Blocks.AIR);
                }
            }
        } else {
            BlockState dl = p.door.defaultBlockState().setValue(DoorBlock.FACING, front.getOpposite()).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            s.put(door, dl);
            s.put(door.above(), dl.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        // a step and a lantern by the door
        BlockPos step = door.relative(front);
        // the roof
        int ridgeTop;
        if (p.flat) ridgeTop = flatRoof(s, x0, z0, x1, z1, wallTop, p, r);
        else ridgeTop = gableRoof(s, x0, z0, x1, z1, wallTop, ridgeX, p, r);
        // a chimney on some, always on the bakery and the smithy
        boolean chimney = kind == Kind.BAKERY || kind == Kind.SMITHY || kind == Kind.INN || r.nextInt(3) == 0;
        if (chimney) {
            int cx = ridgeX ? (r.nextBoolean() ? x0 + 1 : x1 - 1) : (x0 + x1) / 2;
            int cz = ridgeX ? (z0 + z1) / 2 : (r.nextBoolean() ? z0 + 1 : z1 - 1);
            for (int yy = y + 1; yy <= ridgeTop + 1; yy++) s.put(cx, yy, cz, p.chimney);
            s.put(cx, ridgeTop + 2, cz, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, false));
            if (kind != Kind.SMITHY) s.put(cx, y + 1, cz, Blocks.FURNACE.defaultBlockState());
        }
        // inside: beds against the back wall, the tools of the trade by the door, a light, a way up
        List<BlockPos> beds = new ArrayList<>();
        Direction back = front.getOpposite();
        int bedsPerFloor = kind == Kind.INN ? Math.max(2, (Math.max(w, d) - 3) / 2) : kind == Kind.SMITHY || kind == Kind.BAKERY ? 1 : 2;
        for (int f = 0; f < floors; f++) {
            int base = y + 4 * f;
            if (kind == Kind.SMITHY && f == 0) continue;
            int placed = 0;
            int across = front.getAxis() == Direction.Axis.Z ? w : d;
            int last = floors > 1 ? across - 3 : across - 2;           // the far back corner is the ladder's
            for (int i = 1; i <= last && placed < bedsPerFloor; i += 2) {
                BlockPos head = backCell(x0, z0, x1, z1, base + 1, back, i);
                BlockPos foot = head.relative(front);
                if (taken(s, head) || taken(s, foot)) continue;
                if (foot.distManhattan(door) <= 1 || foot.distManhattan(door.relative(back)) <= 0) continue;
                BlockState bed = (r.nextBoolean() ? Blocks.RED_BED : Blocks.BROWN_BED).defaultBlockState().setValue(BedBlock.FACING, back);
                s.put(foot, bed.setValue(BedBlock.PART, BedPart.FOOT));
                s.put(head, bed.setValue(BedBlock.PART, BedPart.HEAD));
                beds.add(foot);
                placed++;
            }
            // a lantern hung in the middle: from the ceiling, or on a chain from the roof timbers where the room's open to the roof
            int lx = (x0 + x1) / 2, lz = (z0 + z1) / 2;
            if (!taken(s, new BlockPos(lx, base + 3, lz))) {
                int ceil = base + 4;
                while (ceil < base + 24 && !taken(s, new BlockPos(lx, ceil, lz))) ceil++;
                if (ceil < base + 24) {
                    for (int yy = base + 4; yy < ceil; yy++) s.put(lx, yy, lz, Blocks.CHAIN.defaultBlockState());
                    s.put(lx, base + 3, lz, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
                }
            }
        }
        if (floors > 1) {
            // a ladder against the back wall in the far corner, through a hole in each floor above
            int across = front.getAxis() == Direction.Axis.Z ? w : d;
            BlockPos lad = backCell(x0, z0, x1, z1, y + 1, back, across - 2);
            for (int yy = y + 1; yy < y + 4 * floors; yy++)
                s.put(lad.getX(), yy, lad.getZ(), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, front));
        }
        // the trade, by the door where there's room
        BlockPos work = inside(door, back, 1).relative(front.getClockWise(), 2);
        BlockPos other = inside(door, back, 1).relative(front.getCounterClockWise(), 2);
        switch (kind) {
            case FARMHOUSE -> place(s, x0, z0, x1, z1, door, back, work, Blocks.COMPOSTER.defaultBlockState());
            case BAKERY -> {
                place(s, x0, z0, x1, z1, door, back, work, Blocks.SMOKER.defaultBlockState().setValue(net.minecraft.world.level.block.AbstractFurnaceBlock.FACING, front));
                place(s, x0, z0, x1, z1, door, back, other, Blocks.BARREL.defaultBlockState());
                place(s, x0, z0, x1, z1, door, back, work.relative(back), Blocks.HAY_BLOCK.defaultBlockState());
            }
            case SMITHY -> {
                place(s, x0, z0, x1, z1, door, back, work, Blocks.BLAST_FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.AbstractFurnaceBlock.FACING, front));
                place(s, x0, z0, x1, z1, door, back, inside(door, back, 2), Blocks.ANVIL.defaultBlockState());
                place(s, x0, z0, x1, z1, door, back, other, Blocks.GRINDSTONE.defaultBlockState());
            }
            case INN -> {
                place(s, x0, z0, x1, z1, door, back, work, Blocks.BARREL.defaultBlockState());
                place(s, x0, z0, x1, z1, door, back, work.relative(back), Blocks.BARREL.defaultBlockState());
                place(s, x0, z0, x1, z1, door, back, other, Blocks.CRAFTING_TABLE.defaultBlockState());
            }
            case WORKSHOP -> place(s, x0, z0, x1, z1, door, back, work, pick(r, List.of(Blocks.LOOM, Blocks.FLETCHING_TABLE, Blocks.CARTOGRAPHY_TABLE,
                    Blocks.STONECUTTER, Blocks.LECTERN, Blocks.CAULDRON, Blocks.BARREL)).defaultBlockState());
            default -> place(s, x0, z0, x1, z1, door, back, work, pick(r, List.of(Blocks.CRAFTING_TABLE, Blocks.BARREL, Blocks.COMPOSTER)).defaultBlockState());
        }
        // shutters on some windows, a flower box or two
        if (!p.flat && r.nextBoolean()) shutters(s, x0, z0, x1, z1, y, floors, p);
        // the garden: wheat in rows with a channel of water, fenced
        if (garden != null) gardenPlot(s, garden, front, p);
        // the step and a lantern on a post by the door
        s.put(step.below(), Blocks.DIRT_PATH);
        int n = s.flush(level);
        BoundingBox box = new BoundingBox(Math.min(x0, lot.x0()), y - 1, Math.min(z0, lot.z0()),
                Math.max(x1, lot.x0() + lot.w() - 1), ridgeTop + 2, Math.max(z1, lot.z0() + lot.d() - 1));
        return new Built(box, step, beds, garden, n);
    }

    /** Something solid set at this cell already? */
    private static boolean taken(Slate s, BlockPos p) {
        BlockState st = s.at(p.getX(), p.getY(), p.getZ());
        return st != null && !st.isAir();
    }

    /** Set {@code st} on the free floor cell inside the walls nearest {@code want}, keeping the doorway clear; nowhere if the room's full. */
    private static void place(Slate s, int x0, int z0, int x1, int z1, BlockPos door, Direction back, BlockPos want, BlockState st) {
        BlockPos doorway = door.relative(back);
        BlockPos best = null;
        int bd = Integer.MAX_VALUE;
        for (int x = x0 + 1; x < x1; x++) {
            for (int z = z0 + 1; z < z1; z++) {
                BlockPos q = new BlockPos(x, want.getY(), z);
                if (q.equals(doorway) || taken(s, q)) continue;
                int d = Math.abs(x - want.getX()) + Math.abs(z - want.getZ());
                if (d < bd) {
                    bd = d;
                    best = q;
                }
            }
        }
        if (best != null) s.put(best, st);
    }

    private static int depthOf(Lot lot) {
        return lot.front().getAxis() == Direction.Axis.Z ? lot.d() : lot.w();
    }

    private static BlockState axis(Block b, Direction.Axis a) {
        BlockState s = b.defaultBlockState();
        return s.hasProperty(RotatedPillarBlock.AXIS) ? s.setValue(RotatedPillarBlock.AXIS, a) : s;
    }

    /** Is (x, z) on the wall facing {@code side}? */
    private static boolean onSide(int x, int z, int x0, int z0, int x1, int z1, Direction side) {
        return switch (side) {
            case NORTH -> z == z0;
            case SOUTH -> z == z1;
            case WEST -> x == x0;
            default -> x == x1;
        };
    }

    /** The cell {@code along} blocks along the wall facing {@code side}, in that wall. */
    private static BlockPos doorCell(int x0, int z0, int x1, int z1, int y, Direction side, int along) {
        return switch (side) {
            case NORTH -> new BlockPos(Math.min(x1 - 1, x0 + along), y, z0);
            case SOUTH -> new BlockPos(Math.min(x1 - 1, x0 + along), y, z1);
            case WEST -> new BlockPos(x0, y, Math.min(z1 - 1, z0 + along));
            default -> new BlockPos(x1, y, Math.min(z1 - 1, z0 + along));
        };
    }

    /** The cell just inside the wall facing {@code back}, {@code along} blocks along it. */
    private static BlockPos backCell(int x0, int z0, int x1, int z1, int y, Direction back, int along) {
        return doorCell(x0, z0, x1, z1, y, back, along).relative(back.getOpposite());
    }

    private static BlockPos inside(BlockPos door, Direction back, int k) {
        return door.relative(back, k);
    }

    /** A gabled roof along the longer side, its eaves and gables a block over the walls. The ridge's height. */
    private static int gableRoof(Slate s, int x0, int z0, int x1, int z1, int wallTop, boolean ridgeX, Palette p, RandomSource r) {
        // across the ridge runs "s" (lo..hi); along it runs "a"
        int sLo = (ridgeX ? z0 : x0) - 1, sHi = (ridgeX ? z1 : x1) + 1;
        int aLo = (ridgeX ? x0 : z0) - 1, aHi = (ridgeX ? x1 : z1) + 1;
        Direction upLo = ridgeX ? Direction.SOUTH : Direction.EAST;   // on the low side the roof climbs toward +s
        int top = wallTop;
        for (int k = 0; ; k++) {
            int lo = sLo + k, hi = sHi - k, yy = wallTop + 1 + k;
            if (lo > hi) break;
            top = yy;
            for (int a = aLo; a <= aHi; a++) {
                if (lo == hi) {
                    put2(s, ridgeX, a, yy, lo, p.roofBlock.defaultBlockState());
                    put2(s, ridgeX, a, yy + 1, lo, p.roofSlab.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                } else {
                    put2(s, ridgeX, a, yy, lo, p.roofStair.defaultBlockState().setValue(StairBlock.FACING, upLo));
                    put2(s, ridgeX, a, yy, hi, p.roofStair.defaultBlockState().setValue(StairBlock.FACING, upLo.getOpposite()));
                }
            }
            // the gable ends: wall up under the roof
            for (int a : new int[]{aLo + 1, aHi - 1}) {
                for (int c = Math.max(lo + 1, sLo + 1); c <= Math.min(hi - 1, sHi - 1); c++) {
                    if (k == 0 && (c == sLo + 1 || c == sHi - 1)) {
                        put2(s, ridgeX, a, yy, c, axis(p.frame, Direction.Axis.Y));
                        continue;
                    }
                    put2(s, ridgeX, a, yy, c, p.gable.defaultBlockState());
                }
            }
            if (k == 0) {
                // the wall plate: the long walls carried up a course to meet the roof
                for (int a = aLo + 1; a <= aHi - 1; a++) {
                    put2(s, ridgeX, a, yy, sLo + 1, axis(p.frame, ridgeX ? Direction.Axis.X : Direction.Axis.Z));
                    put2(s, ridgeX, a, yy, sHi - 1, axis(p.frame, ridgeX ? Direction.Axis.X : Direction.Axis.Z));
                }
            }
            // under the roof, the attic is clear
            int cLo = k == 0 ? sLo + 2 : lo + 1, cHi = k == 0 ? sHi - 2 : hi - 1;
            for (int a = aLo + 2; a <= aHi - 2; a++) for (int c = cLo; c <= cHi; c++) put2(s, ridgeX, a, yy, c, Blocks.AIR.defaultBlockState());
            if (lo == hi || lo + 1 == hi) {
                if (lo + 1 == hi) top = yy;
                break;
            }
        }
        // a small window high in each gable
        int mid = (sLo + sHi) / 2;
        int gy = wallTop + 2;
        if (sHi - sLo >= 6) for (int a : new int[]{aLo + 1, aHi - 1}) put2(s, ridgeX, a, gy, mid, Blocks.GLASS_PANE.defaultBlockState());
        return top;
    }

    /** (a, y, c) in the roof's frame: a along the ridge, c across it. */
    private static void put2(Slate s, boolean ridgeX, int a, int y, int c, BlockState b) {
        if (ridgeX) s.put(a, y, c, b);
        else s.put(c, y, a, b);
    }

    /** A flat roof of slabs behind a low parapet, as in Dorne. The roof's height. */
    private static int flatRoof(Slate s, int x0, int z0, int x1, int z1, int wallTop, Palette p, RandomSource r) {
        int yy = wallTop + 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                s.put(x, yy, z, p.roofBlock);
                if (edge) s.put(x, yy + 1, z, p.parapet);
            }
        }
        // a roof terrace's awning on some
        if (r.nextInt(3) == 0) {
            int cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
            for (int[] o : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) s.put(cx + o[0], yy + 1, cz + o[1], p.fence);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) s.put(cx + dx, yy + 2, cz + dz, Blocks.WHITE_WOOL);
        }
        return yy + 1;
    }

    /** Trapdoor shutters beside the ground-floor windows. */
    private static void shutters(Slate s, int x0, int z0, int x1, int z1, int y, int floors, Palette p) {
        for (int f = 0; f < floors; f++) {
            int yy = y + 4 * f + 2;
            for (int x = x0 + 1; x < x1; x++) {
                for (int[] zz : new int[][]{{z0, -1}, {z1, 1}}) {
                    BlockState here = s.at(x, yy, zz[0]);
                    if (here == null || !here.is(Blocks.GLASS_PANE)) continue;
                    Direction out = zz[1] < 0 ? Direction.NORTH : Direction.SOUTH;
                    for (int dx : new int[]{-1, 1}) {
                        BlockState side = s.at(x + dx, yy, zz[0]);
                        if (side == null || side.is(Blocks.GLASS_PANE)) continue;
                        s.put(x + dx, yy, zz[0] + zz[1], p.trap.defaultBlockState().setValue(TrapDoorBlock.FACING, out).setValue(TrapDoorBlock.OPEN, true)
                                .setValue(TrapDoorBlock.HALF, Half.TOP));
                    }
                }
            }
        }
    }

    /** Rows of wheat on farmland, a channel of water down the middle, a fence round the three outer sides. */
    private static void gardenPlot(Slate s, BoundingBox g, Direction front, Palette p) {
        int y = g.minY();
        boolean alongX = g.getXSpan() >= g.getZSpan();
        int midX = (g.minX() + g.maxX()) / 2, midZ = (g.minZ() + g.maxZ()) / 2;
        for (int x = g.minX(); x <= g.maxX(); x++) {
            for (int z = g.minZ(); z <= g.maxZ(); z++) {
                boolean edge = x == g.minX() || x == g.maxX() || z == g.minZ() || z == g.maxZ();
                boolean houseSide = switch (front) {
                    case NORTH -> z == g.minZ();
                    case SOUTH -> z == g.maxZ();
                    case WEST -> x == g.minX();
                    default -> x == g.maxX();
                };
                if (edge && !houseSide) {
                    s.put(x, y, z, Blocks.DIRT);
                    s.put(x, y + 1, z, p.fence);
                    continue;
                }
                if (edge) {
                    s.put(x, y, z, Blocks.DIRT);
                    s.put(x, y + 1, z, Blocks.AIR);
                    continue;
                }
                boolean channel = alongX ? z == midZ : x == midX;
                if (channel) {
                    s.put(x, y, z, Blocks.WATER);
                    s.put(x, y + 1, z, Blocks.AIR);
                } else {
                    s.put(x, y, z, Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7));
                    s.put(x, y + 1, z, Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE, 3 + (x + z) % 4));
                }
            }
        }
    }

    // ------------------------------------------------------------ a market stall

    private static Built stall(ServerLevel level, Lot lot, Palette p, RandomSource r, Slate s) {
        // up to five along the street and four back, at the street edge of the lot, in the middle of its frontage
        Direction f = lot.front();
        boolean alongX = f.getAxis() == Direction.Axis.Z;
        int along = alongX ? lot.w() : lot.d(), deep = alongX ? lot.d() : lot.w();
        int sw = Math.min(along, 5), sd = Math.min(deep, 4), off = (along - sw) / 2;
        int x0, z0, x1, z1;
        if (alongX) {
            x0 = lot.x0() + off;
            x1 = x0 + sw - 1;
            z0 = f == Direction.NORTH ? lot.z0() : lot.z0() + lot.d() - sd;
            z1 = z0 + sd - 1;
        } else {
            z0 = lot.z0() + off;
            z1 = z0 + sw - 1;
            x0 = f == Direction.WEST ? lot.x0() : lot.x0() + lot.w() - sd;
            x1 = x0 + sd - 1;
        }
        int y = lot.y();
        Block wool = pick(r, List.of(Blocks.RED_WOOL, Blocks.YELLOW_WOOL, Blocks.BLUE_WOOL, Blocks.GREEN_WOOL, Blocks.WHITE_WOOL, Blocks.ORANGE_WOOL));
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                s.put(x, y, z, p.floor);
                for (int k = 1; k <= 6; k++) {
                    BlockState below = level.getBlockState(new BlockPos(x, y - k, z));
                    if (!below.isAir() && below.getFluidState().isEmpty() && !below.canBeReplaced()) break;
                    s.put(x, y - k, z, p.foundation);
                }
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                for (int h = 1; h <= 2; h++) s.put(x, y + h, z, corner ? p.fence.defaultBlockState() : Blocks.AIR.defaultBlockState());
                s.put(x, y + 3, z, ((x + z) % 2 == 0 ? wool : Blocks.WHITE_WOOL).defaultBlockState());
            }
        }
        // the counter along the street side
        for (int x = x0 + 1; x < x1; x++) {
            for (int z = z0 + 1; z < z1; z++) {
                if (onSide(x, z, x0 + 1, z0 + 1, x1 - 1, z1 - 1, f)) s.put(x, y + 1, z, Blocks.BARREL.defaultBlockState());
            }
        }
        int n = s.flush(level);
        BlockPos front = doorCell(x0, z0, x1, z1, y + 1, f, 1).relative(f);
        return new Built(new BoundingBox(x0, y - 1, z0, x1, y + 4, z1), front, List.of(), null, n);
    }

    private HouseGen() {
    }
}
