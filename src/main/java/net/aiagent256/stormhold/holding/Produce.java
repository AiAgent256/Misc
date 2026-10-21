package net.aiagent256.stormhold.holding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Work you can watch. Miners drive a real tunnel out from the mine's gallery and everything they dig goes
 * into the stores; herds in the pastures breed until the pen is full and the butcher takes the surplus;
 * ripe crops in the fields are cut and replanted at dawn. Each runs a little while someone is near to see
 * it, and catches up once a day.
 */
public final class Produce {
    /** One slice (two wide, three tall) every this many ticks while a player is near the mine. */
    public static final int DIG_EVERY = 300;
    public static final int WATCH_RANGE = 96;
    /** Slices the miners get through in a day when nobody's watching. */
    public static final int DIG_PER_DAY = 12;
    /** Straight run before the tunnel turns; total slices before the seam is worked out. */
    public static final int RUN = 18, MAX_SLICES = 300;
    /** The tunnel ramps down one block every other slice until it is this deep under the gallery. */
    public static final int DEPTH = 16;
    public static final int HERD_CAP = 12;

    /** A mine's working face: where the next slice goes, which way the tunnel runs, and how far it has gone. */
    public static final class Dig {
        public BlockPos next;
        /** The base of the last slice actually cut; turns are measured from it. */
        @Nullable
        public BlockPos last;
        public int dx, dz, sx, sz;
        public int dug, run, turns, fails, down;
        public boolean done;

        Dig(BlockPos next, int dx, int dz, int sx, int sz) {
            this.next = next;
            this.dx = dx;
            this.dz = dz;
            this.sx = sx;
            this.sz = sz;
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.put("Next", NbtUtils.writeBlockPos(next));
            if (last != null) t.put("Last", NbtUtils.writeBlockPos(last));
            t.putIntArray("V", new int[]{dx, dz, sx, sz, dug, run, turns, fails, done ? 1 : 0, down});
            return t;
        }

        public static Dig load(CompoundTag t) {
            int[] v = t.getIntArray("V");
            Dig d = new Dig(NbtUtils.readBlockPos(t.getCompound("Next")), v[0], v[1], v[2], v[3]);
            if (t.contains("Last")) d.last = NbtUtils.readBlockPos(t.getCompound("Last"));
            d.dug = v[4];
            d.run = v[5];
            d.turns = v[6];
            d.fails = v[7];
            d.done = v[8] != 0;
            if (v.length > 9) d.down = v[9];
            return d;
        }
    }

    // ------------------------------------------------------------ ticking

    /** Once a second while players are near the holding. */
    public static void tick(ServerLevel level, HoldingManager mgr, Holding h, long now) {
        if (h.fief.isEmpty()) return;
        if (now % DIG_EVERY == 0) {
            for (Holding.FiefBuilding b : h.fief) {
                // with the castle's miners at the face, the tunnel moves by their picks (see Work), not on its own
                if (!b.piece().equals("mine") || !watched(level, b.box()) || Work.atTheFace(h)) continue;
                Dig d = dig(level, h, b);
                if (d != null && !d.done) {
                    Map<String, Integer> made = new HashMap<>();
                    if (slice(level, d, made)) credit(h, made);
                    mgr.setDirty();
                }
            }
        }
        if (now % 2400 == 1200) {
            for (Holding.FiefBuilding b : h.fief) {
                if (b.piece().equals("pasture") && watched(level, b.box())) breed(level, b, 1);
            }
        }
    }

    /** The day's work, at the census. Returns what was made (already in the stores). */
    public static Map<String, Integer> daily(ServerLevel level, HoldingManager mgr, Holding h) {
        Map<String, Integer> made = new java.util.LinkedHashMap<>();
        for (Holding.FiefBuilding b : h.fief) {
            switch (b.piece()) {
                case "mine" -> {
                    if (!h.isPlayerOwned()) continue;
                    Dig d = dig(level, h, b);
                    for (int i = 0; d != null && !d.done && i < DIG_PER_DAY; i++) slice(level, d, made);
                }
                case "pasture" -> {
                    breed(level, b, 2);
                    cull(level, b, made);
                    shear(level, b, made);
                }
                case "field" -> harvest(level, b.box(), made);
                case "orchard" -> made.merge("apple", 3, Integer::sum);
                default -> {
                }
            }
        }
        credit(h, made);
        mgr.setDirty();
        return made;
    }

    private static boolean watched(ServerLevel level, BoundingBox box) {
        double cx = box.getCenter().getX(), cz = box.getCenter().getZ();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double dx = p.getX() - cx, dz = p.getZ() - cz;
            if (dx * dx + dz * dz < WATCH_RANGE * WATCH_RANGE) return true;
        }
        return false;
    }

    private static void credit(Holding h, Map<String, Integer> made) {
        for (var e : made.entrySet()) if (e.getValue() > 0) h.addStock(new ResourceLocation("minecraft", e.getKey()), e.getValue());
    }

    // ------------------------------------------------------------ the mine

    /** The mine's face, found from the gallery the first time it's needed. */
    @Nullable
    public static Dig dig(ServerLevel level, Holding h, Holding.FiefBuilding b) {
        long key = new BlockPos(b.box().minX(), b.box().minY(), b.box().minZ()).asLong();
        Dig d = h.digs.get(key);
        if (d == null) {
            d = findFace(level, b.box());
            if (d == null) return null;
            h.digs.put(key, d);
        }
        return d;
    }

    /**
     * The gallery at the bottom of the shaft is the air at the box's floor; it runs along its longer side,
     * and the face is at the end away from the shaft.
     */
    @Nullable
    public static Dig findFace(ServerLevel level, BoundingBox box) {
        if (!level.isLoaded(new BlockPos(box.minX(), box.minY(), box.minZ())) || !level.isLoaded(new BlockPos(box.maxX(), box.minY(), box.maxZ())))
            return null;
        int gy = box.minY() + 1, sy = box.minY() + 3;
        List<BlockPos> gallery = new ArrayList<>(), shaft = new ArrayList<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                if (level.getBlockState(new BlockPos(x, gy, z)).isAir() || level.getBlockState(new BlockPos(x, gy, z)).is(Blocks.LADDER)) {
                    gallery.add(new BlockPos(x, box.minY(), z));
                }
                BlockState ss = level.getBlockState(new BlockPos(x, sy, z));
                if (ss.isAir() || ss.is(Blocks.LADDER)) shaft.add(new BlockPos(x, box.minY(), z));
            }
        }
        if (gallery.size() < 6) return null;
        int gx0 = gallery.stream().mapToInt(BlockPos::getX).min().getAsInt(), gx1 = gallery.stream().mapToInt(BlockPos::getX).max().getAsInt();
        int gz0 = gallery.stream().mapToInt(BlockPos::getZ).min().getAsInt(), gz1 = gallery.stream().mapToInt(BlockPos::getZ).max().getAsInt();
        double shx = shaft.isEmpty() ? (gx0 + gx1) / 2.0 : shaft.stream().mapToInt(BlockPos::getX).average().orElse(0);
        double shz = shaft.isEmpty() ? (gz0 + gz1) / 2.0 : shaft.stream().mapToInt(BlockPos::getZ).average().orElse(0);
        int y = box.minY();
        if (gx1 - gx0 >= gz1 - gz0) {
            boolean east = Math.abs(gx1 - shx) >= Math.abs(gx0 - shx);
            return new Dig(new BlockPos(east ? gx1 + 1 : gx0 - 1, y, gz0), east ? 1 : -1, 0, 0, 1);
        }
        boolean south = Math.abs(gz1 - shz) >= Math.abs(gz0 - shz);
        return new Dig(new BlockPos(gx0, y, south ? gz1 + 1 : gz0 - 1), 0, south ? 1 : -1, 1, 0);
    }

    private static boolean diggable(BlockState s) {
        return s.isAir() || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(Blocks.GRAVEL) || s.is(BlockTags.SAND)
                || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE) || s.is(Blocks.COBBLED_DEEPSLATE) || s.is(Blocks.CLAY)
                || s.is(Blocks.CALCITE) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.POINTED_DRIPSTONE)
                || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES) || s.is(BlockTags.GOLD_ORES)
                || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES) || s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.EMERALD_ORES)
                || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.RAW_IRON_BLOCK) || s.is(Blocks.COAL_BLOCK) || s.is(BlockTags.LEAVES)
                || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.GLOW_LICHEN) || s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT);
    }

    /** What a miner gets out of a block. */
    private static void takings(BlockState s, Map<String, Integer> made) {
        String item = null;
        int n = 1;
        if (s.is(BlockTags.COAL_ORES) || s.is(Blocks.COAL_BLOCK)) item = "coal";
        else if (s.is(BlockTags.IRON_ORES) || s.is(Blocks.RAW_IRON_BLOCK)) item = "iron_ingot";
        else if (s.is(BlockTags.COPPER_ORES)) {
            item = "copper_ingot";
            n = 2;
        } else if (s.is(BlockTags.GOLD_ORES)) item = "gold_ingot";
        else if (s.is(BlockTags.REDSTONE_ORES)) {
            item = "redstone";
            n = 4;
        } else if (s.is(BlockTags.LAPIS_ORES)) {
            item = "lapis_lazuli";
            n = 4;
        } else if (s.is(BlockTags.DIAMOND_ORES)) item = "diamond";
        else if (s.is(BlockTags.EMERALD_ORES)) item = "emerald";
        else if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.COBBLED_DEEPSLATE)
                || s.is(Blocks.MOSSY_COBBLESTONE)) item = "cobblestone";
        if (item != null) made.merge(item, n, Integer::sum);
    }

    /** Can a slice be cut at base, two wide along (sx, sz)? Loaded, a roof of rock overhead, no water or lava, nothing anybody built. */
    private static boolean cuttable(ServerLevel level, BlockPos base, int sx, int sz) {
        for (BlockPos c : new BlockPos[]{base, base.offset(sx, 0, sz)}) {
            if (!level.isLoaded(c)) return false;
            BlockState roof = level.getBlockState(c.above(3));
            if (roof.isAir() || !roof.getFluidState().isEmpty()) return false;
            for (int y = 0; y < 3; y++) {
                BlockPos p = c.above(y);
                BlockState s = level.getBlockState(p);
                if (!s.getFluidState().isEmpty() || !diggable(s)) return false;
                for (BlockPos n : new BlockPos[]{p.north(), p.south(), p.east(), p.west(), p.above()}) {
                    if (!level.getBlockState(n).getFluidState().isEmpty()) return false;
                }
            }
        }
        return true;
    }

    /** Drive the tunnel one slice forward. False if the miners had to turn or are done. */
    public static boolean slice(ServerLevel level, Dig d, Map<String, Integer> made) {
        if (d.done) return false;
        if (d.dug >= MAX_SLICES) {
            d.done = true;
            return false;
        }
        BlockPos base = d.next;
        if (!level.isLoaded(base)) return false;
        if (!cuttable(level, base, d.sx, d.sz)) return turn(level, d, true);
        BlockPos[] cols = {base, base.offset(d.sx, 0, d.sz)};
        for (BlockPos c : cols) {
            for (int y = 0; y < 3; y++) {
                BlockPos p = c.above(y);
                BlockState s = level.getBlockState(p);
                if (s.isAir()) continue;
                takings(s, made);
                level.destroyBlock(p, false);
            }
            BlockPos floor = c.below();
            if (level.getBlockState(floor).isAir() || !level.getBlockState(floor).getFluidState().isEmpty()) {
                level.setBlock(floor, Blocks.COBBLESTONE.defaultBlockState(), 2);
            }
        }
        // timber frame every four slices: posts in the walls, a beam across the roof
        if (d.dug % 4 == 0) {
            BlockPos left = base.offset(-d.sx, 0, -d.sz), right = base.offset(2 * d.sx, 0, 2 * d.sz);
            for (BlockPos post : new BlockPos[]{left, right}) {
                for (int y = 0; y < 3; y++) {
                    BlockPos p = post.above(y);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir() || diggable(s)) {
                        if (!s.isAir()) takings(s, made);
                        level.setBlock(p, Blocks.SPRUCE_LOG.defaultBlockState(), 2);
                    }
                }
            }
            for (BlockPos c : new BlockPos[]{left, cols[0], cols[1], right}) {
                BlockPos p = c.above(3);
                BlockState s = level.getBlockState(p);
                if (s.isAir() || diggable(s)) level.setBlock(p, Blocks.SPRUCE_PLANKS.defaultBlockState(), 2);
            }
        }
        if (d.dug % 8 == 2) {
            BlockPos lamp = cols[1].above(2);
            if (level.getBlockState(lamp.above()).isSolid()) {
                level.setBlock(lamp, Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true), 3);
            }
        }
        level.setBlock(cols[0], Blocks.RAIL.defaultBlockState(), 3);
        level.playSound(null, base, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.8F, 0.8F);
        // ramp down to working depth: a step every other slice, so men and carts can climb it
        boolean step = d.down < DEPTH && d.dug % 2 == 1;
        if (step) d.down++;
        d.last = base;
        d.next = base.offset(d.dx, step ? -1 : 0, d.dz);
        d.dug++;
        d.fails = 0;
        if (++d.run >= RUN) turn(level, d, false);
        return true;
    }

    /**
     * Turn the tunnel a quarter, from the last slice cut. At the end of a run the sides alternate so the tunnel
     * snakes onward instead of circling back; when the face is blocked (open air, water, something built) the
     * men try one side, then the other, and give up only when both are rock they can't cut.
     */
    private static boolean turn(ServerLevel level, Dig d, boolean blocked) {
        BlockPos last = d.last != null ? d.last : d.next.offset(-d.dx, 0, -d.dz);
        if (d.next.getY() < last.getY() && d.down > 0) d.down--;     // the step down that was never cut
        boolean rightFirst = blocked ? d.fails % 2 == 0 : d.turns % 2 == 0;
        int[][] options = rightFirst ? new int[][]{{1}, {-1}} : new int[][]{{-1}, {1}};
        for (int[] o : options) {
            boolean right = o[0] > 0;
            int ndx = right ? d.sx : -d.sx, ndz = right ? d.sz : -d.sz;
            int nsx = -d.dx, nsz = -d.dz;
            BlockPos next = right ? last.offset(2 * d.sx, 0, 2 * d.sz) : last.offset(-d.sx, 0, -d.sz);
            if (!cuttable(level, next, nsx, nsz)) continue;
            d.dx = ndx;
            d.dz = ndz;
            d.sx = nsx;
            d.sz = nsz;
            d.next = next;
            d.run = 0;
            d.turns++;
            if (blocked && ++d.fails >= 6) d.done = true;
            return false;
        }
        if (blocked) {
            d.done = true;      // walled in on every side
        } else {
            d.run = 0;          // neither side will cut: carry straight on
        }
        return false;
    }

    // ------------------------------------------------------------ the herds

    private static List<Animal> herd(ServerLevel level, BoundingBox box) {
        return level.getEntitiesOfClass(Animal.class, AABB.of(box).inflate(1, 4, 1), Entity::isAlive);
    }

    /** Up to {@code max} pairs in the pen have young, with hearts, while there's room. */
    public static int breed(ServerLevel level, Holding.FiefBuilding pen, int max) {
        List<Animal> herd = herd(level, pen.box());
        if (herd.size() >= HERD_CAP) return 0;
        Map<EntityType<?>, List<Animal>> by = new HashMap<>();
        for (Animal a : herd) if (!a.isBaby()) by.computeIfAbsent(a.getType(), k -> new ArrayList<>()).add(a);
        int born = 0;
        List<List<Animal>> kinds = new ArrayList<>(by.values());
        kinds.sort(Comparator.comparingInt(List::size));
        for (List<Animal> kind : kinds) {
            if (born >= max || herd.size() + born >= HERD_CAP) break;
            if (kind.size() < 2) continue;
            Animal mother = kind.get(level.random.nextInt(kind.size()));
            Entity child = mother.getType().create(level);
            if (!(child instanceof AgeableMob baby)) continue;
            baby.setAge(-24000);
            baby.moveTo(mother.getX(), mother.getY(), mother.getZ(), level.random.nextFloat() * 360F, 0);
            baby.finalizeSpawn(level, level.getCurrentDifficultyAt(mother.blockPosition()), MobSpawnType.BREEDING, null, null);
            baby.setPersistenceRequired();
            baby.setAge(-24000);
            level.addFreshEntity(baby);
            level.sendParticles(ParticleTypes.HEART, mother.getX(), mother.getY() + 0.8, mother.getZ(), 6, 0.4, 0.3, 0.4, 0.1);
            born++;
        }
        return born;
    }

    /** Past the pen's size, the butcher takes the commonest grown beasts. */
    public static int cull(ServerLevel level, Holding.FiefBuilding pen, Map<String, Integer> made) {
        List<Animal> herd = herd(level, pen.box());
        int over = herd.size() - HERD_CAP;
        if (over <= 0) return 0;
        Map<EntityType<?>, List<Animal>> by = new HashMap<>();
        for (Animal a : herd) if (!a.isBaby()) by.computeIfAbsent(a.getType(), k -> new ArrayList<>()).add(a);
        int taken = 0;
        while (taken < over) {
            List<Animal> most = by.values().stream().filter(l -> l.size() > 2).max(Comparator.comparingInt(List::size)).orElse(null);
            if (most == null) break;
            Animal a = most.remove(most.size() - 1);
            if (a instanceof Cow) {
                made.merge("cooked_beef", 3, Integer::sum);
                made.merge("leather", 1, Integer::sum);
            } else if (a instanceof Sheep) {
                made.merge("cooked_mutton", 2, Integer::sum);
                made.merge("white_wool", 1, Integer::sum);
            } else if (a instanceof Pig) {
                made.merge("cooked_porkchop", 3, Integer::sum);
            } else if (a instanceof Chicken) {
                made.merge("cooked_chicken", 1, Integer::sum);
                made.merge("feather", 2, Integer::sum);
            } else {
                made.merge("leather", 1, Integer::sum);
            }
            a.discard();
            taken++;
        }
        return taken;
    }

    /** Shear the sheep that have grown their wool back; they'll be bare in the pen until they've grazed. */
    public static int shear(ServerLevel level, Holding.FiefBuilding pen, Map<String, Integer> made) {
        int n = 0;
        for (Animal a : herd(level, pen.box())) {
            if (a instanceof Sheep s && s.readyForShearing()) {
                s.setSheared(true);
                made.merge("white_wool", 1 + level.random.nextInt(2), Integer::sum);
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------ the fields

    /** Cut what's ripe and plant it again. */
    public static int harvest(ServerLevel level, BoundingBox box, Map<String, Integer> made) {
        int cut = 0;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!level.isLoaded(p)) return cut;
                    BlockState s = level.getBlockState(p);
                    if (!(s.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(s)) continue;
                    Block b = s.getBlock();
                    if (b == Blocks.WHEAT) made.merge("wheat", 1, Integer::sum);
                    else if (b == Blocks.CARROTS) made.merge("carrot", 2, Integer::sum);
                    else if (b == Blocks.POTATOES) made.merge("potato", 2, Integer::sum);
                    else if (b == Blocks.BEETROOTS) made.merge("beetroot", 1, Integer::sum);
                    else made.merge("wheat", 1, Integer::sum);
                    level.setBlock(p, crop.getStateForAge(0), 2);
                    cut++;
                }
            }
        }
        return cut;
    }

    private Produce() {
    }
}
