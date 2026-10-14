package net.aiagent256.stormhold.holding;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.House;
import net.aiagent256.stormhold.house.NameGen;
import net.aiagent256.stormhold.worldgen.CastleStructure;
import net.aiagent256.stormhold.worldgen.CastleTilePiece;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Watches the chunks around players for castles and turns them into holdings. */
public final class Detector {
    private static List<? extends String> cachedList;
    private static Map<ResourceLocation, Integer> cachedTiers = Map.of();

    private static Map<ResourceLocation, Integer> tiers() {
        List<? extends String> list = Config.ATTACH.get();
        if (list != cachedList) {
            Map<ResourceLocation, Integer> m = new HashMap<>();
            for (String s : list) {
                String[] kv = s.split("=");
                if (kv.length != 2) continue;
                try {
                    m.put(new ResourceLocation(kv[0].trim()), Math.max(1, Math.min(3, Integer.parseInt(kv[1].trim()))));
                } catch (Exception e) {
                    Stormhold.LOGGER.warn("Bad attach entry '{}'", s);
                }
            }
            cachedTiers = m;
            cachedList = list;
        }
        return cachedTiers;
    }

    public static void detect(ServerLevel level, HoldingManager mgr) {
        if (level.players().isEmpty()) return;
        Registry<Structure> reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        int r = Config.DETECT_RADIUS_CHUNKS.get();
        LongOpenHashSet seen = new LongOpenHashSet();
        for (ServerPlayer p : level.players()) {
            ChunkPos cp = p.chunkPosition();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (!seen.add(ChunkPos.asLong(cp.x + dx, cp.z + dz))) continue;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x + dx, cp.z + dz);
                    if (chunk == null) continue;
                    for (var e : chunk.getAllReferences().entrySet()) {
                        Structure s = e.getKey();
                        ResourceLocation id = reg.getKey(s);
                        if (id == null) continue;
                        boolean own = s instanceof CastleStructure;
                        Integer tier = own ? Integer.valueOf(0) : tiers().get(id);
                        if (tier == null) continue;
                        for (long ref : e.getValue()) consider(level, mgr, s, id, own, tier, ref);
                    }
                }
            }
        }
    }

    private static void consider(ServerLevel level, HoldingManager mgr, Structure s, ResourceLocation id, boolean own,
                                 int tier, long ref) {
        String key = id + "@" + ref;
        if (mgr.byKey.containsKey(key) || mgr.rejected.contains(key)) return;
        ChunkPos sp = new ChunkPos(ref);
        ChunkAccess startChunk = level.getChunk(sp.x, sp.z, ChunkStatus.STRUCTURE_STARTS, false);
        if (startChunk == null) return;
        StructureStart start = level.structureManager().getStartForStructure(SectionPos.of(sp, 0), s, startChunk);
        if (start == null || !start.isValid()) {
            mgr.rejected.add(key);
            return;
        }
        Holding h = create(level, mgr, key, id, own, tier, start);
        if (h == null) {
            mgr.rejected.add(key);
            return;
        }
        Stormhold.LOGGER.info("Stormhold: new holding {} ({}, tier {}) at {}", h.name, id, h.tier, h.center());
    }

    @Nullable
    static Holding create(ServerLevel level, HoldingManager mgr, String key, ResourceLocation id, boolean own, int tier,
                          StructureStart start) {
        Holding h = new Holding();
        h.key = key;
        h.structure = id;
        RandomSource rnd = RandomSource.create(key.hashCode() ^ level.getSeed());
        if (own) {
            CastleTilePiece piece = null;
            for (StructurePiece p : start.getPieces()) {
                if (p instanceof CastleTilePiece c) {
                    piece = c;
                    break;
                }
            }
            if (piece == null) return null;
            Layout l = DataRegistries.layout(piece.layout());
            if (l == null) return null;
            applyLayout(h, l, piece.castleOrigin());
            h.layout = l.name;
            h.theme = piece.theme();
        } else {
            h.tier = tier;
            h.box = start.getBoundingBox();
            h.scanned = false;
        }
        House house = mgr.houseForRegion(level, h.center());
        h.houseId = house.id;
        h.owner = Sides.house(house.id);
        h.name = NameGen.place(rnd, h.tier);
        h.lordName = Holding.lordTitle(h.tier) + NameGen.first(rnd) + " " + house.name;
        if (h.tier >= 4) {
            // the capital: the house's seat, and friendly to strangers unless the house is already an enemy
            house.capital = h.id;
            if (house.disposition < 40) house.disposition = 40;
        }
        h.fillGarrison(1.0);
        h.lordAlive = true;
        mgr.add(h);
        return h;
    }

    public static void applyLayout(Holding h, Layout l, BlockPos o) {
        h.tier = l.tier;
        h.box = new BoundingBox(o.getX(), o.getY(), o.getZ(), o.getX() + l.sizeX - 1, o.getY() + l.sizeY - 1, o.getZ() + l.sizeZ - 1);
        h.bannerPos = o.offset(l.bannerStand);
        h.lordPos = o.offset(l.lord);
        h.warTablePos = o.offset(l.warTable);
        h.gatePos = o.offset(l.gate);
        h.rallyPos = o.offset(l.rally);
        h.posts.clear();
        for (var e : l.posts.entrySet()) {
            List<BlockPos> list = new ArrayList<>();
            for (BlockPos p : e.getValue()) list.add(o.offset(p));
            h.posts.put(e.getKey(), list);
        }
        h.banners.clear();
        for (BlockPos b : l.banners) h.banners.add(o.offset(b));
        h.scanned = true;
    }

    @Nullable
    public static Holding createForTest(ServerLevel level, HoldingManager mgr, String key, ResourceLocation id, StructureStart start) {
        return create(level, mgr, key, id, true, 0, start);
    }

    @SuppressWarnings("unused")
    private static final Role[] KEEP = Role.values();

    private Detector() {
    }
}
