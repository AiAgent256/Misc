package net.aiagent256.stormhold.holding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Work you can watch. While you're near one of your castles, by day, the people set to work on the War Table do it
 * in front of you: the miners walk to the fief's mine, go down, and drive the tunnel with their picks (the face cracks
 * as they swing and a slice comes down when they've cut it); the smiths walk to the castle's anvil (or its forge) and
 * hammer. At dusk they come up and go home. Away from the castle the work goes on by the day's count as before.
 */
public final class Work {
    /** Pick strokes, all the men at the face together, to bring a slice down (two blocks wide, three high). */
    public static final int STROKES_PER_SLICE = 24;
    /** Room at the face: two columns, three deep. */
    static final int FACE_ROOM = 6;
    /** A man this close to the mine's head goes down the ladder. */
    static final double GO_DOWN = 8.0;
    /** How far from the War Table the smiths look for an anvil or a forge. */
    static final int FORGE_LOOK = 32;
    /** Persistent tag on a villager holding a tool we gave him. */
    static final String TOOL = "StormholdTool";
    /** Persistent tag on a miner who went down the mine: he comes up the same way, however far the tunnel has run. */
    static final String BELOW = "StormholdBelow";

    /** Strokes on the face of each holding's mine so far, by holding. */
    private static final Map<UUID, Integer> STROKES = new HashMap<>();
    /** Where each castle's smiths work, and when that was last looked for. */
    private static final Map<UUID, BlockPos> FORGE = new HashMap<>();
    private static final Map<UUID, Long> FORGE_LOOKED = new HashMap<>();

    /** Every ten ticks, for a castle of a player's that a player is near. */
    public static void tick(ServerLevel level, HoldingManager mgr, Holding h, long now) {
        if (!h.isPlayerOwned() || h.id == null || h.box == null) return;
        List<Villager> miners = new ArrayList<>(), smiths = new ArrayList<>();
        BlockPos c = h.center();
        AABB area = new AABB(c).inflate(Fief.REACH, 80, Fief.REACH);
        for (Villager v : level.getEntitiesOfClass(Villager.class, area, v -> v.isAlive() && !v.isBaby())) {
            var d = v.getPersistentData();
            if (!d.contains(Labour.TAG) || !d.hasUUID(Labour.HOLDING_TAG) || !h.id.equals(d.getUUID(Labour.HOLDING_TAG))) {
                // let go from the work: up from the mine if he's down it, tools away
                if (d.getBoolean(BELOW)) surface(level, v, mine(h));
                else if (d.getBoolean(TOOL)) undress(v);
                continue;
            }
            if (Labour.MINER.equals(d.getString(Labour.TAG))) miners.add(v);
            else if (Labour.SMITH.equals(d.getString(Labour.TAG))) smiths.add(v);
        }
        boolean day = level.isDay();
        miners(level, mgr, h, miners, day, now);
        smiths(level, h, smiths, day, now);
    }

    /** True while men of this castle are cutting at its mine's face (the tunnel then moves only by their picks). */
    public static boolean atTheFace(Holding h) {
        return STROKES.containsKey(h.id);
    }

    // ------------------------------------------------------------ the mine

    @Nullable
    static Holding.FiefBuilding mine(Holding h) {
        for (Holding.FiefBuilding b : h.fief) if (b.piece().equals("mine")) return b;
        return null;
    }

    /** Where the mine's head is on the surface: its middle, at ground level. */
    static BlockPos head(ServerLevel level, Holding.FiefBuilding mine) {
        BlockPos mid = mine.box().getCenter();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mid.getX(), mid.getZ());
        BlockPos top = new BlockPos(mid.getX(), y, mid.getZ());
        for (int r = 2; r <= 6; r += 2) {
            for (int[] o : new int[][]{{r, 0}, {-r, 0}, {0, r}, {0, -r}}) {
                int x = mid.getX() + o[0], z = mid.getZ() + o[1];
                BlockPos p = Garrisons.standable(level, new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z));
                if (p != null) return p;
            }
        }
        return top;
    }

    static void miners(ServerLevel level, HoldingManager mgr, Holding h, List<Villager> men, boolean day, long now) {
        Holding.FiefBuilding mine = mine(h);
        Produce.Dig d = mine == null ? null : Produce.dig(level, h, mine);
        if (men.isEmpty() || d == null || d.done || !level.isLoaded(d.next)) {
            STROKES.remove(h.id);
            for (Villager v : men) if (v.getPersistentData().getBoolean(TOOL) || v.getPersistentData().getBoolean(BELOW)) surface(level, v, mine);
            return;
        }
        // where a man stands to swing: the tunnel floor behind the face, the two columns, up to three deep
        List<BlockPos> spots = new ArrayList<>();
        for (int back = 1; back <= 3 && spots.size() < FACE_ROOM; back++) {
            for (int col = 0; col < 2; col++) {
                BlockPos s = d.next.offset(-d.dx * back + d.sx * col, 0, -d.dz * back + d.sz * col);
                if (Garrisons.canStand(level, s)) spots.add(s);
            }
        }
        int cutting = 0, i = 0;
        for (Villager v : men) {
            if (!day || i >= spots.size()) {
                // night, or no room at the face: up the ladder and home
                if (v.getPersistentData().getBoolean(TOOL) || v.getPersistentData().getBoolean(BELOW)) surface(level, v, mine);
                continue;
            }
            BlockPos spot = spots.get(i++);
            Vec3 at = Vec3.atBottomCenterOf(spot);
            if (v.distanceToSqr(at) > 3.0 * 3.0) {
                double dx = v.getX() - mine.box().getCenter().getX(), dz = v.getZ() - mine.box().getCenter().getZ();
                if (v.getPersistentData().getBoolean(BELOW) || dx * dx + dz * dz < GO_DOWN * GO_DOWN) {
                    // at the head of the shaft (or already below and wandered off the face): down to his place
                    v.teleportTo(at.x, at.y, at.z);
                    v.getNavigation().stop();
                    v.getPersistentData().putBoolean(BELOW, true);
                } else {
                    walk(v, head(level, mine), 2);
                }
                continue;
            }
            dress(v, Items.IRON_PICKAXE);
            walk(v, spot, 0);
            BlockPos face = d.next.offset(d.sx * (i % 2 == 0 ? 1 : 0), 1, d.sz * (i % 2 == 0 ? 1 : 0));
            v.getLookControl().setLookAt(Vec3.atCenterOf(face));
            if ((now / 10 + v.getId()) % 2 == 0) {
                v.swing(InteractionHand.MAIN_HAND);
                BlockState s = level.getBlockState(face);
                if (!s.isAir()) {
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), face.getX() + 0.5 - d.dx * 0.5, face.getY() + 0.5,
                            face.getZ() + 0.5 - d.dz * 0.5, 4, 0.2, 0.2, 0.2, 0.05);
                    level.playSound(null, face, s.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.5F, 0.8F + level.random.nextFloat() * 0.3F);
                }
                cutting++;
            }
        }
        if (cutting == 0) {
            if (!day) STROKES.remove(h.id);
            return;
        }
        int strokes = STROKES.merge(h.id, cutting, Integer::sum);
        BlockPos[] cols = {d.next, d.next.offset(d.sx, 0, d.sz)};
        int stage = Math.min(9, strokes * 10 / STROKES_PER_SLICE);
        for (BlockPos col : cols) for (int y = 0; y < 3; y++) crack(level, col.above(y), stage);
        if (strokes < STROKES_PER_SLICE) return;
        STROKES.put(h.id, 0);
        for (BlockPos col : cols) for (int y = 0; y < 3; y++) crack(level, col.above(y), -1);
        Map<String, Integer> made = new HashMap<>();
        Produce.slice(level, d, made);
        for (var e : made.entrySet()) if (e.getValue() > 0) h.addStock(new net.minecraft.resources.ResourceLocation("minecraft", e.getKey()), e.getValue());
        mgr.setDirty();
    }

    /** Up from the mine to its head, tool put away, free to go home. */
    static void surface(ServerLevel level, Villager v, @Nullable Holding.FiefBuilding mine) {
        if (mine != null && v.getPersistentData().getBoolean(BELOW)) {
            BlockPos up = head(level, mine);
            v.teleportTo(up.getX() + 0.5, up.getY(), up.getZ() + 0.5);
        }
        v.getPersistentData().remove(BELOW);
        undress(v);
        v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }

    private static void crack(ServerLevel level, BlockPos p, int stage) {
        long k = p.asLong();
        level.destroyBlockProgress(0x5700000 ^ (int) (k ^ (k >>> 32)), p, stage);
    }

    // ------------------------------------------------------------ the forge

    /** The castle's anvil, smithing table or forge nearest its War Table, looked for every five minutes. Null if none. */
    @Nullable
    public static BlockPos forge(ServerLevel level, Holding h, long now) {
        Long looked = FORGE_LOOKED.get(h.id);
        if (looked != null && now - looked < 6000) return FORGE.get(h.id);
        FORGE_LOOKED.put(h.id, now);
        BlockPos from = h.warTablePos.equals(BlockPos.ZERO) ? h.center() : h.warTablePos;
        BlockPos best = null;
        int bestRank = Integer.MAX_VALUE;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -8; dy <= 8; dy++) {
            for (int dx = -FORGE_LOOK; dx <= FORGE_LOOK; dx++) {
                for (int dz = -FORGE_LOOK; dz <= FORGE_LOOK; dz++) {
                    p.set(from.getX() + dx, from.getY() + dy, from.getZ() + dz);
                    if (!level.isLoaded(p)) continue;
                    BlockState s = level.getBlockState(p);
                    int rank = s.is(Blocks.ANVIL) || s.is(Blocks.CHIPPED_ANVIL) || s.is(Blocks.DAMAGED_ANVIL) ? 0
                            : s.is(Blocks.SMITHING_TABLE) ? 1 : s.is(Blocks.BLAST_FURNACE) ? 2 : s.is(Blocks.FURNACE) ? 3 : -1;
                    if (rank < 0) continue;
                    double d = dx * dx + dy * dy * 4 + dz * dz;
                    if (rank < bestRank || (rank == bestRank && d < bestD)) {
                        bestRank = rank;
                        bestD = d;
                        best = p.immutable();
                    }
                }
            }
        }
        if (best == null) FORGE.remove(h.id);
        else FORGE.put(h.id, best);
        return best;
    }

    static void smiths(ServerLevel level, Holding h, List<Villager> men, boolean day, long now) {
        if (men.isEmpty()) return;
        BlockPos forge = day ? forge(level, h, now) : null;
        if (forge == null) {
            for (Villager v : men) {
                if (v.getPersistentData().getBoolean(TOOL)) {
                    undress(v);
                    v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                }
            }
            return;
        }
        // places round the anvil: its four sides first, then a step further out
        List<BlockPos> spots = new ArrayList<>();
        for (int r = 1; r <= 2 && spots.size() < men.size(); r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r || (r == 1 && dx != 0 && dz != 0)) continue;
                    BlockPos s = Garrisons.standable(level, forge.offset(dx, 0, dz));
                    if (s != null && !spots.contains(s) && Math.abs(s.getY() - forge.getY()) <= 1) spots.add(s);
                }
            }
        }
        int i = 0;
        for (Villager v : men) {
            if (i >= spots.size()) break;
            BlockPos spot = spots.get(i++);
            walk(v, spot, 0);
            if (v.distanceToSqr(Vec3.atBottomCenterOf(spot)) > 2.5 * 2.5) continue;
            dress(v, hammer());
            v.getLookControl().setLookAt(Vec3.atCenterOf(forge));
            if ((now / 10 + v.getId()) % 3 == 0) {
                v.swing(InteractionHand.MAIN_HAND);
                boolean anvil = level.getBlockState(forge).is(Blocks.ANVIL) || level.getBlockState(forge).is(Blocks.CHIPPED_ANVIL)
                        || level.getBlockState(forge).is(Blocks.DAMAGED_ANVIL);
                level.playSound(null, forge, anvil ? SoundEvents.ANVIL_USE : SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 0.35F,
                        0.9F + level.random.nextFloat() * 0.3F);
                level.sendParticles(ParticleTypes.LAVA, forge.getX() + 0.5, forge.getY() + 1.05, forge.getZ() + 0.5, 1, 0.1, 0.0, 0.1, 0.0);
                level.sendParticles(ParticleTypes.CRIT, forge.getX() + 0.5, forge.getY() + 1.1, forge.getZ() + 0.5, 3, 0.15, 0.05, 0.15, 0.1);
            }
        }
    }

    /** An Epic Knights war hammer when the pack has one, else an iron axe. */
    static Item hammer() {
        for (String n : new String[]{"iron_heavy_war_hammer", "iron_lucerne_hammer", "iron_heavymace"}) {
            Item i = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("magistuarmory", n));
            if (i != null && i != Items.AIR) return i;
        }
        return Items.IRON_AXE;
    }

    // ------------------------------------------------------------ hands and feet

    /** A villager goes where he's sent: his walk target, renewed every ten ticks so his own business doesn't take him off. */
    public static void walk(Villager v, BlockPos to, int closeEnough) {
        v.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(to, 0.6F, closeEnough));
    }

    /** A tool in his hand (and never dropped), marked as ours. */
    public static void dress(Villager v, Item tool) {
        if (!v.getMainHandItem().is(tool)) {
            v.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(tool));
            v.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        }
        v.getPersistentData().putBoolean(TOOL, true);
    }

    public static void undress(Villager v) {
        if (!v.getPersistentData().getBoolean(TOOL)) return;
        v.getPersistentData().remove(TOOL);
        v.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    private Work() {
    }
}
