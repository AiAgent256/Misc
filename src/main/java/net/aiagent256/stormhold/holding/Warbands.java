package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Illager warbands: barbarians who come for the castles, towns and cities a player holds, every several
 * days, a day's warning first. They come over the hill with a horn, mass at the gate and try every way in:
 * a ravager batters the gate down, ladders go up against the curtain wall where they're held, and if the
 * walls still hold, sappers blow a breach. Inside, they go for the banner stand and anyone in the way.
 *
 * <p>Break them and their spoils go to the castle's stores. If they take the yard (the garrison dead or
 * gone and the illagers inside), they sack it: a quarter of the stores carried off and a third of the
 * garrison lost, though the castle stays yours. While nobody's there to see, it's fought out by numbers,
 * as the houses' raids are. Their men come kitted for the frontier zone they march in, and their
 * captain carries the illager banner: kill him and you've a Bad Omen to carry into someone's village.
 */
public final class Warbands {
    public static final String SIDE = "illagers";
    public static final String TAG = "StormholdWarband";
    private static final String LADDER = "StormholdLadder", LADDER_WALL = "StormholdLadderWall", LADDER_TOP = "StormholdLadderTop";
    private static final double MARCH_SPEED = 3.0;
    public static final String CAPTAIN = "captain";

    /** Each kind's weight in the fighting, before the frontier makes them harder. */
    private static final Map<String, Double> STRENGTH = Map.ofEntries(
            Map.entry(CAPTAIN, 2.0), Map.entry("minecraft:pillager", 1.0), Map.entry("minecraft:vindicator", 2.0),
            Map.entry("minecraft:witch", 1.0), Map.entry("minecraft:evoker", 4.0), Map.entry("minecraft:ravager", 6.0),
            Map.entry("illagerinvasion:basher", 2.0), Map.entry("illagerinvasion:provoker", 2.0),
            Map.entry("illagerinvasion:marauder", 1.5), Map.entry("illagerinvasion:sorcerer", 3.0),
            Map.entry("illagerinvasion:inquisitor", 4.0), Map.entry("illagerinvasion:invoker", 8.0));

    public static double strength(String kind) {
        return STRENGTH.getOrDefault(kind, 1.5);
    }

    // ------------------------------------------------------------ when they come

    public static long delay(ServerLevel level) {
        int lo = Config.WARBAND_MIN_DAYS.get(), hi = Math.max(lo, Config.WARBAND_MAX_DAYS.get());
        return 24000L * (lo + level.random.nextInt(hi - lo + 1));
    }

    /** Daily for every place a player holds: the warning, then the warband. */
    public static void schedule(ServerLevel level, HoldingManager mgr, Holding h, long now) {
        if (!Config.WARBANDS_ENABLED.get() || !h.isPlayerOwned()) return;
        if (h.nextWarband <= 0) {
            h.nextWarband = now + delay(level);
            mgr.setDirty();
            return;
        }
        if (active(mgr, h.id)) return;
        if (!h.warbandWarned && now >= h.nextWarband - 24000) {
            h.warbandWarned = true;
            HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("Scouts sight an illager warband gathering in the hills near "
                    + h.name + ". They'll come within a day.").withStyle(ChatFormatting.RED));
            mgr.setDirty();
        }
        if (now < h.nextWarband) return;
        // one fight at a time for a lord: while any of his places is attacked, this one waits a day
        for (Campaign c : mgr.campaigns) {
            if (c.stage == Campaign.Stage.DONE || c.target == null) continue;
            if (c.kind != Campaign.Kind.RAID && c.kind != Campaign.Kind.ILLAGERS) continue;
            Holding other = mgr.holdings.get(c.target);
            if (other != null && other.owner.equals(h.owner)) {
                h.nextWarband = now + 24000;
                mgr.setDirty();
                return;
            }
        }
        launch(level, mgr, h);
        h.nextWarband = now + delay(level);
        h.warbandWarned = false;
        mgr.setDirty();
    }

    public static boolean active(HoldingManager mgr, UUID holding) {
        for (Campaign c : mgr.campaigns)
            if (c.kind == Campaign.Kind.ILLAGERS && c.stage != Campaign.Stage.DONE && holding.equals(c.target)) return true;
        return false;
    }

    /** The warband for a place: bigger for bigger places and farther out, with the frontier's worst out there. */
    public static Map<String, Integer> muster(ServerLevel level, Holding h) {
        int zone = Frontier.zone(level, h.center());
        int n = (h.isTown() ? 8 : h.isCity() ? 22 : h.tier >= 3 ? 14 : h.tier == 2 ? 9 : 6) + (zone - 1);
        boolean ii = ForgeRegistries.ENTITY_TYPES.containsKey(new ResourceLocation("illagerinvasion", "basher"));
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put(CAPTAIN, 1);
        int left = n - 1;
        int ravagers = h.tier >= 2 || h.isCity() ? (h.isCity() || zone >= 5 ? 2 : 1) : 0;
        int evokers = h.tier >= 2 ? (h.isCity() ? 2 : 1) : 0;
        int witches = Math.max(1, n / 8);
        m.put("minecraft:ravager", ravagers);
        m.put("minecraft:evoker", evokers);
        m.put("minecraft:witch", witches);
        left -= ravagers + evokers + witches;
        if (ii) {
            int special = Math.max(1, left / 4);
            m.put("illagerinvasion:basher", special / 2 + special % 2);
            m.put("illagerinvasion:provoker", special / 2);
            if (zone >= 3) m.put("illagerinvasion:sorcerer", 1);
            if (zone >= 5) m.put("illagerinvasion:inquisitor", 1);
            if (h.isCity() && zone >= 6) m.put("illagerinvasion:invoker", 1);
            left -= special + (zone >= 3 ? 1 : 0) + (zone >= 5 ? 1 : 0) + (h.isCity() && zone >= 6 ? 1 : 0);
        }
        left = Math.max(2, left);
        m.put("minecraft:vindicator", left * 2 / 5);
        m.put("minecraft:pillager", left - left * 2 / 5);
        m.values().removeIf(v -> v <= 0);
        return m;
    }

    public static Campaign launch(ServerLevel level, HoldingManager mgr, Holding h) {
        Campaign c = new Campaign();
        c.kind = Campaign.Kind.ILLAGERS;
        c.side = SIDE;
        c.target = h.id;
        c.tier = h.tier;
        c.band.putAll(muster(level, h));
        c.spoils = 2 * c.total() + 10;   // two emeralds a head, and the captain's purse
        double ang = level.random.nextDouble() * Math.PI * 2;
        BlockPos center = h.center();
        int reach = Math.max(h.box.getXSpan(), h.box.getZSpan()) / 2 + 48;
        c.from = new BlockPos(center.getX() + (int) (Math.cos(ang) * reach), center.getY(), center.getZ() + (int) (Math.sin(ang) * reach));
        c.to = Campaigns.gateOutside(h);
        mgr.campaigns.add(c);
        HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("An illager warband of " + c.total() + " marches on " + h.name
                + " from the " + Guide.way(center, c.from).replaceAll("^\\d+ blocks ", "") + "!").withStyle(ChatFormatting.RED));
        Chronicle.log(level, mgr, Chronicle.WAR, "Illagers march on " + h.name + ", " + c.total() + " strong.", h.ownerPlayer());
        mgr.setDirty();
        return c;
    }

    // ------------------------------------------------------------ the fight (once a second)

    public static void tick(ServerLevel level, HoldingManager mgr, Campaign c) {
        Holding h = c.target != null ? mgr.holdings.get(c.target) : null;
        if (h == null || !h.isPlayerOwned()) {
            discardLive(level, c);
            c.stage = Campaign.Stage.DONE;
            return;
        }
        prune(level, c);
        if (c.total() <= 0) {
            broken(level, mgr, h, c, c.spoils, true);
            return;
        }
        boolean near = Campaigns.playerNear(level, h.center(), 160);
        if (!near) {
            discardLive(level, c);
            if (c.progress < 1) {
                c.progress = Math.min(1, c.progress + MARCH_SPEED / c.length());
                return;
            }
            if (Config.ABSTRACT_RAIDS.get()) resolve(level, mgr, h, c);
            return;
        }
        if (c.bandLive.size() < c.total()) {
            boolean first = c.bandLive.isEmpty();
            materialize(level, c);
            if (first && !c.bandLive.isEmpty()) {
                level.playSound(null, h.center(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 16.0F, 0.8F);
                HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("Horns in the hills: the illagers come for " + h.name + "!")
                        .withStyle(ChatFormatting.RED));
            }
        }
        if (c.bandLive.isEmpty()) return;
        List<Mob> band = live(level, c);
        int inside = 0;
        double sx = 0, sz = 0;
        AABB yard = AABB.of(h.box).deflate(6, 0, 6);
        for (Mob m : band) {
            sx += m.getX();
            sz += m.getZ();
            if (yard.contains(m.position())) inside++;
        }
        Vec3 mid = new Vec3(sx / band.size(), 0, sz / band.size());
        double ux = (c.to.getX() - c.from.getX()) / c.length(), uz = (c.to.getZ() - c.from.getZ()) / c.length();
        c.progress = Math.max(c.progress, Math.min(1, ((mid.x - c.from.getX()) * ux + (mid.z - c.from.getZ()) * uz) / c.length()));
        boolean in = inside >= 3 || c.rammedOpen;
        BlockPos goal = in ? (h.bannerPos.equals(BlockPos.ZERO) ? h.center() : h.bannerPos) : c.to;
        steer(level, h, c, band, goal);
        boolean arrived = mid.distanceToSqr(Vec3.atCenterOf(c.to).multiply(1, 0, 1)) < 30 * 30 || inside > 0;
        if (!arrived) return;
        c.siegeSeconds++;
        // the ram: a ravager at the gate, or axes on it after a while
        if (!c.rammed) {
            Mob ravager = null;
            for (Mob m : band) if (m instanceof Ravager) ravager = m;
            boolean atGate = ravager != null && ravager.distanceToSqr(Vec3.atCenterOf(h.gatePos)) < 7 * 7;
            if (atGate || c.siegeSeconds >= (ravager != null ? 40 : 60)) {
                c.rammed = true;
                if (Gates.breach(level, h)) {
                    c.rammedOpen = true;
                    HoldingEvents.tell(level, h.ownerPlayer(), Component.literal((ravager != null ? "A ravager staves in" : "Illager axes hack through")
                            + " the gate of " + h.name + "! They're in the gatehouse.").withStyle(ChatFormatting.RED));
                }
            }
        }
        // held at the walls: ladders, then a charge under the wall
        if (inside < 3 && !c.laddered && c.siegeSeconds >= 45) {
            c.laddered = true;
            int raised = ladders(level, h, c, band, mid);
            if (raised > 0) HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("Ladders against the walls of " + h.name
                    + "! " + raised + " places.").withStyle(ChatFormatting.RED));
        }
        if (inside < 3 && !c.sapped && c.siegeSeconds >= 90 && Config.WARBAND_SAPPERS.get()) {
            c.sapped = true;
            BlockPos at = sap(level, h, band, mid);
            if (at != null) HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("Sappers have set a charge under the wall of "
                    + h.name + " at " + at.getX() + ", " + at.getZ() + "!").withStyle(ChatFormatting.RED));
        }
        // the yard taken: no man of the garrison left inside and the illagers in it
        if (inside >= 3 && defenders(level, h) == 0) {
            if (++c.sackSeconds >= 20) {
                sack(level, mgr, h, c);
                return;
            }
        } else {
            c.sackSeconds = 0;
        }
        // stuck for ten minutes: fought out by numbers
        if (c.siegeSeconds > 600) {
            discardLive(level, c);
            resolve(level, mgr, h, c);
        }
    }

    /** Each man goes for a defender close by, else for the gate, or for the banner once they're in. */
    private static void steer(ServerLevel level, Holding h, Campaign c, List<Mob> band, BlockPos goal) {
        for (Mob m : band) {
            if (m.getPersistentData().contains(LADDER)) {
                BlockPos foot = BlockPos.of(m.getPersistentData().getLong(LADDER));
                if (m.distanceToSqr(Vec3.atBottomCenterOf(foot)) > 2.0) m.getNavigation().moveTo(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5, 1.0);
                continue;
            }
            LivingEntity t = m.getTarget();
            if (t == null || !t.isAlive()) {
                GarrisonEntity near = null;
                double best = 18 * 18;
                for (GarrisonEntity g : level.getEntitiesOfClass(GarrisonEntity.class, m.getBoundingBox().inflate(18),
                        g -> g.isAlive() && g.side().equals(h.owner))) {
                    double d = g.distanceToSqr(m);
                    if (d < best) {
                        best = d;
                        near = g;
                    }
                }
                if (near != null) {
                    m.setTarget(near);
                    continue;
                }
                BlockPos to = m instanceof Ravager && !c.rammed ? h.gatePos : goal;
                if (m.getNavigation().isDone() || m.distanceToSqr(Vec3.atCenterOf(to)) > 9) m.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 1.0);
            }
        }
    }

    /** How many of the garrison are still on their feet inside the walls. */
    private static int defenders(ServerLevel level, Holding h) {
        return level.getEntitiesOfClass(GarrisonEntity.class, AABB.of(h.box), g -> g.isAlive() && g.side().equals(h.owner)).size();
    }

    // ------------------------------------------------------------ the men

    /** Bring up the men not yet on the field, a few a second, where the warband has got to. */
    private static void materialize(ServerLevel level, Campaign c) {
        BlockPos at = c.position();
        if (!level.isLoaded(at)) return;
        Map<String, Integer> have = new LinkedHashMap<>();
        for (String k : c.bandLive.values()) have.merge(k, 1, Integer::sum);
        int budget = 6;
        for (var e : c.band.entrySet()) {
            int n = have.getOrDefault(e.getKey(), 0);
            while (n < e.getValue() && budget > 0) {
                int x = at.getX() + level.random.nextInt(11) - 5, z = at.getZ() + level.random.nextInt(11) - 5;
                if (!level.isLoaded(new BlockPos(x, at.getY(), z))) break;
                BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                Mob m = spawn(level, c, e.getKey(), p);
                if (m == null) break;
                n++;
                budget--;
            }
        }
    }

    @Nullable
    public static Mob spawn(ServerLevel level, Campaign c, String kind, BlockPos p) {
        String id = kind.equals(CAPTAIN) ? "minecraft:pillager" : kind;
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
        if (type == null) return null;
        Entity e = type.create(level);
        if (!(e instanceof Mob m)) {
            if (e != null) e.discard();
            return null;
        }
        m.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, level.random.nextFloat() * 360F, 0);
        m.finalizeSpawn(level, level.getCurrentDifficultyAt(p), MobSpawnType.EVENT, null, null);
        if (m instanceof Raider r) {
            r.setCanJoinRaid(false);
            if (kind.equals(CAPTAIN)) {
                r.setPatrolLeader(true);
                r.setItemSlot(EquipmentSlot.HEAD, Raid.getLeaderBannerInstance());
                r.setDropChance(EquipmentSlot.HEAD, 2.0F);
            }
        }
        m.setPersistenceRequired();
        m.getPersistentData().putUUID(TAG, c.id);
        c.bandLive.put(m.getUUID(), kind);
        level.addFreshEntity(m);
        return m;
    }

    private static List<Mob> live(ServerLevel level, Campaign c) {
        List<Mob> out = new ArrayList<>();
        for (UUID id : c.bandLive.keySet()) if (level.getEntity(id) instanceof Mob m && m.isAlive()) out.add(m);
        return out;
    }

    /** Forget the men who are gone without a death (unloaded, despawned): they're still on the count. */
    private static void prune(ServerLevel level, Campaign c) {
        c.bandLive.keySet().removeIf(id -> !(level.getEntity(id) instanceof Mob m) || m.isRemoved());
    }

    static void discardLive(ServerLevel level, Campaign c) {
        for (UUID id : c.bandLive.keySet()) {
            Entity e = level.getEntity(id);
            if (e != null) e.discard();
        }
        c.bandLive.clear();
    }

    @Nullable
    private static Campaign campaignOf(HoldingManager mgr, Entity e) {
        if (!e.getPersistentData().hasUUID(TAG)) return null;
        UUID id = e.getPersistentData().getUUID(TAG);
        for (Campaign c : mgr.campaigns) if (c.id.equals(id)) return c;
        return null;
    }

    /** A warband man dies: one fewer on the count. */
    public static void onDeath(ServerLevel level, LivingEntity e) {
        HoldingManager mgr = HoldingManager.get(level);
        Campaign c = campaignOf(mgr, e);
        if (c == null) return;
        String kind = c.bandLive.remove(e.getUUID());
        if (kind == null) return;
        c.band.merge(kind, -1, Integer::sum);
        c.band.values().removeIf(v -> v <= 0);
        mgr.setDirty();
    }

    /** A warband man loaded back from disk after a restart: the warband brings its men up itself. */
    public static boolean stray(ServerLevel level, Entity e) {
        if (!e.getPersistentData().hasUUID(TAG)) return false;
        Campaign c = campaignOf(HoldingManager.get(level), e);
        return c == null || !c.bandLive.containsKey(e.getUUID());
    }

    // ------------------------------------------------------------ ways in

    /** A point on the outside face of the curtain wall between the warband and the castle, and the way in. */
    public record WallFace(BlockPos foot, Direction in, int top) {
    }

    @Nullable
    public static WallFace wallFace(ServerLevel level, Holding h, Vec3 from) {
        BlockPos c = h.center();
        int x = (int) Math.floor(from.x), z = (int) Math.floor(from.z);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int step = 0; step < 160; step++) {
            int dx = c.getX() - x, dz = c.getZ() - z;
            if (dx == 0 && dz == 0) return null;
            Direction d = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            if (!level.isLoaded(new BlockPos(nx, ground, nz))) return null;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
            if (top - ground >= 5) {
                if (!h.box.inflatedBy(4).isInside(new BlockPos(nx, ground, nz))) return null;
                return new WallFace(new BlockPos(x, ground, z), d, top);
            }
            x = nx;
            z = nz;
            ground = top;
        }
        return null;
    }

    /** Ladders up the outside of the wall at a couple of places, and a few men sent up each. */
    static int ladders(ServerLevel level, Holding h, Campaign c, List<Mob> band, Vec3 mid) {
        Vec3 toward = Vec3.atCenterOf(h.center()).subtract(mid).multiply(1, 0, 1).normalize();
        Vec3 side = new Vec3(-toward.z, 0, toward.x);
        int raised = 0;
        List<Mob> free = new ArrayList<>(band);
        free.removeIf(m -> m instanceof Ravager || m.getPersistentData().contains(LADDER));
        for (int off : new int[]{-7, 7}) {
            WallFace f = wallFace(level, h, mid.add(side.scale(off)));
            if (f == null || raise(level, f) < 4) continue;
            raised++;
            // the nearest few go up it
            free.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(Vec3.atCenterOf(f.foot()))));
            for (int i = 0; i < 3 && !free.isEmpty(); i++) {
                Mob m = free.remove(0);
                CompoundTag d = m.getPersistentData();
                d.putLong(LADDER, f.foot().asLong());
                d.putLong(LADDER_WALL, f.foot().relative(f.in()).asLong());
                d.putInt(LADDER_TOP, f.top());
                m.setTarget(null);
            }
        }
        return raised;
    }

    /** A ladder up the outside face of the wall. How many rungs went up (it stops at anything in the way). */
    public static int raise(ServerLevel level, WallFace f) {
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, f.in().getOpposite());
        int placed = 0;
        for (int y = f.foot().getY(); y < f.top(); y++) {
            BlockPos q = new BlockPos(f.foot().getX(), y, f.foot().getZ());
            BlockState here = level.getBlockState(q);
            if (!(here.isAir() || here.canBeReplaced()) || !here.getFluidState().isEmpty()) break;
            if (!ladder.canSurvive(level, q)) break;
            level.setBlock(q, ladder, 3);
            placed++;
        }
        return placed;
    }

    /** Every tick for a warband man sent up a ladder: up he goes, and over the top onto the wall. */
    public static void climb(Mob m) {
        CompoundTag d = m.getPersistentData();
        if (!d.contains(LADDER)) return;
        BlockPos foot = BlockPos.of(d.getLong(LADDER)), wall = BlockPos.of(d.getLong(LADDER_WALL));
        int top = d.getInt(LADDER_TOP);
        double cx = foot.getX() + 0.5, cz = foot.getZ() + 0.5;
        double hx = m.getX() - cx, hz = m.getZ() - cz;
        if (hx * hx + hz * hz > 1.2) return;                       // not at the ladder yet: steer() walks him there
        Vec3 in = new Vec3(wall.getX() + 0.5 - cx, 0, wall.getZ() + 0.5 - cz).normalize();
        if (m.getY() >= top) {
            m.setDeltaMovement(in.x * 0.35, 0.15, in.z * 0.35);  // over the parapet
            d.remove(LADDER);
            d.remove(LADDER_WALL);
            d.remove(LADDER_TOP);
            return;
        }
        m.getNavigation().stop();
        m.setDeltaMovement(in.x * 0.05 - hx * 0.2, 0.22, in.z * 0.05 - hz * 0.2);
        m.fallDistance = 0;
    }

    /** A charge under the wall where they're held: a breach to walk through. */
    @Nullable
    static BlockPos sap(ServerLevel level, Holding h, List<Mob> band, Vec3 mid) {
        WallFace f = wallFace(level, h, mid);
        if (f == null) return null;
        LivingEntity owner = band.isEmpty() ? null : band.get(0);
        for (int k = 0; k < 3; k++) {
            PrimedTnt tnt = new PrimedTnt(level, f.foot().getX() + 0.5, f.foot().getY() + k * 0.4, f.foot().getZ() + 0.5, owner);
            tnt.setFuse(80 + k * 4);
            level.addFreshEntity(tnt);
        }
        level.playSound(null, f.foot(), SoundEvents.TNT_PRIMED, SoundSource.HOSTILE, 2.0F, 0.8F);
        return f.foot();
    }

    // ------------------------------------------------------------ the end of it

    /** Fought out by numbers while nobody watches. */
    static void resolve(ServerLevel level, HoldingManager mgr, Holding h, Campaign c) {
        double def = h.strength() * (h.mode == Holding.Mode.DEFEND ? 1.6 : 1.2) * (0.8 + level.random.nextDouble() * 0.4);
        double att = c.strength() * (1.0 + 0.15 * (Frontier.zone(level, h.center()) - 1)) * (0.8 + level.random.nextDouble() * 0.4);
        if (att > def) {
            sack(level, mgr, h, c);
            return;
        }
        int losses = (int) Math.ceil(att / 5);
        for (Role r : new Role[]{Role.KNIGHT, Role.ARCHER, Role.CROSSBOWMAN}) {
            int l = Math.min(losses, h.count(r));
            h.add(r, -l);
            losses -= l;
        }
        broken(level, mgr, h, c, c.spoils / 2, false);
    }

    /** The warband broken: its spoils into the castle's stores. */
    static void broken(ServerLevel level, HoldingManager mgr, Holding h, Campaign c, int spoils, boolean watched) {
        discardLive(level, c);
        boolean pack = false;
        if (spoils > 0) {
            if (watched) pack = Rewards.pay(level, mgr, h.ownerPlayer(), spoils, h) > 0;
            else h.addStock(new ResourceLocation("minecraft", "emerald"), spoils);
        }
        HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("The illager warband is broken at " + h.name + "! Their spoils, "
                + spoils + " emeralds, " + (pack ? "are yours." : "go to its stores.")).withStyle(ChatFormatting.GREEN));
        Chronicle.log(level, mgr, Chronicle.WAR, "The illagers are broken at the walls of " + h.name + ".", h.ownerPlayer());
        c.stage = Campaign.Stage.DONE;
        mgr.setDirty();
    }

    /** The yard taken: they carry off a quarter of the stores and a third of the garrison is lost. The castle stays yours. */
    public static void sack(ServerLevel level, HoldingManager mgr, Holding h, Campaign c) {
        discardLive(level, c);
        List<String> taken = new ArrayList<>();
        for (String item : List.of("emerald", "diamond", "gold_ingot", "iron_ingot", "bread")) {
            ResourceLocation rl = new ResourceLocation("minecraft", item);
            int n = h.stock(rl) / 4;
            if (n <= 0) continue;
            h.addStock(rl, -n);
            taken.add(n + " " + item.replace("_ingot", "").replace('_', ' '));
        }
        for (Role r : Role.TROOPS) h.add(r, -(int) Math.ceil(h.count(r) / 3.0));
        HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("Illagers sack " + h.name + "! They carry off "
                + (taken.isEmpty() ? "nothing worth having" : String.join(", ", taken)) + ", and a third of the garrison is lost.")
                .withStyle(ChatFormatting.DARK_RED));
        Chronicle.log(level, mgr, Chronicle.WAR, "Illagers sack " + h.name + ".", h.ownerPlayer());
        c.stage = Campaign.Stage.DONE;
        mgr.setDirty();
    }

    private Warbands() {
    }
}
