package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.Standing;
import net.aiagent256.stormhold.mixin.MapDataInvoker;
import net.aiagent256.stormhold.worldgen.Finder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The great map. Your seat's scouts chart the country round it, ten thousand blocks a side, and the castle's
 * cartographer hangs it on a wall: 25 sheets, 5 across and 5 down, each a scale-4 map 2,048 blocks a side on the
 * world's own map grid, home in the middle, north at the top. Riders go out in pairs to the nearest sheet nobody
 * has charted yet, survey it, and ride home; the sheet is drawn when they're back. The country is drawn from the
 * lie of the land as the world will make it (height and biome, without loading or generating a chunk), and every
 * castle, town and city on a charted sheet is marked with its name in the colour of how it stands with you, the
 * sites of castles nobody has seen yet with an X. The marks are kept up to date as long as the map hangs.
 *
 * <p>A sign that says "Map" on a wall in the seat puts the map on that wall, above the sign; otherwise the
 * cartographer takes a bare wall 5 by 5 near the War Table.
 */
public final class Chart {
    public static final int SIDE = 5, SHEETS = SIDE * SIDE, HOME = SHEETS / 2;
    public static final byte SCALE = 4;
    /** A sheet's side in blocks. */
    public static final int CELL = 128 << SCALE;
    /** Pairs of riders out at once, and the men the castle keeps on its walls while they ride. */
    public static final int PAIRS = 4, KEEP = 6;
    /** Seconds a pair spends riding over a sheet before it turns for home. */
    public static final int SURVEY = 180;
    /** How far from the War Table the cartographer looks for a bare wall. */
    static final int REACH = 16;
    /** Time the drawing may take each tick. */
    static final long BUDGET_NS = 4_000_000L;
    public static final byte UNSEEN = 0, OUT = 1, DRAW = 2, CHARTED = 3;
    static final Set<String> SIGNS = Set.of("map", "great map", "the map", "the great map", "chart", "map room");

    /** The home sheet's square on the map grid. */
    public int homeX, homeZ;
    /** Each sheet's map id, west to east and north to south. */
    public final int[] maps = new int[SHEETS];
    public final byte[] state = new byte[SHEETS];
    public final List<Trip> trips = new ArrayList<>();
    /** Sites of castles nobody has seen, as the riders found them. */
    public final List<Site> sites = new ArrayList<>();
    /** The wall: the top-left frame cell and the way the frames face; null until it's up. */
    @Nullable public BlockPos wallTopLeft;
    public Direction wallFacing = Direction.SOUTH;

    // not saved
    long wallTriedAt = Long.MIN_VALUE, toldNoWallAt = Long.MIN_VALUE;

    /** A pair of riders charting one sheet: out, over it, and home. */
    public static final class Trip {
        public int sheet, stage, left, ride;
        public final EnumMap<Role, Integer> men = new EnumMap<>(Role.class);

        int total() {
            int n = 0;
            for (int v : men.values()) n += v;
            return n;
        }
    }

    public record Site(int sheet, int x, int z, String what) {
    }

    // ------------------------------------------------------------ the grid

    /** The map square a block coordinate lies in, as the game lays out its scale-4 maps. */
    public static int cell(int coord) {
        return Math.floorDiv(coord + 64, CELL);
    }

    public static int centre(int cell) {
        return cell * CELL + CELL / 2 - 64;
    }

    public int sheetX(int i) {
        return centre(homeX + i % SIDE - SIDE / 2);
    }

    public int sheetZ(int i) {
        return centre(homeZ + i / SIDE - SIDE / 2);
    }

    /** The sheet a block lies on, or -1 off the map. */
    public int sheetAt(int x, int z) {
        int col = cell(x) - homeX + SIDE / 2, row = cell(z) - homeZ + SIDE / 2;
        return col < 0 || col >= SIDE || row < 0 || row >= SIDE ? -1 : row * SIDE + col;
    }

    public int charted() {
        int n = 0;
        for (byte s : state) if (s == CHARTED) n++;
        return n;
    }

    /** A new great map for a castle, every sheet's map made and the home sheet waiting to be drawn. */
    public static Chart fresh(ServerLevel level, Holding h) {
        Chart c = new Chart();
        BlockPos at = h.center();
        c.homeX = cell(at.getX());
        c.homeZ = cell(at.getZ());
        for (int i = 0; i < SHEETS; i++) c.maps[i] = newMap(level, c, i);
        c.state[HOME] = DRAW;               // the castle knows its own country
        return c;
    }

    static int newMap(ServerLevel level, Chart c, int i) {
        ItemStack m = MapItem.create(level, c.sheetX(i), c.sheetZ(i), SCALE, false, false);
        Integer id = MapItem.getMapId(m);
        return id == null ? -1 : id;
    }

    /** A sheet as a map to hang: the same map every time, so a copy taken down is the same sheet. */
    public ItemStack stack(Holding h, int i) {
        ItemStack s = new ItemStack(Items.FILLED_MAP);
        s.getOrCreateTag().putInt("map", maps[i]);
        s.setHoverName(Component.literal("The great map of " + h.name + ": " + where(h, i)).withStyle(ChatFormatting.GOLD));
        return s;
    }

    String where(Holding h, int i) {
        if (i == HOME) return "home";
        String w = Guide.way(h.center(), new BlockPos(sheetX(i), 0, sheetZ(i)));
        return w.equals("right here") ? "home" : w;
    }

    // ------------------------------------------------------------ once a second

    public static void tick(ServerLevel level, HoldingManager mgr, Holding h, long now) {
        if (!h.isPlayerOwned() || h.isNether() || level.dimension() != Level.OVERWORLD) return;
        UUID lord = h.ownerPlayer();
        boolean seat = lord != null && mgr.seatOf(lord) == h;
        Chart c = h.chart;
        if (c == null) {
            if (!seat) return;
            c = h.chart = fresh(level, h);
            mgr.setDirty();
        }
        ride(level, mgr, h, c);
        if (seat) launch(level, mgr, h, c);
        if (!level.isLoaded(MapRoom.anchor(h))) return;
        if (c.wallTopLeft == null) {
            if (now - c.wallTriedAt >= 600) {
                c.wallTriedAt = now;
                ensureWall(level, mgr, h);
            }
            return;
        }
        if (now % 200 == 0) refresh(level, mgr, h, c);
    }

    /** Riders on the road: out, over the sheet, home. */
    public static void ride(ServerLevel level, HoldingManager mgr, Holding h, Chart c) {
        for (Iterator<Trip> it = c.trips.iterator(); it.hasNext(); ) {
            Trip t = it.next();
            if (--t.left > 0) continue;
            if (t.stage == 0) {
                lose(level, mgr, h, c, t);
                if (t.total() <= 0) {
                    c.state[t.sheet] = UNSEEN;
                    it.remove();
                    mgr.setDirty();
                    HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("The riders sent from " + h.name + " to chart the country "
                            + c.where(h, t.sheet) + " never came back. Others will go when there are men to spare.").withStyle(ChatFormatting.RED));
                    continue;
                }
                t.stage = 1;
                t.left = SURVEY;
            } else if (t.stage == 1) {
                t.stage = 2;
                t.left = t.ride;
            } else {
                for (var e : t.men.entrySet()) h.add(e.getKey(), e.getValue());
                survey(level, c, t.sheet);
                c.state[t.sheet] = DRAW;
                it.remove();
                mgr.setDirty();
            }
        }
    }

    /** Send pairs to the nearest sheets nobody has charted, while the castle has the men. */
    public static void launch(ServerLevel level, HoldingManager mgr, Holding h, Chart c) {
        BlockPos from = Campaigns.gateOutside(h);
        double speed = Scouts.SPEED * (Council.has(h.ownerPlayer(), Council.Seat.SPYMASTER) ? 1.5 : 1.0);
        while (c.trips.size() < PAIRS && h.troops() >= KEEP + 2) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < SHEETS; i++) {
                if (c.state[i] != UNSEEN) continue;
                double d = dist(from, c.sheetX(i), c.sheetZ(i));
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            if (best < 0) return;
            Trip t = new Trip();
            t.sheet = best;
            t.ride = Math.max(1, (int) Math.ceil(bestD / speed));
            t.left = t.ride;
            for (int k = 0; k < 2; k++) {
                Role r = h.count(Role.KNIGHT) > 1 ? Role.KNIGHT : h.count(Role.ARCHER) > 1 ? Role.ARCHER
                        : h.count(Role.CROSSBOWMAN) > 0 ? Role.CROSSBOWMAN : Role.KNIGHT;
                h.add(r, -1);
                t.men.merge(r, 1, Integer::sum);
            }
            c.trips.add(t);
            c.state[best] = OUT;
            mgr.setDirty();
        }
    }

    /** At the sheet: riders lost to the frontier's beasts and to castles that hate you. */
    static void lose(ServerLevel level, HoldingManager mgr, Holding h, Chart c, Trip t) {
        BlockPos at = new BlockPos(c.sheetX(t.sheet), 64, c.sheetZ(t.sheet));
        UUID lord = h.ownerPlayer();
        double risk = 0.03 * (Frontier.zone(level, at) - 1);
        for (Holding o : mgr.holdings.values()) {
            if (o == h || o.isNether() || c.sheetAt(o.center().getX(), o.center().getZ()) != t.sheet) continue;
            if (o.isHouseOwned() && lord != null && Standing.hostile(mgr, o.houseId, lord)) risk += 0.08;
            else if (Sides.OUTLAWS.equals(o.owner)) risk += 0.05;
        }
        if (Council.has(lord, Council.Seat.SPYMASTER)) risk /= 2;
        risk = Math.min(0.5, risk);
        for (Role r : Role.TROOPS) {
            int n = t.men.getOrDefault(r, 0);
            for (int k = 0; k < n; k++) if (level.random.nextDouble() < risk) t.men.merge(r, -1, Integer::sum);
        }
    }

    /** The sites of castles nobody has seen, on a sheet the riders crossed. */
    static void survey(ServerLevel level, Chart c, int sheet) {
        c.sites.removeIf(s -> s.sheet() == sheet);
        BlockPos at = new BlockPos(c.sheetX(sheet), 64, c.sheetZ(sheet));
        for (Finder.Found f : Finder.within(level, at, CELL * 3 / 4, 150)) {
            if (c.sheetAt(f.x(), f.z()) == sheet) c.sites.add(new Site(sheet, f.x(), f.z(), f.what()));
        }
    }

    // ------------------------------------------------------------ the wall

    /** Put the map up: on the wall over a sign that says "Map", else on a bare wall near the War Table. */
    public static boolean ensureWall(ServerLevel level, HoldingManager mgr, Holding h) {
        Chart c = h.chart;
        if (c == null) return false;
        if (c.wallTopLeft != null) return true;
        long now = level.getGameTime();
        SignBlockEntity sign = sign(level, h);
        if (sign != null) {
            BlockState st = sign.getBlockState();
            if (st.getBlock() instanceof WallSignBlock) {
                Direction f = st.getValue(WallSignBlock.FACING);
                Direction right = f.getOpposite().getClockWise();
                BlockPos tl = sign.getBlockPos().relative(right, -(SIDE / 2)).above(SIDE);
                if (fits(level, h, tl, f, false)) {
                    put(level, mgr, h, c, tl, f);
                    return true;
                }
            }
            if (now - c.toldNoWallAt >= 24000) {
                c.toldNoWallAt = now;
                BlockPos p = sign.getBlockPos();
                HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("The great map of " + h.name + " won't go up over the sign at "
                        + p.getX() + ", " + p.getY() + ", " + p.getZ() + ": it wants the sign on a wall, with a solid wall 5 blocks wide and 5 high"
                        + " over it and nothing hanging there.").withStyle(ChatFormatting.RED));
            }
            return false;
        }
        BlockPos anchor = MapRoom.anchor(h);
        BlockPos best = null;
        Direction bestD = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = -3; dy <= 6; dy++) {
            for (int dx = -REACH; dx <= REACH; dx++) {
                for (int dz = -REACH; dz <= REACH; dz++) {
                    BlockPos bl = anchor.offset(dx, dy, dz);
                    double dist = bl.distSqr(anchor);
                    if (dist >= bestDist) continue;
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos tl = bl.above(SIDE - 1);
                        if (fits(level, h, tl, d, true)) {
                            best = tl;
                            bestD = d;
                            bestDist = dist;
                            break;
                        }
                    }
                }
            }
        }
        if (best != null) {
            put(level, mgr, h, c, best, bestD);
            return true;
        }
        if (now - c.toldNoWallAt >= 24000) {
            c.toldNoWallAt = now;
            HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("Your scouts are charting the country round " + h.name
                    + ", but there's no wall for the great map yet. Put a sign that says Map on a wall with room for 5 by 5 maps over it, or clear a bare wall"
                    + " that size within " + REACH + " blocks of the War Table.").withStyle(ChatFormatting.GOLD));
        }
        return false;
    }

    /** Every frame cell clear, a solid wall behind each, nothing hanging there, and none of it the map room's wall. */
    static boolean fits(ServerLevel level, Holding h, BlockPos tl, Direction d, boolean inBox) {
        Direction right = d.getOpposite().getClockWise();
        for (int col = 0; col < SIDE; col++) {
            for (int row = 0; row < SIDE; row++) {
                BlockPos p = tl.relative(right, col).below(row);
                if (!level.isLoaded(p) || (inBox && !h.box.isInside(p)) || MapRoom.onWall(h, p)) return false;
                if (!level.getBlockState(p).isAir()) return false;
                if (inBox && !level.getBlockState(p.relative(d)).isAir()) return false;
                BlockPos wall = p.relative(d.getOpposite());
                BlockState ws = level.getBlockState(wall);
                if (!ws.isSolidRender(level, wall) || !ws.isFaceSturdy(level, wall, d)) return false;
            }
        }
        BlockPos br = tl.relative(right, SIDE - 1).below(SIDE - 1);
        return level.getEntitiesOfClass(HangingEntity.class, new AABB(tl).minmax(new AABB(br)).inflate(0.1)).isEmpty();
    }

    static void put(ServerLevel level, HoldingManager mgr, Holding h, Chart c, BlockPos tl, Direction d) {
        c.wallTopLeft = tl;
        c.wallFacing = d;
        mgr.setDirty();
        refresh(level, mgr, h, c);
        BlockPos mid = c.frame(SIDE / 2, SIDE - 1);
        long all = 0, near = 0;
        BlockPos from = Campaigns.gateOutside(h);
        List<Long> each = new ArrayList<>();
        for (int i = 0; i < SHEETS; i++) {
            if (c.state[i] == CHARTED || c.state[i] == DRAW) continue;
            each.add((long) (2 * Math.ceil(dist(from, c.sheetX(i), c.sheetZ(i)) / Scouts.SPEED) + SURVEY));
        }
        each.sort(Long::compare);
        for (int k = 0; k < each.size(); k++) {
            all += each.get(k);
            if (k < 8) near += each.get(k);
        }
        String time = each.isEmpty() ? "" : " Riders go out in pairs, " + PAIRS + " pairs at a time, and each sheet goes up when its riders are home: "
                + "the near ring in about " + Scouts.time((int) (near / PAIRS)) + " of play, the whole of it in about " + Scouts.time((int) (all / PAIRS)) + ".";
        HoldingEvents.tell(level, h.ownerPlayer(), HoldingEvents.gold("The cartographer of " + h.name + " hangs the great map (" + mid.getX()
                + ", " + mid.getY() + ", " + mid.getZ() + "): " + SHEETS + " sheets, " + SIDE + " across and " + SIDE + " down, each "
                + String.format(Locale.ROOT, "%,d", CELL) + " blocks a side, " + String.format(Locale.ROOT, "%,d", CELL * SIDE)
                + " in all, home in the middle and north at the top." + time));
    }

    /** The frame cell of a sheet: column west to east, row north to south. */
    public BlockPos frame(int col, int row) {
        Direction right = wallFacing.getOpposite().getClockWise();
        return wallTopLeft.relative(right, col).below(row);
    }

    /** Whether {@code p} is one of the great map's frame cells: other maps keep off it. */
    public static boolean onWall(Holding h, BlockPos p) {
        Chart c = h.chart;
        if (c == null || c.wallTopLeft == null) return false;
        for (int col = 0; col < SIDE; col++) for (int row = 0; row < SIDE; row++) if (c.frame(col, row).equals(p)) return true;
        return false;
    }

    /** The first sign in the castle that says "Map". */
    @Nullable
    static SignBlockEntity sign(ServerLevel level, Holding h) {
        for (int cx = h.box.minX() >> 4; cx <= h.box.maxX() >> 4; cx++) {
            for (int cz = h.box.minZ() >> 4; cz <= h.box.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof SignBlockEntity s) || !h.box.isInside(be.getBlockPos())) continue;
                    if (SIGNS.contains(line(s))) return s;
                }
            }
        }
        return null;
    }

    static String line(SignBlockEntity s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4; i++) b.append(' ').append(s.getFrontText().getMessage(i, false).getString());
        String t = clean(b.toString());
        if (t.isEmpty()) {
            b.setLength(0);
            for (int i = 0; i < 4; i++) b.append(' ').append(s.getBackText().getMessage(i, false).getString());
            t = clean(b.toString());
        }
        return t;
    }

    static String clean(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\[\\]():!.,]", " ").trim().replaceAll("\\s+", " ");
    }

    /** Every sheet in its frame (a frame put back if it's gone), and the marks on the charted ones brought up to date. */
    static void refresh(ServerLevel level, HoldingManager mgr, Holding h, Chart c) {
        if (c.wallTopLeft == null) return;
        for (int i = 0; i < SHEETS; i++) {
            BlockPos p = c.frame(i % SIDE, i / SIDE);
            if (!level.isLoaded(p)) continue;
            if (MapItem.getSavedData(c.maps[i], level) == null) {
                c.maps[i] = newMap(level, c, i);
                if (c.state[i] == CHARTED) c.state[i] = DRAW;
                mgr.setDirty();
            }
            List<ItemFrame> there = level.getEntitiesOfClass(ItemFrame.class, new AABB(p), f -> f.getDirection() == c.wallFacing);
            if (there.isEmpty()) {
                if (level.getBlockState(p).isAir()) {
                    ItemFrame f = new ItemFrame(level, p, c.wallFacing);
                    if (f.survives()) {
                        f.setItem(c.stack(h, i), false);
                        level.addFreshEntity(f);
                    }
                }
            } else if (there.get(0).getItem().isEmpty()) {
                there.get(0).setItem(c.stack(h, i), false);
            }
            if (c.state[i] == CHARTED) decorate(level, mgr, h, c, i);
        }
    }

    /** The marks on a charted sheet: every place on it in the colour of how it stands with you, and unseen sites. */
    static void decorate(ServerLevel level, HoldingManager mgr, Holding h, Chart c, int i) {
        MapItemSavedData data = MapItem.getSavedData(c.maps[i], level);
        if (data == null) return;
        MapDataInvoker marks = (MapDataInvoker) data;
        UUID lord = h.ownerPlayer();
        List<BlockPos> places = new ArrayList<>();
        for (Holding o : mgr.holdings.values()) {
            if (o.isNether()) continue;
            BlockPos p = o.center();
            places.add(p);
            if (c.sheetAt(p.getX(), p.getZ()) != i) continue;
            marks.stormhold$addDecoration(type(mgr, o, lord), level, "sh_" + o.id, p.getX(), p.getZ(), 180.0, Component.literal(o.name));
        }
        for (Site s : c.sites) {
            if (s.sheet() != i) continue;
            String id = "sh_site_" + s.x() + "_" + s.z();
            boolean taken = false;
            for (BlockPos p : places) if (dist(p, s.x(), s.z()) < 160) taken = true;
            if (taken) marks.stormhold$removeDecoration(id);
            else marks.stormhold$addDecoration(MapDecoration.Type.TARGET_X, level, id, s.x(), s.z(), 180.0, Component.literal(s.what()));
        }
    }

    static MapDecoration.Type type(HoldingManager mgr, Holding o, @Nullable UUID lord) {
        if (lord != null && lord.equals(o.ownerPlayer())) return MapDecoration.Type.BANNER_LIME;
        if (Sides.OUTLAWS.equals(o.owner)) return MapDecoration.Type.BANNER_BLACK;
        if (o.isHouseOwned() && lord != null) {
            if (Standing.hostile(mgr, o.houseId, lord)) return MapDecoration.Type.BANNER_RED;
            if (Standing.friendly(mgr, o.houseId, lord)) return MapDecoration.Type.BANNER_LIGHT_BLUE;
        }
        if (o.isPlayerOwned()) return MapDecoration.Type.BANNER_ORANGE;
        return MapDecoration.Type.BANNER_WHITE;
    }

    // ------------------------------------------------------------ drawing, a few rows a tick

    private static final class Job {
        UUID holding;
        int sheet, row;
        int[] prev;
    }

    @Nullable private static Job job;

    /** Every tick: draw the next rows of a sheet waiting to be drawn, within the time budget. */
    public static void drawTick(ServerLevel level, HoldingManager mgr) {
        if (level.dimension() != Level.OVERWORLD) return;
        if (job != null) {
            Holding h = mgr.holdings.get(job.holding);
            if (h == null || h.chart == null || h.chart.state[job.sheet] != DRAW) job = null;
        }
        if (job == null) {
            for (Holding h : mgr.holdings.values()) {
                if (h.chart == null) continue;
                for (int i = 0; i < SHEETS; i++) {
                    if (h.chart.state[i] != DRAW) continue;
                    job = new Job();
                    job.holding = h.id;
                    job.sheet = i;
                    break;
                }
                if (job != null) break;
            }
            if (job == null) return;
        }
        Holding h = mgr.holdings.get(job.holding);
        Chart c = h.chart;
        long until = System.nanoTime() + BUDGET_NS;
        if (draw(level, c, job, until)) {
            int sheet = job.sheet;
            job = null;
            done(level, mgr, h, c, sheet);
        }
    }

    /** Draw a whole sheet at once (tests, and nothing else). */
    public static void drawAll(ServerLevel level, HoldingManager mgr, Holding h, int sheet) {
        Job j = new Job();
        j.holding = h.id;
        j.sheet = sheet;
        draw(level, h.chart, j, Long.MAX_VALUE);
        done(level, mgr, h, h.chart, sheet);
    }

    /** Rows until the budget's spent; true when the sheet is finished. */
    static boolean draw(ServerLevel level, Chart c, Job j, long until) {
        MapItemSavedData data = MapItem.getSavedData(c.maps[j.sheet], level);
        if (data == null) {
            c.maps[j.sheet] = newMap(level, c, j.sheet);
            data = MapItem.getSavedData(c.maps[j.sheet], level);
            if (data == null) return false;
        }
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int sea = gen.getSeaLevel();
        int step = 1 << data.scale;
        int x0 = data.centerX - 64 * step, z0 = data.centerZ - 64 * step;
        Ground ground = Ground.of(level, gen, rs);
        if (j.prev == null) {
            j.prev = new int[128];
            int hint = sea;
            for (int px = 0; px < 128; px++) hint = j.prev[px] = ground.height(x0 + px * step + step / 2, z0 - step / 2, hint);
        }
        while (j.row < 128) {
            int z = z0 + j.row * step + step / 2;
            int[] row = new int[128];
            for (int px = 0; px < 128; px++) {
                int x = x0 + px * step + step / 2;
                int y = ground.height(x, z, j.prev[px]);
                row[px] = y;
                Holder<Biome> b = gen.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(Math.max(y, sea)),
                        QuartPos.fromBlock(z), rs.sampler());
                data.updateColor(px, j.row, colour(b, x, y, z, sea, j.prev[px], px, j.row));
            }
            j.prev = row;
            j.row++;
            if (System.nanoTime() >= until && j.row < 128) return false;
        }
        return true;
    }

    static void done(ServerLevel level, HoldingManager mgr, Holding h, Chart c, int sheet) {
        if (sheet < 0) return;
        c.state[sheet] = CHARTED;
        mgr.setDirty();
        if (c.wallTopLeft != null && level.isLoaded(c.frame(sheet % SIDE, sheet / SIDE))) decorate(level, mgr, h, c, sheet);
        int n = c.charted();
        if (n == SHEETS) {
            HoldingEvents.tell(level, h.ownerPlayer(), HoldingEvents.gold("The great map of " + h.name + " is finished: "
                    + String.format(Locale.ROOT, "%,d", CELL * SIDE) + " blocks of country a side, every castle on it."));
        } else if (sheet != HOME) {
            HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("Riders are home at " + h.name + ": the sheet of the country "
                    + c.where(h, sheet) + " goes up on the great map (" + n + " of " + SHEETS + ").").withStyle(ChatFormatting.GRAY));
        }
    }

    /**
     * The ground's height at a spot as the world will make it, without loading or making a chunk. On a noise world
     * it's the preliminary surface the game itself works out for its aquifers and surface rules: the first height
     * from the top where the terrain's density, without the peaks' jaggedness or the caves, is solid. A few points of
     * one density function a spot, where the exact height runs the whole noise column. Started from a hint (the
     * height next door), it's a short way down to the ground from just over it.
     */
    static final class Ground {
        /** The density at which the game counts the preliminary surface solid. */
        static final double SOLID = 0.390625;
        final ServerLevel level;
        final ChunkGenerator gen;
        final RandomState rs;
        @Nullable final DensityFunction density;
        final int minY, top;

        private Ground(ServerLevel level, ChunkGenerator gen, RandomState rs, @Nullable DensityFunction density, int minY, int top) {
            this.level = level;
            this.gen = gen;
            this.rs = rs;
            this.density = density;
            this.minY = minY;
            this.top = top;
        }

        static Ground of(ServerLevel level, ChunkGenerator gen, RandomState rs) {
            if (gen instanceof NoiseBasedChunkGenerator nb) {
                NoiseSettings ns = nb.generatorSettings().value().noiseSettings();
                return new Ground(level, gen, rs, rs.router().initialDensityWithoutJaggedness(), ns.minY(), ns.minY() + ns.height());
            }
            return new Ground(level, gen, rs, null, level.getMinBuildHeight(), level.getMaxBuildHeight());
        }

        boolean solid(int x, int y, int z) {
            return density.compute(new DensityFunction.SinglePointContext(x, y, z)) > SOLID;
        }

        int height(int x, int z, int hint) {
            if (density == null) return gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
            // down in eights from a little over the hint (from the top if that's already in the ground)
            int y = Math.min(top, hint + 40);
            if (solid(x, y, z)) y = top;
            while (y >= minY && !solid(x, y, z)) y -= 8;
            if (y < minY) return minY;
            // the first air within the eight above
            int lo = y, hi = Math.min(top, y + 8);
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (solid(x, mid, z)) lo = mid;
                else hi = mid;
            }
            return hi;
        }
    }

    /** The ground's height as the great map reckons it (tests). */
    public static int groundAt(ServerLevel level, int x, int z, int hint) {
        return Ground.of(level, level.getChunkSource().getGenerator(), level.getChunkSource().randomState()).height(x, z, hint);
    }

    /** One pixel: water shaded by its depth, the land by its biome and shaded by the slope from the north, as the game's maps are. */
    static byte colour(Holder<Biome> b, int x, int y, int z, int sea, int north, int px, int pz) {
        BlockPos pos = new BlockPos(x, Math.max(y, sea), z);
        if (y < sea) {
            if (b.value().coldEnoughToSnow(pos)) return MapColor.ICE.getPackedId(MapColor.Brightness.NORMAL);
            double d = (sea - y) * 0.1 + ((px + pz) & 1) * 0.2;
            return MapColor.WATER.getPackedId(d < 0.5 ? MapColor.Brightness.HIGH : d > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL);
        }
        double d = (y - north) * 4.0 / (16 + 4) + (((px + pz) & 1) - 0.5) * 0.4;
        MapColor.Brightness br = d > 0.6 ? MapColor.Brightness.HIGH : d < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        return land(b, y, sea, pos).getPackedId(br);
    }

    static MapColor land(Holder<Biome> b, int y, int sea, BlockPos pos) {
        String path = b.unwrapKey().map(k -> k.location().getPath()).orElse("");
        boolean snow = b.value().coldEnoughToSnow(pos);
        if (y >= sea + 95) return MapColor.SNOW;
        if (y >= sea + 60) return snow ? MapColor.SNOW : MapColor.STONE;
        if (snow) return MapColor.SNOW;
        if (b.is(BiomeTags.IS_BEACH) || path.contains("beach") || path.contains("shore")) return MapColor.SAND;
        if (b.is(BiomeTags.IS_BADLANDS) || path.contains("badlands") || path.contains("mesa")) return MapColor.TERRACOTTA_ORANGE;
        if (path.contains("desert") || path.contains("dune") || (!b.value().hasPrecipitation() && b.value().getBaseTemperature() >= 1.5f))
            return MapColor.SAND;
        if (path.contains("mushroom")) return MapColor.COLOR_PURPLE;
        if (path.contains("swamp") || path.contains("marsh") || path.contains("bog") || path.contains("bayou") || path.contains("mangrove"))
            return MapColor.TERRACOTTA_GREEN;
        if (b.is(BiomeTags.IS_SAVANNA) || path.contains("savanna") || path.contains("steppe") || path.contains("prairie") || path.contains("outback"))
            return MapColor.TERRACOTTA_LIGHT_GREEN;
        if (b.is(BiomeTags.IS_FOREST) || b.is(BiomeTags.IS_TAIGA) || b.is(BiomeTags.IS_JUNGLE) || path.contains("forest") || path.contains("wood")
                || path.contains("grove") || path.contains("jungle") || path.contains("taiga")) return MapColor.PLANT;
        return MapColor.GRASS;
    }

    static double dist(BlockPos a, int x, int z) {
        double dx = a.getX() - x, dz = a.getZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ------------------------------------------------------------ saving

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("HomeX", homeX);
        t.putInt("HomeZ", homeZ);
        t.putIntArray("Maps", maps);
        t.putByteArray("State", state);
        ListTag tl = new ListTag();
        for (Trip tr : trips) {
            CompoundTag x = new CompoundTag();
            x.putInt("Sheet", tr.sheet);
            x.putInt("Stage", tr.stage);
            x.putInt("Left", tr.left);
            x.putInt("Ride", tr.ride);
            CompoundTag m = new CompoundTag();
            tr.men.forEach((r, n) -> m.putInt(r.name(), n));
            x.put("Men", m);
            tl.add(x);
        }
        t.put("Trips", tl);
        ListTag sl = new ListTag();
        for (Site s : sites) {
            CompoundTag x = new CompoundTag();
            x.putInt("Sheet", s.sheet());
            x.putInt("X", s.x());
            x.putInt("Z", s.z());
            x.putString("What", s.what());
            sl.add(x);
        }
        t.put("Sites", sl);
        if (wallTopLeft != null) {
            t.put("Wall", NbtUtils.writeBlockPos(wallTopLeft));
            t.putInt("Facing", wallFacing.get3DDataValue());
        }
        return t;
    }

    public static Chart load(CompoundTag t) {
        Chart c = new Chart();
        c.homeX = t.getInt("HomeX");
        c.homeZ = t.getInt("HomeZ");
        int[] m = t.getIntArray("Maps");
        byte[] s = t.getByteArray("State");
        for (int i = 0; i < SHEETS; i++) {
            c.maps[i] = i < m.length ? m[i] : -1;
            c.state[i] = i < s.length ? s[i] : UNSEEN;
        }
        for (var e : t.getList("Trips", 10)) {
            CompoundTag x = (CompoundTag) e;
            Trip tr = new Trip();
            tr.sheet = x.getInt("Sheet");
            tr.stage = x.getInt("Stage");
            tr.left = x.getInt("Left");
            tr.ride = x.getInt("Ride");
            CompoundTag men = x.getCompound("Men");
            for (String k : men.getAllKeys()) {
                try {
                    tr.men.put(Role.valueOf(k), men.getInt(k));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (tr.sheet >= 0 && tr.sheet < SHEETS) c.trips.add(tr);
        }
        // a sheet marked out with nobody riding to it is unseen again
        for (int i = 0; i < SHEETS; i++) {
            if (c.state[i] != OUT) continue;
            boolean riding = false;
            for (Trip tr : c.trips) if (tr.sheet == i) riding = true;
            if (!riding) c.state[i] = UNSEEN;
        }
        for (var e : t.getList("Sites", 10)) {
            CompoundTag x = (CompoundTag) e;
            c.sites.add(new Site(x.getInt("Sheet"), x.getInt("X"), x.getInt("Z"), x.getString("What")));
        }
        if (t.contains("Wall")) {
            c.wallTopLeft = NbtUtils.readBlockPos(t.getCompound("Wall"));
            c.wallFacing = Direction.from3DDataValue(t.getInt("Facing"));
            if (c.wallFacing.getAxis() == Direction.Axis.Y) c.wallFacing = Direction.SOUTH;
        }
        return c;
    }

    /** For the guide: how far along the map is. */
    public String progress() {
        int out = trips.size();
        return charted() + " of " + SHEETS + " sheets charted" + (out > 0 ? ", " + out + (out == 1 ? " pair" : " pairs") + " of riders out" : "");
    }

    private Chart() {
    }
}
