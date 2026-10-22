package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Stormhold;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A place that grows. Out from a castle's gate a high street runs across the land, and as the place prospers it
 * lengthens, turns, and throws off lanes. Lots line every street on both sides, and each new building goes up on one,
 * facing its street, made by {@link HouseGen} for its trade and its country. The buildings that stood before the
 * streets are tied in by footpaths. A step of growth comes every two or three days the place is fed and at peace (see
 * {@link Growth}); it goes up when someone is near enough for the land to be there.
 */
public final class Town {
    /** Most buildings a place's town grows to, by what it is. */
    public static int cap(Holding h) {
        if (h.isCity()) return 50;
        if (h.isTown()) return 30;
        return switch (Math.max(1, Math.min(4, h.tier))) {
            case 1 -> 10;
            case 2 -> 18;
            case 3 -> 30;
            default -> 40;
        };
    }

    /** Main street and lanes: their width, and how long a stretch is laid at a time. */
    static final int MAIN_W = 3, LANE_W = 2, MAIN_LEN = 14, LANE_LEN = 12;
    /** The approach road starts this far from the walls; streets and lots keep out of the fortifications' belt (to the stakes beyond the moat). */
    static final int CLEAR = 4, FORT = Fortify.STAKES_TO + 1;
    /** Steps of growth waiting to be built, at most. */
    public static final int MOST_PENDING = 3;

    /** A straight stretch of street: from {@code a}, {@code len} blocks in {@code dir}, {@code width} across. */
    public static final class Street {
        public BlockPos a = BlockPos.ZERO;
        public Direction dir = Direction.NORTH;
        public int len, width;
        public boolean main;

        public BlockPos end() {
            return a.relative(dir, len - 1);
        }

        /** How far the street runs to its right (clockwise of its way) and to its left, from its middle line. */
        int right() {
            return width / 2;
        }

        int left() {
            return (width - 1) / 2;
        }

        /** The street's ground, as a box, with a margin all round. */
        public BoundingBox box(int margin) {
            BlockPos b = end();
            Direction side = dir.getClockWise();
            BlockPos p1 = a.relative(side, -left() - margin).relative(dir, -margin), p2 = b.relative(side, right() + margin).relative(dir, margin);
            return new BoundingBox(Math.min(p1.getX(), p2.getX()), -64, Math.min(p1.getZ(), p2.getZ()), Math.max(p1.getX(), p2.getX()), 320,
                    Math.max(p1.getZ(), p2.getZ()));
        }

        /** Every column of it (at y 0). */
        public List<BlockPos> cells() {
            List<BlockPos> out = new ArrayList<>();
            Direction side = dir.getClockWise();
            for (int i = 0; i < len; i++) for (int k = -left(); k <= right(); k++) out.add(a.relative(dir, i).relative(side, k).atY(0));
            return out;
        }

        /** How far out from its middle line a street turning off it to {@code d} starts: just past its edge on that side. */
        int edge(Direction d) {
            return (d == dir.getClockWise() ? right() : left()) + 1;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("A", a.asLong());
            t.putString("Dir", dir.getName());
            t.putInt("Len", len);
            t.putInt("W", width);
            t.putBoolean("Main", main);
            return t;
        }

        static Street load(CompoundTag t) {
            Street s = new Street();
            s.a = BlockPos.of(t.getLong("A"));
            Direction d = Direction.byName(t.getString("Dir"));
            s.dir = d == null || d.getAxis() == Direction.Axis.Y ? Direction.NORTH : d;
            s.len = Math.max(1, t.getInt("Len"));
            s.width = Math.max(1, t.getInt("W"));
            s.main = t.getBoolean("Main");
            return s;
        }
    }

    /** A lot along a street: where, how big, which side faces the street, and whether anything stands on it. */
    public static final class Lot {
        public int x0, z0, w, d;
        public Direction front = Direction.NORTH;
        public boolean built;
        public int tries;
        /** The street it's on, by index. */
        public int street;

        public BoundingBox box(int margin) {
            return new BoundingBox(x0 - margin, -64, z0 - margin, x0 + w - 1 + margin, 320, z0 + d - 1 + margin);
        }

        /** How far it runs back from the street. */
        public int depth() {
            return front.getAxis() == Direction.Axis.Z ? d : w;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putIntArray("R", new int[]{x0, z0, w, d, front.get3DDataValue(), built ? 1 : 0, tries, street});
            return t;
        }

        @Nullable
        static Lot load(CompoundTag t) {
            int[] v = t.getIntArray("R");
            if (v.length < 8) return null;
            Lot l = new Lot();
            l.x0 = v[0];
            l.z0 = v[1];
            l.w = v[2];
            l.d = v[3];
            Direction f = Direction.from3DDataValue(v[4]);
            l.front = f.getAxis() == Direction.Axis.Y ? Direction.NORTH : f;
            l.built = v[5] != 0;
            l.tries = v[6];
            l.street = v[7];
            return l;
        }
    }

    /** One place's streets, lots and growth. */
    public static final class Plan {
        public final List<Street> streets = new ArrayList<>();
        public final List<Lot> lots = new ArrayList<>();
        public int houses, pending, failed;
        /** The buildings that stood before the streets and have their footpath now (by their corner). */
        public final Set<Long> linked = new HashSet<>();
        /** Not saved: ticks to wait before looking for ground for a field or paddock again, after finding none. */
        public int landWait;

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            ListTag st = new ListTag();
            for (Street s : streets) st.add(s.save());
            t.put("Streets", st);
            ListTag lt = new ListTag();
            for (Lot l : lots) lt.add(l.save());
            t.put("Lots", lt);
            t.putInt("Houses", houses);
            t.putInt("Pending", pending);
            t.putInt("Failed", failed);
            t.putLongArray("Linked", linked.stream().mapToLong(Long::longValue).toArray());
            return t;
        }

        public static Plan load(CompoundTag t) {
            Plan p = new Plan();
            for (Tag x : t.getList("Streets", Tag.TAG_COMPOUND)) p.streets.add(Street.load((CompoundTag) x));
            for (Tag x : t.getList("Lots", Tag.TAG_COMPOUND)) {
                Lot l = Lot.load((CompoundTag) x);
                if (l != null) p.lots.add(l);
            }
            p.houses = t.getInt("Houses");
            p.pending = t.getInt("Pending");
            p.failed = t.getInt("Failed");
            for (long l : t.getLongArray("Linked")) p.linked.add(l);
            return p;
        }
    }

    public static Plan plan(Holding h) {
        if (h.town == null) h.town = new Plan();
        return h.town;
    }

    /** Whether this place grows a town: in the overworld, standing, with walls to grow out from. */
    public static boolean grows(ServerLevel level, Holding h) {
        return level.dimension() == net.minecraft.world.level.Level.OVERWORLD && !h.ruined && !h.isNether() && h.box.getXSpan() > 1;
    }

    /** Whether it has room for one more step, counting those waiting. */
    public static boolean room(Holding h) {
        Plan p = plan(h);
        return p.houses + p.pending < cap(h) && p.pending < MOST_PENDING;
    }

    /** One more step of growth asked for. False (and nothing asked) if the place is full grown or has steps enough waiting. */
    public static boolean ask(Holding h) {
        if (!room(h)) return false;
        plan(h).pending++;
        return true;
    }

    /** Is anything of the town's (a street, or a lot built on or waiting) in this footprint? For the fief's own buildings to keep off. */
    public static boolean blocks(Holding h, BoundingBox foot) {
        if (h.town == null) return false;
        BoundingBox f = flat(foot);
        for (Street s : h.town.streets) if (s.box(1).intersects(f)) return true;
        for (Lot l : h.town.lots) if (l.box(1).intersects(f)) return true;
        return false;
    }

    /** True for the pieces the town builds (as against the fief's drawn buildings). */
    public static boolean isGenerated(String piece) {
        for (HouseGen.Kind k : HouseGen.Kind.values()) if (k.name().toLowerCase(Locale.ROOT).equals(piece)) return true;
        return false;
    }

    /** A home: where the people sleep (for what the fief is called). */
    public static boolean isHome(String piece) {
        return piece.startsWith("cottage") || piece.equals("house") || piece.equals("farmhouse") || piece.equals("inn");
    }

    // ------------------------------------------------------------ building, while someone's near

    /** Every couple of seconds while a player is near: tie in an old building, or build a step that's waiting. */
    public static void tick(ServerLevel level, HoldingManager mgr, Holding h) {
        if (!grows(level, h)) return;
        Plan p = plan(h);
        // the high street's laid when there's first something to build
        if (p.streets.isEmpty() && p.pending > 0) {
            if (!seed(level, h, p)) {
                if (++p.failed > 40) give(h, p);
                return;
            }
            mgr.setDirty();
            return;
        }
        // the working land: fields for farmers who have none, paddocks for butchers without beasts
        if (Fields.tick(level, mgr, h, p)) return;
        if (p.streets.isEmpty()) return;
        if (link(level, h, p)) {
            mgr.setDirty();
            return;
        }
        if (p.pending <= 0) return;
        Result r = step(level, h, p);
        if (r == null) {
            if (++p.failed > 40) give(h, p);
            mgr.setDirty();
            return;
        }
        p.pending--;
        p.houses++;
        p.failed = 0;
        mgr.setDirty();
        if (h.isPlayerOwned()) HoldingEvents.tell(level, h.ownerPlayer(), HoldingEvents.gold("The people of " + h.name + " have raised "
                + r.what() + (r.newStreet() ? ", on a new " + (r.main() ? "stretch of the high street" : "lane") : "") + "."));
    }

    /** No room the land allows: the step's dropped. */
    private static void give(Holding h, Plan p) {
        p.pending = Math.max(0, p.pending - 1);
        p.failed = 0;
        Stormhold.LOGGER.info("Stormhold: {} has no more room to grow along its streets", h.name);
    }

    /** What a step did. */
    public record Result(String what, boolean newStreet, boolean main) {
    }

    /** One step: a building on a free lot; if there's none the land allows, a new stretch of street (one with room for lots) first. */
    @Nullable
    public static Result step(ServerLevel level, Holding h, Plan p) {
        RandomSource r = level.random;
        Result built = buildOnALot(level, h, p, r);
        if (built != null) return built;
        for (int attempt = 0; attempt < 8; attempt++) {
            boolean main = r.nextInt(5) < 2 || p.streets.size() < 2;
            Plot c = main ? extendMain(level, h, p, r) : branch(level, h, p, r);
            if (c == null) c = main ? branch(level, h, p, r) : extendMain(level, h, p, r);
            if (c == null) return null;
            lay(level, h, p, c);
            Result b = buildOnALot(level, h, p, r);
            if (b != null) return new Result(b.what(), true, c.street().main);
        }
        return null;
    }

    /** A stretch of street that'll go, and the lots along it. */
    record Plot(Street street, List<Lot> lots) {
    }

    /** Pave it and take it into the plan, with its lots. */
    static void lay(ServerLevel level, Holding h, Plan p, Plot c) {
        // the town grows over its fields: those in the way are given up, and the farmers break new ground further out
        List<BoundingBox> taken = new ArrayList<>();
        taken.add(c.street().box(1));
        for (Lot l : c.lots()) taken.add(l.box(1));
        for (Holding.FiefBuilding f : new ArrayList<>(h.fief)) {
            if (!f.piece().equals("field") || inBuiltLot(p, f)) continue;
            for (BoundingBox t : taken) {
                if (flat(f.box()).intersects(t)) {
                    Fields.reclaim(level, h, f);
                    break;
                }
            }
        }
        pave(level, h, p, c.street());
        p.streets.add(c.street());
        for (Lot l : c.lots()) {
            l.street = p.streets.size() - 1;
            p.lots.add(l);
        }
    }

    /** A farmhouse's own garden: it goes with its house. */
    static boolean inBuiltLot(Plan p, Holding.FiefBuilding f) {
        BlockPos mid = f.box().getCenter();
        for (Lot l : p.lots) if (l.built && l.box(0).isInside(mid.atY(0))) return true;
        return false;
    }

    /** A field the town may grow over (not a farmhouse's garden). */
    static boolean reclaimable(Plan p, Holding.FiefBuilding f) {
        return f.piece().equals("field") && !inBuiltLot(p, f);
    }

    /** The land under a field's crops: its farmland, channel or verge. */
    static int fieldGround(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        for (int g = 0; g < 8; g++, y--) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (s.isAir() || s.is(BlockTags.CROPS) || s.is(BlockTags.FENCES) || s.is(BlockTags.FENCE_GATES) || s.is(Blocks.HAY_BLOCK)
                    || s.is(Blocks.CARVED_PUMPKIN) || s.canBeReplaced() && s.getFluidState().isEmpty()) continue;
            return y;
        }
        return y;
    }

    // ------------------------------------------------------------ the streets

    /** The high street starts outside the gate and runs out away from the walls (another side if the gate's ground won't take it). */
    static boolean seed(ServerLevel level, Holding h, Plan p) {
        BlockPos c = h.center();
        boolean gated = !h.gatePos.equals(BlockPos.ZERO);
        Direction out = Direction.SOUTH;
        if (gated) {
            Direction g = Direction.getNearest(h.gatePos.getX() - c.getX(), 0, h.gatePos.getZ() - c.getZ());
            if (g.getAxis() != Direction.Axis.Y) out = g;
        }
        BoundingBox clear = h.box.inflatedBy(FORT);
        for (Direction d : new Direction[]{out, out.getClockWise(), out.getCounterClockWise(), out.getOpposite()}) {
            boolean atGate = d == out && gated;
            BlockPos start = atGate ? h.gatePos : c;
            int guard = 0;
            while (clear.isInside(start.atY(clear.minY())) && guard++ < 300) start = start.relative(d);
            for (int shift : new int[]{0, 3, 6, 10}) {
                for (int len : new int[]{MAIN_LEN + 4, MAIN_LEN, 8}) {
                    Street s = new Street();
                    s.a = start.relative(d, shift);
                    s.dir = d;
                    s.len = len;
                    s.width = MAIN_W;
                    s.main = true;
                    if (!level.isLoaded(s.a) || !level.isLoaded(s.end())) return false;
                    if (!ok(level, h, p, s)) continue;
                    // from the gate (or the walls) to where the street starts, the road's laid too
                    Street approach = new Street();
                    approach.a = atGate ? h.gatePos.relative(d) : start.relative(d, -(FORT - CLEAR));
                    approach.dir = d;
                    approach.width = MAIN_W;
                    approach.len = (int) Math.round(Math.sqrt(approach.a.atY(0).distSqr(s.a.atY(0))));
                    if (approach.len > 0) pave(level, h, p, approach);
                    lay(level, h, p, new Plot(s, lotsFor(h, p, s, level.random)));
                    return true;
                }
            }
        }
        return false;
    }

    /** The high street onward from its far end: straight on mostly, sometimes turning. */
    @Nullable
    static Plot extendMain(ServerLevel level, Holding h, Plan p, RandomSource r) {
        Street last = null;
        for (Street s : p.streets) if (s.main) last = s;
        if (last == null) return null;
        List<Direction> ways = new ArrayList<>(List.of(last.dir, last.dir, last.dir, last.dir.getClockWise(), last.dir.getCounterClockWise()));
        java.util.Collections.shuffle(ways, new java.util.Random(r.nextLong()));
        for (Direction d : new java.util.LinkedHashSet<>(ways)) {
            for (int len : new int[]{MAIN_LEN - 2 + r.nextInt(8), 10}) {
                Street s = new Street();
                s.dir = d;
                s.a = d == last.dir ? last.end().relative(d) : last.end().relative(d, last.edge(d));
                s.width = MAIN_W;
                s.main = true;
                s.len = len;
                if (!ok(level, h, p, s)) continue;
                List<Lot> lots = lotsFor(h, p, s, r);
                if (!lots.isEmpty()) return new Plot(s, lots);
            }
        }
        return null;
    }

    /** A lane off any street, square to it, from wherever there's a gap between its lots, long enough to reach past them to room of its own. */
    @Nullable
    static Plot branch(ServerLevel level, Holding h, Plan p, RandomSource r) {
        if (p.streets.isEmpty()) return null;
        List<int[]> tries = new ArrayList<>();
        for (int si = 0; si < p.streets.size(); si++) {
            Street from = p.streets.get(si);
            for (int at = 1; at < from.len - 1; at++) {
                tries.add(new int[]{si, at, 0});
                tries.add(new int[]{si, at, 1});
            }
        }
        java.util.Collections.shuffle(tries, new java.util.Random(r.nextLong()));
        int checked = 0;
        for (int[] t : tries) {
            if (checked++ > 200) break;
            Street from = p.streets.get(t[0]);
            Direction d = t[2] == 0 ? from.dir.getClockWise() : from.dir.getCounterClockWise();
            int width = from.main && r.nextInt(3) == 0 ? MAIN_W : LANE_W;
            for (int len : new int[]{LANE_LEN + 10 + r.nextInt(8), LANE_LEN + 4, LANE_LEN - 2}) {
                Street s = new Street();
                s.dir = d;
                s.a = from.a.relative(from.dir, t[1]).relative(d, from.edge(d));
                s.width = width;
                s.main = false;
                s.len = len;
                if (!ok(level, h, p, s)) continue;
                List<Lot> lots = lotsFor(h, p, s, r);
                if (!lots.isEmpty()) return new Plot(s, lots);
            }
        }
        return null;
    }

    /** Can a stretch go here: loaded, within the fief, clear of the castle, the other streets, the lots and every building, on dry land with no step of more than one. */
    static boolean ok(ServerLevel level, Holding h, Plan p, Street s) {
        BlockPos end = s.end();
        if (!level.isLoaded(s.a) || !level.isLoaded(end)) return false;
        BlockPos c = h.center();
        double reach = Fief.REACH + Math.max(h.box.getXSpan(), h.box.getZSpan()) / 2.0 - 8;
        if (Math.sqrt(end.atY(0).distSqr(c.atY(0))) > reach) return false;
        BoundingBox mine = s.box(0);
        if (h.box.inflatedBy(FORT).intersects(mine)) return false;
        for (Street o : p.streets) if (o.box(0).intersects(mine)) return false;
        for (Lot l : p.lots) if (l.box(0).intersects(mine)) return false;
        for (Holding.FiefBuilding b : h.fief) if (!reclaimable(p, b) && flat(b.box().inflatedBy(1)).intersects(mine)) return false;
        int prev = Integer.MIN_VALUE;
        for (int i = 0; i < s.len; i++) {
            BlockPos q = s.a.relative(s.dir, i);
            boolean field = false;
            for (Holding.FiefBuilding b : h.fief) if (reclaimable(p, b) && flat(b.box()).isInside(q.atY(0))) field = true;
            int y = field ? fieldGround(level, q.getX(), q.getZ()) : surface(level, q.getX(), q.getZ());
            if (y == Integer.MIN_VALUE) return false;
            BlockState top = level.getBlockState(new BlockPos(q.getX(), y, q.getZ()));
            if (!field && !natural(top) && !road(top)) return false;
            if (prev != Integer.MIN_VALUE && Math.abs(y - prev) > 1) return false;
            prev = y;
        }
        return true;
    }

    static BoundingBox flat(BoundingBox b) {
        return new BoundingBox(b.minX(), -64, b.minZ(), b.maxX(), 320, b.maxZ());
    }

    /**
     * The y of the land at (x, z): its top block, under any tree, plant or snow. MIN_VALUE where water comes first, or
     * there's nothing to stand on for a long way down.
     */
    static int surface(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        for (int guard = 0; guard < 32; guard++, y--) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (!s.getFluidState().isEmpty()) return Integer.MIN_VALUE;
            if (growing(s)) continue;
            return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Trees, plants, snow: what stands on the land rather than being it. */
    static boolean growing(BlockState s) {
        return s.isAir() || s.canBeReplaced() || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(Blocks.SNOW) || s.is(Blocks.BAMBOO)
                || s.is(Blocks.CACTUS) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.BROWN_MUSHROOM_BLOCK)
                || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON)
                || s.is(Blocks.BEE_NEST) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS);
    }

    /** The land itself: earth, sand and stone. */
    static boolean natural(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.STONE)
                || s.is(Blocks.ANDESITE) || s.is(Blocks.DIORITE) || s.is(Blocks.GRANITE) || s.is(Blocks.CLAY) || s.is(Blocks.DIRT_PATH)
                || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.CALCITE)
                || s.is(Blocks.TUFF) || s.is(Blocks.DEEPSLATE) || s.is(Blocks.MUD) || s.is(Blocks.PACKED_MUD) || s.is(Blocks.PACKED_ICE);
    }

    /** A made road: a street can cross it, nothing's built on it. */
    static boolean road(BlockState s) {
        return s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE) || s.is(Blocks.STONE_BRICKS) || s.is(Blocks.SMOOTH_SANDSTONE)
                || s.is(Blocks.POLISHED_ANDESITE) || s.is(Blocks.SMOOTH_STONE) || s.is(Blocks.CUT_SANDSTONE);
    }

    // ------------------------------------------------------------ paving

    /** The street's surface by what the place is: a beaten path for a hamlet, gravel for a village, cobbles for a town. */
    static BlockState paving(Holding h, Plan p, RandomSource r, boolean middle) {
        String theme = h.theme == null ? "north" : h.theme;
        int size = p.houses;
        boolean town = h.isTown() || h.isCity() || h.tier >= 3 || size >= 14;
        boolean village = town || h.tier >= 2 || size >= 6;
        if (theme.equals("dorne")) {
            return town ? (r.nextInt(4) == 0 ? Blocks.SANDSTONE : Blocks.SMOOTH_SANDSTONE).defaultBlockState()
                    : (r.nextInt(3) == 0 ? Blocks.SAND : Blocks.DIRT_PATH).defaultBlockState();
        }
        if (town) {
            int k = r.nextInt(10);
            return (k < 5 ? Blocks.COBBLESTONE : k < 8 ? Blocks.STONE_BRICKS : k < 9 ? Blocks.MOSSY_COBBLESTONE : Blocks.ANDESITE).defaultBlockState();
        }
        if (village) return (middle && r.nextInt(3) > 0 ? Blocks.GRAVEL : r.nextInt(5) == 0 ? Blocks.COARSE_DIRT : Blocks.DIRT_PATH).defaultBlockState();
        return (r.nextInt(6) == 0 ? Blocks.COARSE_DIRT : Blocks.DIRT_PATH).defaultBlockState();
    }

    /** Lay it: the land's top block in each cell becomes paving (a road already there is left), and trees and plants over it are cleared. */
    static void pave(ServerLevel level, Holding h, Plan p, Street s) {
        RandomSource r = level.random;
        for (BlockPos col : s.cells()) {
            if (!level.isLoaded(col)) continue;
            int y = surface(level, col.getX(), col.getZ());
            if (y == Integer.MIN_VALUE) continue;
            BlockPos top = new BlockPos(col.getX(), y, col.getZ());
            if (!natural(level.getBlockState(top))) continue;
            for (int up = 1; up <= 12; up++) {
                BlockPos a = top.above(up);
                BlockState above = level.getBlockState(a);
                if (above.isAir()) {
                    if (up > 3) break;
                    continue;
                }
                if (!growing(above) || !above.getFluidState().isEmpty()) break;
                level.setBlock(a, Blocks.AIR.defaultBlockState(), 2);
            }
            boolean middle = s.dir.getAxis() == Direction.Axis.X ? col.getZ() == s.a.getZ() : col.getX() == s.a.getX();
            level.setBlock(top, paving(h, p, r, middle), 2);
        }
        // a lantern on a post at the start of each stretch of the high street
        if (s.main && s.len >= 6) {
            BlockPos q = s.a.relative(s.dir, 1).relative(s.dir.getClockWise(), s.right() + 1);
            if (!level.isLoaded(q)) return;
            int y = surface(level, q.getX(), q.getZ());
            if (y == Integer.MIN_VALUE) return;
            BlockPos base = new BlockPos(q.getX(), y + 1, q.getZ());
            if (growing(level.getBlockState(base)) && growing(level.getBlockState(base.above())) && level.getBlockState(base.above(2)).isAir()) {
                level.setBlock(base, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
                level.setBlock(base.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
                level.setBlock(base.above(2), Blocks.LANTERN.defaultBlockState(), 3);
            }
        }
    }

    // ------------------------------------------------------------ lots

    /**
     * Lots down both sides of a stretch, a verge back from it: each as wide and deep as the room allows, a two-block gap
     * between, and now and then three blocks left for a lane. Where one won't fit, the next is tried a block further on.
     */
    static List<Lot> lotsFor(Holding h, Plan p, Street s, RandomSource r) {
        List<Lot> out = new ArrayList<>();
        Direction side = s.dir.getClockWise();
        for (int sgn : new int[]{1, -1}) {
            Direction toward = sgn > 0 ? side : side.getOpposite();
            int off = (sgn > 0 ? s.right() : s.left()) + (s.main ? 3 : 2);       // the street's half and a verge, wider on the high street
            int t = 0, since = 0;
            while (t <= s.len - 5) {
                Lot got = null;
                int frontage = Math.min(s.len - t, 5 + r.nextInt(4)), depth = 6 + r.nextInt(5);
                for (int[] fd : new int[][]{{frontage, depth}, {frontage, 6}, {5, depth}, {5, 6}}) {
                    if (fd[0] < 5) continue;
                    Lot l = lot(s, toward, off, t, fd[0], fd[1]);
                    if (fits(h, p, l, out)) {
                        got = l;
                        break;
                    }
                }
                if (got == null) {
                    t++;
                    continue;
                }
                out.add(got);
                t += (got.front.getAxis() == Direction.Axis.Z ? got.w : got.d) + 3;   // room between for both roofs' eaves and a way through
                // every couple of lots, a gap a lane could come through
                if (++since >= 2 + r.nextInt(2)) {
                    t += 3;
                    since = 0;
                }
            }
        }
        return out;
    }

    private static Lot lot(Street s, Direction toward, int off, int t, int frontage, int depth) {
        BlockPos c1 = s.a.relative(s.dir, t).relative(toward, off);
        BlockPos c2 = s.a.relative(s.dir, t + frontage - 1).relative(toward, off + depth - 1);
        Lot l = new Lot();
        l.x0 = Math.min(c1.getX(), c2.getX());
        l.z0 = Math.min(c1.getZ(), c2.getZ());
        l.w = Math.abs(c1.getX() - c2.getX()) + 1;
        l.d = Math.abs(c1.getZ() - c2.getZ()) + 1;
        l.front = toward.getOpposite();
        return l;
    }

    /** Clear of the castle, the streets, the other lots (and those just laid out) and every building, and within the fief. */
    static boolean fits(Holding h, Plan p, Lot l, List<Lot> fresh) {
        BoundingBox b = l.box(0);
        for (Lot o : fresh) if (o.box(0).intersects(b)) return false;
        if (h.box.inflatedBy(FORT + 1).intersects(b)) return false;
        for (Street s : p.streets) if (s.box(0).intersects(b)) return false;
        for (Lot o : p.lots) if (o != l && o.box(0).intersects(b)) return false;
        for (Holding.FiefBuilding f : h.fief) if (!reclaimable(p, f) && flat(f.box().inflatedBy(1)).intersects(b)) return false;
        double reach = Fief.REACH + Math.max(h.box.getXSpan(), h.box.getZSpan()) / 2.0;
        double dx = l.x0 + l.w / 2.0 - h.center().getX(), dz = l.z0 + l.d / 2.0 - h.center().getZ();
        return Math.sqrt(dx * dx + dz * dz) <= reach;
    }

    /** What the place needs next: a bakery, a smithy, an inn, stalls on the high street as it grows, and homes (many of them farms) between. */
    static HouseGen.Kind next(Holding h, Plan p, RandomSource r, Lot l) {
        int bakeries = count(h, "bakery"), smithies = count(h, "smithy"), inns = count(h, "inn"), stalls = count(h, "stall");
        if (p.houses >= 3 && bakeries == 0) return HouseGen.Kind.BAKERY;
        if (p.houses >= 4 && count(h, "stable") == 0 && Math.min(l.w, l.d) >= 7) return HouseGen.Kind.STABLE;
        if (p.houses >= 5 && smithies == 0) return HouseGen.Kind.SMITHY;
        if (p.houses >= 8 && inns == 0 && Math.min(l.w, l.d) >= 7) return HouseGen.Kind.INN;
        if (p.houses >= 6 && stalls < p.houses / 6 && l.street == 0 && r.nextBoolean()) return HouseGen.Kind.STALL;
        if (p.houses >= 16 && bakeries < 2 && r.nextInt(6) == 0) return HouseGen.Kind.BAKERY;
        if (p.houses >= 24 && smithies < 2 && r.nextInt(8) == 0) return HouseGen.Kind.SMITHY;
        if (l.depth() >= 9 && r.nextInt(3) > 0) return HouseGen.Kind.FARMHOUSE;
        return r.nextInt(4) == 0 ? HouseGen.Kind.WORKSHOP : HouseGen.Kind.HOUSE;
    }

    public static int count(Holding h, String piece) {
        int n = 0;
        for (Holding.FiefBuilding b : h.fief) if (b.piece().equals(piece)) n++;
        return n;
    }

    /** Build on the free lot nearest the gate that the land allows. */
    @Nullable
    static Result buildOnALot(ServerLevel level, Holding h, Plan p, RandomSource r) {
        BlockPos from = (h.gatePos.equals(BlockPos.ZERO) ? h.center() : h.gatePos).atY(0);
        List<Lot> free = new ArrayList<>();
        for (Lot l : p.lots) if (!l.built && l.tries < 3) free.add(l);
        free.sort(Comparator.comparingDouble(l -> from.distSqr(new BlockPos(l.x0 + l.w / 2, 0, l.z0 + l.d / 2))));
        for (Lot l : free) {
            if (!level.isLoaded(new BlockPos(l.x0 - 1, 64, l.z0 - 1)) || !level.isLoaded(new BlockPos(l.x0 + l.w, 64, l.z0 + l.d))) continue;
            Integer y = floorOf(level, l);
            if (y == null) {
                l.tries++;
                continue;
            }
            HouseGen.Kind kind = next(h, p, r, l);
            clear(level, l, y);
            HouseGen.Built b = HouseGen.build(level, new HouseGen.Lot(l.x0, l.z0, l.w, l.d, y, l.front), kind, h.theme, r);
            l.built = true;
            h.fief.add(new Holding.FiefBuilding(kind.name().toLowerCase(Locale.ROOT), b.box()));
            if (b.garden() != null) h.fief.add(new Holding.FiefBuilding("field", b.garden()));
            people(level, kind, b, r);
            return new Result(article(kind), false, false);
        }
        return null;
    }

    static String article(HouseGen.Kind k) {
        return switch (k) {
            case FARMHOUSE -> "a farmhouse with its garden";
            case BAKERY -> "a bakery";
            case SMITHY -> "a smithy";
            case INN -> "an inn";
            case STALL -> "a market stall";
            case WORKSHOP -> "a workshop";
            case STABLE -> "a stable, with horses in its stalls";
            default -> "a house";
        };
    }

    /**
     * The floor of a building on this lot: the ground at the middle of its street side. Null if the lot's ground rises
     * or falls more than three blocks across it, is wet, or has a road through it.
     */
    @Nullable
    static Integer floorOf(ServerLevel level, Lot l) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, paths = 0, roads = 0;
        for (int x = l.x0; x < l.x0 + l.w; x += 2) {
            for (int z = l.z0; z < l.z0 + l.d; z += 2) {
                int y = surface(level, x, z);
                if (y == Integer.MIN_VALUE) return null;
                BlockState s = level.getBlockState(new BlockPos(x, y, z));
                if (s.is(Blocks.DIRT_PATH)) paths++;
                else if (road(s)) roads++;
                else if (!natural(s)) return null;
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        }
        if (max - min > 3 || roads >= 2 || paths >= 6) return null;
        int fx = l.front == Direction.WEST ? l.x0 : l.front == Direction.EAST ? l.x0 + l.w - 1 : l.x0 + l.w / 2;
        int fz = l.front == Direction.NORTH ? l.z0 : l.front == Direction.SOUTH ? l.z0 + l.d - 1 : l.z0 + l.d / 2;
        int y = surface(level, fx, fz);
        return y == Integer.MIN_VALUE ? null : y;
    }

    /** The lot cleared down to its floor (trees, earth) and the air over it free for the roof; round its edge, only what grows and the land. */
    static void clear(ServerLevel level, Lot l, int y) {
        for (int x = l.x0 - 1; x <= l.x0 + l.w; x++) {
            for (int z = l.z0 - 1; z <= l.z0 + l.d; z++) {
                boolean rim = x < l.x0 || x >= l.x0 + l.w || z < l.z0 || z >= l.z0 + l.d;
                for (int yy = y + 1; yy <= y + 18; yy++) {
                    BlockPos q = new BlockPos(x, yy, z);
                    BlockState s = level.getBlockState(q);
                    if (s.isAir()) continue;
                    if (rim && !(growing(s) || natural(s))) continue;
                    level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /** Folk for it: one a bed (at least two in a home), the first in its trade. */
    static void people(ServerLevel level, HouseGen.Kind kind, HouseGen.Built b, RandomSource r) {
        String prof = switch (kind) {
            case FARMHOUSE -> "farmer";
            case SMITHY -> r.nextBoolean() ? "armorer" : "weaponsmith";
            case HOUSE -> r.nextInt(3) == 0 ? "farmer" : null;
            default -> null;
        };
        VillagerProfession job = prof == null ? null : ForgeRegistries.VILLAGER_PROFESSIONS.getValue(new ResourceLocation("minecraft", prof));
        int souls = kind == HouseGen.Kind.STALL || kind == HouseGen.Kind.STABLE ? 1
                : Math.max(kind == HouseGen.Kind.SMITHY || kind == HouseGen.Kind.BAKERY ? 1 : 2, b.beds().size());
        for (BlockPos stall : b.stalls()) {
            net.minecraft.world.entity.animal.horse.Horse horse = net.minecraft.world.entity.EntityType.HORSE.create(level);
            if (horse == null) continue;
            horse.moveTo(stall.getX() + 0.5, stall.getY(), stall.getZ() + 0.5, r.nextFloat() * 360F, 0);
            horse.finalizeSpawn(level, level.getCurrentDifficultyAt(stall), net.minecraft.world.entity.MobSpawnType.STRUCTURE, null, null);
            horse.setPersistenceRequired();
            level.addFreshEntity(horse);
        }
        for (int i = 0; i < souls; i++) {
            BlockPos at = i < b.beds().size() ? b.beds().get(i) : b.door();
            BlockPos stand = Garrisons.standable(level, at);
            Fief.spawnVillager(level, stand != null ? stand : b.door(), i == 0 ? job : null);
        }
    }

    // ------------------------------------------------------------ the old buildings

    /** The street cell nearest a spot, or null with no streets yet. */
    @Nullable
    static BlockPos nearestStreet(Plan p, BlockPos at) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (Street s : p.streets) {
            for (BlockPos q : s.cells()) {
                double d = q.distSqr(at.atY(0));
                if (d < bd) {
                    bd = d;
                    best = q;
                }
            }
        }
        return best;
    }

    /** One building that stood before the streets, tied in: a footpath from its nearest side to the nearest street. True if one was dealt with. */
    static boolean link(ServerLevel level, Holding h, Plan p) {
        if (p.streets.isEmpty()) return false;
        for (Holding.FiefBuilding b : h.fief) {
            String k = b.piece();
            if (k.equals("field") || k.equals("orchard") || isGenerated(k)) continue;
            BoundingBox box = b.box();
            long key = new BlockPos(box.minX(), box.minY(), box.minZ()).asLong();
            if (p.linked.contains(key)) continue;
            if (!level.isLoaded(new BlockPos(box.minX(), 64, box.minZ())) || !level.isLoaded(new BlockPos(box.maxX(), 64, box.maxZ()))) continue;
            p.linked.add(key);
            BlockPos mid = new BlockPos((box.minX() + box.maxX()) / 2, 0, (box.minZ() + box.maxZ()) / 2);
            BlockPos best = null;
            double bd = Double.MAX_VALUE;
            for (Street s : p.streets) {
                for (BlockPos q : s.cells()) {
                    double d = q.distSqr(mid);
                    if (d < bd) {
                        bd = d;
                        best = q;
                    }
                }
            }
            if (best == null || bd > 70.0 * 70.0) return true;
            int ex = Math.max(box.minX() - 1, Math.min(box.maxX() + 1, best.getX()));
            int ez = Math.max(box.minZ() - 1, Math.min(box.maxZ() + 1, best.getZ()));
            // from just outside the wall: if that's still inside the footprint, step out the nearer side
            if (ex >= box.minX() && ex <= box.maxX() && ez >= box.minZ() && ez <= box.maxZ()) ez = best.getZ() < mid.getZ() ? box.minZ() - 1 : box.maxZ() + 1;
            lane(level, h, p, new BlockPos(ex, 0, ez), best);
            return true;
        }
        return false;
    }

    /** A footpath a block wide, along x then z, laid only on open land (and not across a lot waiting to be built on). */
    static void lane(ServerLevel level, Holding h, Plan p, BlockPos from, BlockPos to) {
        int x = from.getX(), z = from.getZ();
        int sx = Integer.signum(to.getX() - x), sz = Integer.signum(to.getZ() - z);
        for (int guard = 0; guard < 200; guard++) {
            path(level, h, p, x, z);
            if (x == to.getX() && z == to.getZ()) return;
            if (x != to.getX()) x += sx;
            else z += sz;
        }
    }

    private static void path(ServerLevel level, Holding h, Plan p, int x, int z) {
        BlockPos col = new BlockPos(x, 64, z);
        if (!level.isLoaded(col)) return;
        for (Lot l : p.lots) if (!l.built && l.box(0).isInside(col)) return;
        for (Holding.FiefBuilding b : h.fief) if (flat(b.box()).isInside(col)) return;
        int y = surface(level, x, z);
        if (y == Integer.MIN_VALUE) return;
        BlockPos top = new BlockPos(x, y, z);
        BlockState s = level.getBlockState(top);
        if (!(s.is(BlockTags.DIRT) && !s.is(Blocks.DIRT_PATH) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL))) return;
        BlockState above = level.getBlockState(top.above());
        if (!above.isAir()) {
            if (!growing(above) || !above.getFluidState().isEmpty()) return;
            level.setBlock(top.above(), Blocks.AIR.defaultBlockState(), 2);
        }
        level.setBlock(top, Blocks.DIRT_PATH.defaultBlockState(), 2);
    }

    private Town() {
    }
}
