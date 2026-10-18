package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.block.PortcullisBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The great castles' yard market: a quartermaster and three stalls (armoury, weaponsmith, fletcher) put up
 * in the castle yard the first time the castle is seen with all its ground loaded. Each stall is the towns'
 * stall: a coloured awning on four fence posts, a trapdoor counter, goods and a barrel behind, a lantern and
 * a hanging sign. Its keeper is a {@link Shops} keeper like a town's.
 *
 * <p>The spot is found in the world, not in the plans: open level ground reached on foot from the rally
 * point, with sky over it and room to walk round, clear of the gate passage and the garrison's posts. So a
 * castle raised by an older build gets its market where its own yard actually is.
 */
public final class Market {
    public static final List<String> KINDS = List.of("quartermaster", "armoury", "weapons", "fletcher");
    /** How far from the rally point the first stall stands, so the men have their muster ground. */
    static final int FROM_RALLY = 8;
    /** Stall centres at least this far apart: two blocks of lane between their posts. */
    static final int SPACING = 5;
    /** The market's stalls all stand within this of its first, so they read as one market. */
    static final int SPREAD = 14;
    private static final int NONE = Integer.MIN_VALUE;
    /** What the last search found, step by step: for the log and the tests. */
    public static String lastReport = "";

    record Look(Block wool, Block carpet, BlockState left, BlockState right, String line1, String line2) {
    }

    public record Site(BlockPos center, Direction facing) {
    }

    /** Whether a place gets a yard market: Stormhold's own great castles. */
    public static boolean wants(Holding h) {
        return h.layout != null && h.layout.startsWith("great_castle") && !h.isTown() && !h.isCity();
    }

    /** Put the market up once, when the castle's chunks are all loaded. */
    public static void tick(ServerLevel level, HoldingManager mgr, Holding h) {
        if (!wants(h) || h.marketVersion >= 1 || !PostScanner.allLoaded(level, h)) return;
        // marked first: a castle with no yard to spare isn't searched again every few seconds
        h.marketVersion = 1;
        mgr.setDirty();
        int n = raise(level, h);
        Stormhold.LOGGER.info("Stormhold: {} put up {} market stalls ({})", h.name, n, lastReport);
    }

    /** Find the stalls' places, build them and record their keepers' posts. How many stalls went up. */
    public static int raise(ServerLevel level, Holding h) {
        List<Site> sites = plan(level, h, KINDS.size());
        for (int i = 0; i < sites.size(); i++) {
            String kind = KINDS.get(i);
            build(level, sites.get(i), kind);
            h.extras.put("shop_" + kind, new ArrayList<>(List.of(sites.get(i).center())));
        }
        if (!sites.isEmpty()) h.extras.put("market", new ArrayList<>(List.of(sites.get(0).center())));
        return sites.size();
    }

    // ------------------------------------------------------------ where

    /** Up to n stall sites in the castle yard: where the most stalls fit together, and of those the nearest the rally point. */
    public static List<Site> plan(ServerLevel level, Holding h, int n) {
        BoundingBox b = h.box;
        int x0 = b.minX() + 2, z0 = b.minZ() + 2, x1 = b.maxX() - 2, z1 = b.maxZ() - 2;
        int w = x1 - x0 + 1, d = z1 - z0 + 1;
        if (w < 7 || d < 7) return List.of();
        int[] fy = new int[w * d];
        Arrays.fill(fy, NONE);
        boolean[] tall = new boolean[w * d];
        boolean[] door = new boolean[w * d];
        Layout l = h.plans();
        List<BlockPos> gates = new ArrayList<>();
        gates.add(h.gatePos);
        gates.addAll(h.extras.getOrDefault("gate2", List.of()));

        // the yard: every spot a man can walk to from the rally point on the ground floor
        BlockPos start = startNear(level, h.rallyPos, x0, z0, x1, z1);
        if (start == null) {
            lastReport = "no ground to stand on at the rally point " + h.rallyPos.toShortString();
            return List.of();
        }
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        int si = (start.getZ() - z0) * w + (start.getX() - x0);
        fy[si] = start.getY();
        tall[si] = tall(level, start);
        q.add(start);
        while (!q.isEmpty()) {
            BlockPos p = q.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                int nx = p.getX() + dir.getStepX(), nz = p.getZ() + dir.getStepZ();
                if (nx < x0 || nx > x1 || nz < z0 || nz > z1) continue;
                int i = (nz - z0) * w + (nx - x0);
                if (fy[i] != NONE) continue;
                if (l != null && !l.inside(nx - b.minX(), nz - b.minZ())) continue;
                if (nearAny(gates, nx, nz, 6)) continue;           // not out through the gate passage
                BlockPos level0 = new BlockPos(nx, p.getY(), nz);
                var through = level.getBlockState(level0).getBlock();
                if (through instanceof DoorBlock || through instanceof PortcullisBlock) {
                    // the yard's doors and inner gates are shut as often as not: walk through, but no stall here
                    fy[i] = p.getY();
                    door[i] = true;
                    q.add(level0);
                    continue;
                }
                for (int dy : new int[]{0, 1, -1}) {
                    BlockPos m = new BlockPos(nx, p.getY() + dy, nz);
                    if (!Shops.standable(level, m)) continue;
                    if (dy == 1 && !clear(level, p.above(2))) continue;
                    if (dy == -1 && !clear(level, m.above(2))) continue;
                    fy[i] = m.getY();
                    tall[i] = tall(level, m);
                    q.add(m);
                    break;
                }
            }
        }

        // where a stall fits: 7x7 of level, walkable ground (the stall and a two-block lane round it), the
        // stall's own 3x3 free to the roof and open to the sky, off the muster ground and the men's posts
        List<BlockPos> keepOff = new ArrayList<>();
        for (List<BlockPos> ps : h.posts.values()) keepOff.addAll(ps);
        for (var e : h.extras.entrySet()) if (!e.getKey().startsWith("shop_") && !e.getKey().equals("market")) keepOff.addAll(e.getValue());
        keepOff.add(h.lordPos);
        keepOff.add(h.warTablePos);
        keepOff.add(h.bannerPos);
        List<BlockPos> cands = new ArrayList<>();
        int yard = 0, far = 0, level7 = 0, roofed = 0, posted = 0, road = 0, buried = 0;
        for (int v : fy) if (v != NONE) yard++;
        for (int cz = z0 + 3; cz <= z1 - 3; cz++) {
            for (int cx = x0 + 3; cx <= x1 - 3; cx++) {
                int y = fy[(cz - z0) * w + (cx - x0)];
                if (y == NONE) continue;
                if (cheb(cx, cz, h.rallyPos) < FROM_RALLY) continue;
                far++;
                boolean ok = true;
                for (int dz = -3; dz <= 3 && ok; dz++)
                    for (int dx = -3; dx <= 3 && ok; dx++)
                        if (fy[(cz + dz - z0) * w + (cx + dx - x0)] != y || door[(cz + dz - z0) * w + (cx + dx - x0)]) ok = false;
                if (!ok) continue;
                level7++;
                for (int dz = -1; dz <= 1 && ok; dz++)
                    for (int dx = -1; dx <= 1 && ok; dx++)
                        if (!tall[(cz + dz - z0) * w + (cx + dx - x0)]) ok = false;
                if (!ok) continue;
                roofed++;
                if (nearAny(keepOff, cx, cz, 3)) continue;
                posted++;
                boolean onRoad = false;                            // the way in from each gate stays clear
                for (BlockPos gt : gates) if (segDist(cx, cz, gt, h.rallyPos) <= 4) onRoad = true;
                if (onRoad) continue;
                road++;
                BlockPos c = new BlockPos(cx, y, cz);
                for (int dz = -1; dz <= 1 && ok; dz++)
                    for (int dx = -1; dx <= 1 && ok; dx++)
                        if (!free(level, c.offset(dx, 0, dz)) || !free(level, c.offset(dx, 1, dz))) ok = false;
                if (!ok) continue;
                buried++;
                // open to the sky: nothing over the stall (the heightmap, not sky light, which lags behind blocks)
                for (int dz = -1; dz <= 1 && ok; dz++)
                    for (int dx = -1; dx <= 1 && ok; dx++)
                        if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx + dx, cz + dz) > y) ok = false;
                if (ok) cands.add(c);
            }
        }
        lastReport = "yard " + yard + " spots from " + start.toShortString() + "; stall centres off the muster ground " + far
                + ", on 7x7 level ground " + level7 + ", room overhead " + roofed + ", off the posts " + posted
                + ", off the road " + road + ", nothing buried " + buried + ", open sky " + cands.size();

        // the market: of every spot the first stall could take, the one with room for the most stalls round
        // it, and of those the nearest the rally point
        List<Site> out = List.of();
        int bestCount = 0, bestDist = Integer.MAX_VALUE;
        for (BlockPos first : cands) {
            List<Site> o = grow(cands, first, h.rallyPos, n);
            int dist = cheb(first.getX(), first.getZ(), h.rallyPos);
            if (o.size() > bestCount || (o.size() == bestCount && dist < bestDist)) {
                out = o;
                bestCount = o.size();
                bestDist = dist;
            }
        }
        return out;
    }

    /** A first stall and the rest beside it, in a row along its front rather than in front of it. */
    private static List<Site> grow(List<BlockPos> cands, BlockPos first, BlockPos rally, int n) {
        List<Site> out = new ArrayList<>();
        out.add(new Site(first, toward(first, rally)));
        Direction fd = out.get(0).facing();
        while (out.size() < n) {
            BlockPos best = null;
            int bestScore = Integer.MAX_VALUE;
            for (BlockPos c : cands) {
                if (cheb(c.getX(), c.getZ(), first) > SPREAD) continue;
                boolean spaced = true;
                for (Site s : out) if (cheb(c.getX(), c.getZ(), s.center()) < SPACING) spaced = false;
                if (!spaced) continue;
                int ox = c.getX() - first.getX(), oz = c.getZ() - first.getZ();
                int along = Math.abs(ox * fd.getStepX() + oz * fd.getStepZ());
                int score = Math.abs(ox) + Math.abs(oz) + 3 * along + 4 * Math.abs(c.getY() - first.getY());
                if (score < bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            if (best == null) break;
            out.add(new Site(best, toward(best, rally)));
        }
        return out;
    }

    /** How far a spot is from the straight way between two points, in blocks. */
    private static double segDist(int x, int z, BlockPos a, BlockPos b) {
        double vx = b.getX() - a.getX(), vz = b.getZ() - a.getZ(), len = vx * vx + vz * vz;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((x - a.getX()) * vx + (z - a.getZ()) * vz) / len));
        double px = a.getX() + t * vx - x, pz = a.getZ() + t * vz - z;
        return Math.sqrt(px * px + pz * pz);
    }

    /** The walkable spot at or nearest the rally point. */
    @Nullable
    private static BlockPos startNear(ServerLevel level, BlockPos rally, int x0, int z0, int x1, int z1) {
        for (int r = 0; r <= 3; r++) {
            for (BlockPos p : BlockPos.betweenClosed(rally.offset(-r, -1, -r), rally.offset(r, 1, r))) {
                if (p.getX() < x0 || p.getX() > x1 || p.getZ() < z0 || p.getZ() > z1) continue;
                if (Shops.standable(level, p)) return p.immutable();
            }
        }
        return null;
    }

    /** Room overhead for an awning: nothing from head height to the roof's carpet. */
    private static boolean tall(ServerLevel level, BlockPos p) {
        return level.getBlockState(p.above(2)).isAir() && level.getBlockState(p.above(3)).isAir()
                && level.getBlockState(p.above(4)).isAir();
    }

    private static boolean clear(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** Nothing there a stall would bury: air, grass, flowers, snow. */
    private static boolean free(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return (s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty() && !s.hasBlockEntity();
    }

    private static int cheb(int x, int z, BlockPos p) {
        return Math.max(Math.abs(x - p.getX()), Math.abs(z - p.getZ()));
    }

    private static boolean nearAny(List<BlockPos> ps, int x, int z, int r) {
        for (BlockPos p : ps) if (p != null && !p.equals(BlockPos.ZERO) && cheb(x, z, p) <= r) return true;
        return false;
    }

    /** The side of a stall facing the rally point: its customers come from the middle of the yard. */
    private static Direction toward(BlockPos c, BlockPos rally) {
        int dx = rally.getX() - c.getX(), dz = rally.getZ() - c.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // ------------------------------------------------------------ the stall

    private static Look look(String kind, Direction side) {
        return switch (kind) {
            case "armoury" -> new Look(Blocks.LIGHT_GRAY_WOOL, Blocks.LIGHT_GRAY_CARPET,
                    Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, side), Blocks.SMITHING_TABLE.defaultBlockState(),
                    "Armoury", "");
            case "weapons" -> new Look(Blocks.BLACK_WOOL, Blocks.BLACK_CARPET,
                    Blocks.GRINDSTONE.defaultBlockState().setValue(GrindstoneBlock.FACE, AttachFace.FLOOR).setValue(GrindstoneBlock.FACING, side),
                    Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, side), "Weapon", "smith");
            case "fletcher" -> new Look(Blocks.LIME_WOOL, Blocks.LIME_CARPET,
                    Blocks.FLETCHING_TABLE.defaultBlockState(), Blocks.TARGET.defaultBlockState(), "Fletcher", "Bows");
            default -> new Look(Blocks.RED_WOOL, Blocks.RED_CARPET,
                    Blocks.HAY_BLOCK.defaultBlockState(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, side.getClockWise()),
                    "Quarter-", "master");
        };
    }

    private static final Map<Direction, Integer> SIGN_ROT = Map.of(Direction.SOUTH, 0, Direction.WEST, 4, Direction.NORTH, 8, Direction.EAST, 12);

    /** The towns' stall, facing its customers: posts, awning, counter, goods, lantern and sign. */
    public static void build(ServerLevel level, Site s, String kind) {
        Direction d = s.facing(), side = d.getClockWise();
        BlockPos c = s.center();
        Look k = look(kind, side);
        for (int a = -1; a <= 1; a += 2) {
            for (int f = -1; f <= 1; f += 2) {
                BlockPos post = c.relative(side, a).relative(d, f);
                for (int y = 0; y <= 2; y++) set(level, post.above(y), Blocks.SPRUCE_FENCE.defaultBlockState());
            }
        }
        for (int a = -1; a <= 1; a++) {
            for (int f = -1; f <= 1; f++) {
                BlockPos r = c.relative(side, a).relative(d, f);
                set(level, r.above(3), k.wool().defaultBlockState());
                set(level, r.above(4), k.carpet().defaultBlockState());
            }
        }
        BlockState counter = Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, d)
                .setValue(TrapDoorBlock.OPEN, false).setValue(TrapDoorBlock.HALF, Half.TOP);
        for (int a = -1; a <= 1; a++) set(level, c.relative(d).relative(side, a), counter);
        set(level, c.relative(side, -1), k.left());
        set(level, c.relative(side, 1), k.right());
        set(level, c.relative(d.getOpposite()), Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        set(level, c.above(2), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        BlockPos signAt = c.relative(d).above(2);
        set(level, signAt, Blocks.SPRUCE_HANGING_SIGN.defaultBlockState()
                .setValue(CeilingHangingSignBlock.ROTATION, SIGN_ROT.get(d)).setValue(CeilingHangingSignBlock.ATTACHED, false));
        if (level.getBlockEntity(signAt) instanceof SignBlockEntity sign) Shops.write(level, sign, "", k.line1(), k.line2(), "");
    }

    /** Set as a structure would: no neighbour updates, so the fences stay plain posts. */
    private static void set(ServerLevel level, BlockPos p, BlockState st) {
        level.setBlock(p, st, Block.UPDATE_CLIENTS);
    }

    private Market() {
    }
}
