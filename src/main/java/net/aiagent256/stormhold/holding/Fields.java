package net.aiagent256.stormhold.holding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The fief's working land, laid out to match the people who work it. Every farmer needs a share of farmland for a
 * full day's yield, and every two butchers a paddock of beasts; at the census, hands without land make half. While
 * someone's near, the people break new fields (wheat, with strips of roots, watered by channels, fenced, a scarecrow
 * in the corner) and fence new paddocks (cows one side, pigs the other, a trough and a shelter in each) on open
 * ground around the town, each with a footpath to the nearest street. The fields are cut at dawn and the paddocks
 * bred and culled (see {@link Produce}), so what they give is real too.
 */
public final class Fields {
    /** Farmland one farmer works for a full day's yield. */
    public static final int FARMLAND_PER_FARMER = 40;
    /** Butchers one paddock keeps in beasts. */
    public static final int BUTCHERS_PER_PEN = 2;
    /** Most fields and paddocks the people will lay out for one castle. */
    public static final int MOST_FIELDS = 20, MOST_PENS = 6;

    /** Farmland in a field's box, near enough: its inside, less the water channels. */
    static int farmland(BoundingBox b) {
        int w = b.getXSpan() - 2, d = b.getZSpan() - 2;
        return w <= 0 || d <= 0 ? 0 : w * d * 4 / 5;
    }

    /** How many farmers the fields have work for. */
    public static int farmerRoom(Holding h) {
        int f = 0;
        for (Holding.FiefBuilding b : h.fief) if (b.piece().equals("field")) f += farmland(b.box());
        return f / FARMLAND_PER_FARMER;
    }

    /** How many butchers the paddocks keep in beasts. */
    public static int butcherRoom(Holding h) {
        return Town.count(h, "pasture") * BUTCHERS_PER_PEN;
    }

    /** Hands at full work: those the land has room for, and the rest at half. */
    public static double worked(Holding h, String job, int n) {
        int room = switch (job) {
            case "farmer" -> farmerRoom(h);
            case "butcher" -> butcherRoom(h);
            default -> Integer.MAX_VALUE;
        };
        return n <= room ? n : room + (n - room) / 2.0;
    }

    /** For the census: who's short of land, if anyone. */
    public static String shortfall(Holding h, Map<String, Integer> jobs) {
        List<String> out = new ArrayList<>();
        int farmers = jobs.getOrDefault("farmer", 0), butchers = jobs.getOrDefault("butcher", 0);
        int fr = farmerRoom(h), br = butcherRoom(h);
        if (farmers > fr) out.add((farmers - fr) + " farmer" + (farmers - fr > 1 ? "s" : "") + " without a field");
        if (butchers > br) out.add((butchers - br) + " butcher" + (butchers - br > 1 ? "s" : "") + " without beasts");
        return out.isEmpty() ? "" : " " + String.join(", ", out) + " (half work till the land's laid out; it's done while you're there).";
    }

    static boolean wantsField(Holding h) {
        return h.fiefJobs.getOrDefault("farmer", 0) > farmerRoom(h) && fields(h) < MOST_FIELDS;
    }

    static boolean wantsPen(Holding h) {
        return h.fiefJobs.getOrDefault("butcher", 0) > butcherRoom(h) && Town.count(h, "pasture") < MOST_PENS;
    }

    private static int fields(Holding h) {
        int n = 0;
        for (Holding.FiefBuilding b : h.fief) if (b.piece().equals("field") && b.box().getXSpan() >= 9 && b.box().getZSpan() >= 9) n++;
        return n;
    }

    /** While someone's near: a field or a paddock where the people are short of one. True if something was laid out. */
    static boolean tick(ServerLevel level, HoldingManager mgr, Holding h, Town.Plan p) {
        if (p.landWait > 0) {
            p.landWait--;
            return false;
        }
        boolean field = wantsField(h), pen = !field && wantsPen(h);
        if (!field && !pen) return false;
        RandomSource r = level.random;
        int w = field ? 11 + r.nextInt(5) : 12 + r.nextInt(3), d = field ? 9 + r.nextInt(5) : 9 + r.nextInt(2);
        if (r.nextBoolean()) {
            int t = w;
            w = d;
            d = t;
        }
        BoundingBox at = site(level, h, p, w, d, r, !field);
        if (at == null) {
            p.landWait = 30;
            return false;
        }
        BlockPos mid = at.getCenter();
        BlockPos town = Town.nearestStreet(p, mid);
        BlockPos toward = town != null ? town : h.gatePos.equals(BlockPos.ZERO) ? h.center() : h.gatePos;
        Direction face = Direction.getNearest(toward.getX() - mid.getX(), 0, toward.getZ() - mid.getZ());
        if (face.getAxis() == Direction.Axis.Y) face = Direction.NORTH;
        BlockPos gate;
        if (field) {
            gate = field(level, at, face, h.theme, r);
            h.fief.add(new Holding.FiefBuilding("field", new BoundingBox(at.minX(), at.minY() - 1, at.minZ(), at.maxX(), at.minY() + 3, at.maxZ())));
        } else {
            gate = paddock(level, at, face, h.theme, r);
            h.fief.add(new Holding.FiefBuilding("pasture", new BoundingBox(at.minX(), at.minY() - 1, at.minZ(), at.maxX(), at.minY() + 4, at.maxZ())));
        }
        if (town != null) Town.lane(level, h, p, gate.relative(face), town);
        p.landWait = 3;
        mgr.setDirty();
        if (h.isPlayerOwned()) HoldingEvents.tell(level, h.ownerPlayer(), HoldingEvents.gold(field
                ? "The farmers of " + h.name + " have broken a new field, " + w + " by " + d + "."
                : "The butchers of " + h.name + " have fenced a paddock and brought in cows and pigs."));
        return true;
    }

    /** A field the town has grown over: its crops, fences and scarecrow taken up, the farmland and channels turned back to grass, and it's gone from the fief. */
    static void reclaim(ServerLevel level, Holding h, Holding.FiefBuilding f) {
        BoundingBox b = f.box();
        int y0 = b.minY() - 1, y1 = b.maxY() + 3;
        // the channels first, while there's farmland beside them to know them by
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int y = y0; y <= y1; y++) {
                    BlockPos q = new BlockPos(x, y, z);
                    if (!level.isLoaded(q) || !level.getBlockState(q).is(Blocks.WATER)) continue;
                    boolean channel = false;
                    for (Direction d : Direction.Plane.HORIZONTAL) if (level.getBlockState(q.relative(d)).is(Blocks.FARMLAND)) channel = true;
                    if (channel) level.setBlock(q, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                }
            }
        }
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int y = y1; y >= y0; y--) {
                    BlockPos q = new BlockPos(x, y, z);
                    if (!level.isLoaded(q)) continue;
                    BlockState s = level.getBlockState(q);
                    if (s.is(net.minecraft.tags.BlockTags.CROPS) || s.is(net.minecraft.tags.BlockTags.FENCES) || s.is(net.minecraft.tags.BlockTags.FENCE_GATES)
                            || s.is(Blocks.HAY_BLOCK) || s.is(Blocks.CARVED_PUMPKIN) || s.is(Blocks.WATER_CAULDRON)) level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
                    else if (s.is(Blocks.FARMLAND)) level.setBlock(q, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                }
            }
        }
        h.fief.remove(f);
    }

    /** Open, dry, fairly level ground for a w by d plot near the town, clear of everything; its box at the land's height, or null. */
    @Nullable
    static BoundingBox site(ServerLevel level, Holding h, Town.Plan p, int w, int d, RandomSource r, boolean pen) {
        BlockPos c = h.center();
        int half = Math.max(h.box.getXSpan(), h.box.getZSpan()) / 2;
        for (int attempt = 0; attempt < 40; attempt++) {
            int ax, az;
            if (!p.streets.isEmpty() && (attempt < 30 || r.nextInt(3) > 0)) {
                // close round the town: out past the lots of some street
                Town.Street s = p.streets.get(r.nextInt(p.streets.size()));
                BlockPos q = s.a.relative(s.dir, r.nextInt(s.len));
                double ang = r.nextDouble() * Math.PI * 2;
                int dist = (pen ? 24 : 12) + Math.max(w, d) / 2 + r.nextInt(8 + attempt / 2);
                ax = q.getX() + (int) (Math.cos(ang) * dist);
                az = q.getZ() + (int) (Math.sin(ang) * dist);
            } else {
                double ang = r.nextDouble() * Math.PI * 2;
                int dist = half + 18 + r.nextInt(Math.max(1, Fief.REACH - 30));
                ax = c.getX() + (int) (Math.cos(ang) * dist);
                az = c.getZ() + (int) (Math.sin(ang) * dist);
            }
            int x0 = ax - w / 2, z0 = az - d / 2, x1 = x0 + w - 1, z1 = z0 + d - 1;
            BoundingBox foot = new BoundingBox(x0, -64, z0, x1, 320, z1);
            double dx = ax - c.getX(), dz = az - c.getZ();
            if (Math.sqrt(dx * dx + dz * dz) > Fief.REACH + half - 4) continue;
            if (!level.isLoaded(new BlockPos(x0 - 1, 64, z0 - 1)) || !level.isLoaded(new BlockPos(x1 + 1, 64, z1 + 1))
                    || !level.isLoaded(new BlockPos(x0 - 1, 64, z1 + 1)) || !level.isLoaded(new BlockPos(x1 + 1, 64, z0 - 1))) continue;
            if (h.box.inflatedBy(Town.FORT + 2).intersects(foot)) continue;
            if (Town.blocks(h, foot.inflatedBy(1))) continue;
            // a paddock stays out of the way the high street will grow (a field the town just grows over)
            if (pen && ahead(p, foot)) continue;
            boolean clear = true;
            for (Holding.FiefBuilding b : h.fief) {
                if (Town.flat(b.box().inflatedBy(2)).intersects(foot)) {
                    clear = false;
                    break;
                }
            }
            if (!clear) continue;
            List<Integer> ys = new ArrayList<>();
            for (int x = x0; x <= x1 && clear; x += 2) {
                for (int z = z0; z <= z1; z += 2) {
                    int y = Town.surface(level, x, z);
                    if (y == Integer.MIN_VALUE || !Town.natural(level.getBlockState(new BlockPos(x, y, z)))
                            || level.getBlockState(new BlockPos(x, y, z)).is(Blocks.DIRT_PATH)) {
                        clear = false;
                        break;
                    }
                    ys.add(y);
                }
            }
            if (!clear || ys.isEmpty()) continue;
            ys.sort(Integer::compare);
            if (ys.get(ys.size() - 1) - ys.get(0) > 2) continue;
            int y = ys.get(ys.size() / 2);
            return new BoundingBox(x0, y, z0, x1, y + 1, z1);
        }
        return null;
    }

    /** Within thirty blocks ahead of where the high street ends, and fifteen either side of its line. */
    static boolean ahead(Town.Plan p, BoundingBox foot) {
        Town.Street last = null;
        for (Town.Street s : p.streets) if (s.main) last = s;
        if (last == null) return false;
        BlockPos e = last.end(), f = e.relative(last.dir, 30);
        Direction side = last.dir.getClockWise();
        BlockPos a = e.relative(side, -15), b = f.relative(side, 15);
        BoundingBox lane = new BoundingBox(Math.min(a.getX(), b.getX()), -64, Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), 320,
                Math.max(a.getZ(), b.getZ()));
        return lane.intersects(foot);
    }

    /** The ground of a plot made level at its height: cleared above, filled below. */
    private static void flatten(ServerLevel level, BoundingBox b, Block top) {
        int y = b.minY();
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int yy = y + 1; yy <= y + 12; yy++) {
                    BlockPos q = new BlockPos(x, yy, z);
                    BlockState s = level.getBlockState(q);
                    if (s.isAir()) continue;
                    if (Town.growing(s) || Town.natural(s)) level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
                }
                for (int k = 1; k <= 5; k++) {
                    BlockPos q = new BlockPos(x, y - k, z);
                    BlockState s = level.getBlockState(q);
                    if (!s.isAir() && !s.canBeReplaced() && s.getFluidState().isEmpty()) break;
                    level.setBlock(q, Blocks.DIRT.defaultBlockState(), 2);
                }
                level.setBlock(new BlockPos(x, y, z), top.defaultBlockState(), 2);
            }
        }
    }

    private static Block fence(String theme) {
        return switch (theme == null ? "north" : theme) {
            case "dorne" -> Blocks.ACACIA_FENCE;
            case "riverlands", "stormlands" -> Blocks.DARK_OAK_FENCE;
            case "reach" -> Blocks.OAK_FENCE;
            default -> Blocks.SPRUCE_FENCE;
        };
    }

    private static Block gateOf(Block fence) {
        return fence == Blocks.ACACIA_FENCE ? Blocks.ACACIA_FENCE_GATE : fence == Blocks.DARK_OAK_FENCE ? Blocks.DARK_OAK_FENCE_GATE
                : fence == Blocks.OAK_FENCE ? Blocks.OAK_FENCE_GATE : Blocks.SPRUCE_FENCE_GATE;
    }

    /** The middle of the plot's side facing {@code face}, at fence height. */
    private static BlockPos sideMiddle(BoundingBox b, Direction face) {
        int y = b.minY() + 1, mx = (b.minX() + b.maxX()) / 2, mz = (b.minZ() + b.maxZ()) / 2;
        return switch (face) {
            case NORTH -> new BlockPos(mx, y, b.minZ());
            case SOUTH -> new BlockPos(mx, y, b.maxZ());
            case WEST -> new BlockPos(b.minX(), y, mz);
            default -> new BlockPos(b.maxX(), y, mz);
        };
    }

    private static boolean edge(BoundingBox b, int x, int z) {
        return x == b.minX() || x == b.maxX() || z == b.minZ() || z == b.maxZ();
    }

    /** Fences and their gates take their shape from what's beside them. */
    private static void join(ServerLevel level, List<BlockPos> fences) {
        for (BlockPos q : fences) {
            BlockState s = level.getBlockState(q);
            BlockState fixed = Block.updateFromNeighbourShapes(s, level, q);
            if (fixed != s) level.setBlock(q, fixed, 2 | 16);
        }
    }

    /** A field: rows of crops on watered farmland inside a fence, its gate toward the town. Returns the gate. */
    static BlockPos field(ServerLevel level, BoundingBox b, Direction face, String theme, RandomSource r) {
        flatten(level, b, Blocks.GRASS_BLOCK);
        int y = b.minY();
        Block fence = fence(theme);
        boolean rowsX = b.getXSpan() >= b.getZSpan();
        List<BlockPos> fences = new ArrayList<>();
        Block[] crops = new Block[8];
        for (int i = 0; i < crops.length; i++) crops[i] = r.nextInt(10) < 7 ? Blocks.WHEAT : r.nextBoolean() ? Blocks.CARROTS : Blocks.POTATOES;
        // the scarecrow's corner: inside, at the far side from the gate
        BlockPos far = sideMiddle(b, face.getOpposite()).relative(face).below();
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                BlockPos g = new BlockPos(x, y, z);
                if (edge(b, x, z)) {
                    BlockPos f = g.above();
                    level.setBlock(f, fence.defaultBlockState(), 2 | 16);
                    fences.add(f);
                    continue;
                }
                int row = rowsX ? z - b.minZ() - 1 : x - b.minX() - 1;
                if (row % 5 == 2) {
                    level.setBlock(g, Blocks.WATER.defaultBlockState(), 2);
                    continue;
                }
                if (g.equals(far)) {
                    level.setBlock(g, Blocks.COARSE_DIRT.defaultBlockState(), 2);
                    level.setBlock(g.above(), fence.defaultBlockState(), 2 | 16);
                    level.setBlock(g.above(2), Blocks.HAY_BLOCK.defaultBlockState(), 2);
                    level.setBlock(g.above(3), Blocks.CARVED_PUMPKIN.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.CarvedPumpkinBlock.FACING, face), 2);
                    continue;
                }
                CropBlock crop = (CropBlock) crops[Math.min(crops.length - 1, row / 5)];
                level.setBlock(g, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7), 2);
                level.setBlock(g.above(), crop.getStateForAge(r.nextInt(crop.getMaxAge() + 1)), 2);
            }
        }
        BlockPos gate = sideMiddle(b, face);
        level.setBlock(gate, gateOf(fence).defaultBlockState().setValue(FenceGateBlock.FACING, face), 2 | 16);
        fences.add(gate);
        join(level, fences);
        return gate;
    }

    /** A paddock in two: cows on grass one side, pigs in the muck the other, each with a trough, hay and a lean-to, a gate each toward the town. Returns a gate. */
    static BlockPos paddock(ServerLevel level, BoundingBox b, Direction face, String theme, RandomSource r) {
        flatten(level, b, Blocks.GRASS_BLOCK);
        int y = b.minY();
        Block fence = fence(theme);
        List<BlockPos> fences = new ArrayList<>();
        // split square to the town side, so both pens open on it
        boolean splitX = face.getAxis() == Direction.Axis.Z;     // the town's north or south: divide at the middle x
        int mid = splitX ? (b.minX() + b.maxX()) / 2 : (b.minZ() + b.maxZ()) / 2;
        BoundingBox cows = splitX ? new BoundingBox(b.minX(), y, b.minZ(), mid, y + 1, b.maxZ()) : new BoundingBox(b.minX(), y, b.minZ(), b.maxX(), y + 1, mid);
        BoundingBox pigs = splitX ? new BoundingBox(mid, y, b.minZ(), b.maxX(), y + 1, b.maxZ()) : new BoundingBox(b.minX(), y, mid, b.maxX(), y + 1, b.maxZ());
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                BlockPos g = new BlockPos(x, y, z);
                boolean divider = splitX ? x == mid : z == mid;
                if (edge(b, x, z) || divider) {
                    level.setBlock(g.above(), fence.defaultBlockState(), 2 | 16);
                    fences.add(g.above());
                    continue;
                }
                if (pigs.isInside(g)) {
                    int k = r.nextInt(5);
                    level.setBlock(g, (k < 2 ? Blocks.MUD : k < 4 ? Blocks.COARSE_DIRT : Blocks.PODZOL).defaultBlockState(), 2);
                }
            }
        }
        List<BlockPos> gates = new ArrayList<>();
        for (BoundingBox pen : new BoundingBox[]{cows, pigs}) {
            BlockPos gate = sideMiddle(pen, face);
            level.setBlock(gate, gateOf(fence).defaultBlockState().setValue(FenceGateBlock.FACING, face), 2 | 16);
            gates.add(gate);
            // the far corner: a trough, hay, and a lean-to over them
            BlockPos back = sideMiddle(pen, face.getOpposite()).relative(face).below();
            Direction along = face.getClockWise();
            BlockPos trough = back.relative(along, -1).above(), hay = back.relative(along, 1).above();
            if (pen.isInside(trough.below()) && !edge(b, trough.getX(), trough.getZ()))
                level.setBlock(trough, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3), 2);
            if (pen.isInside(hay.below()) && !edge(b, hay.getX(), hay.getZ())) level.setBlock(hay, Blocks.HAY_BLOCK.defaultBlockState(), 2);
            Block slab = fence == Blocks.ACACIA_FENCE ? Blocks.ACACIA_SLAB : fence == Blocks.DARK_OAK_FENCE ? Blocks.DARK_OAK_SLAB
                    : fence == Blocks.OAK_FENCE ? Blocks.OAK_SLAB : Blocks.SPRUCE_SLAB;
            for (int a = -1; a <= 1; a++) {
                for (int k = 0; k <= 1; k++) {
                    BlockPos roof = back.relative(along, a).relative(face, k).above(3);
                    level.setBlock(roof, slab.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), 2);
                }
            }
            for (int a : new int[]{-1, 1}) {
                BlockPos post = back.relative(along, a).relative(face, 1).above();
                if (level.getBlockState(post).isAir()) {
                    level.setBlock(post, fence.defaultBlockState(), 2 | 16);
                    level.setBlock(post.above(), fence.defaultBlockState(), 2 | 16);
                    fences.add(post);
                    fences.add(post.above());
                }
            }
            // the beasts
            EntityType<? extends Mob> kind = pen == cows ? EntityType.COW : EntityType.PIG;
            BlockPos centre = new BlockPos((pen.minX() + pen.maxX()) / 2, y + 1, (pen.minZ() + pen.maxZ()) / 2);
            for (int i = 0; i < 3; i++) {
                Mob m = kind.create(level);
                if (m == null) continue;
                m.moveTo(centre.getX() + 0.5 + r.nextInt(3) - 1, y + 1, centre.getZ() + 0.5 + r.nextInt(3) - 1, r.nextFloat() * 360F, 0);
                m.finalizeSpawn(level, level.getCurrentDifficultyAt(centre), MobSpawnType.STRUCTURE, null, null);
                m.setPersistenceRequired();
                level.addFreshEntity(m);
            }
        }
        fences.addAll(gates);
        join(level, fences);
        return gates.get(0);
    }

    private Fields() {
    }
}
