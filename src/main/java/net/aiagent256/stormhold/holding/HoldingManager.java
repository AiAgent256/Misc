package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.house.House;
import net.aiagent256.stormhold.house.NameGen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-dimension registry of holdings, houses, campaigns, shipments and roads. */
public class HoldingManager extends SavedData {
    private static final String NAME = "stormhold_holdings";

    public final Map<UUID, Holding> holdings = new LinkedHashMap<>();
    public final Map<String, UUID> byKey = new HashMap<>();
    public final Set<String> rejected = new HashSet<>();
    public final Map<String, House> houses = new HashMap<>();
    public final Map<UUID, DyeColor> playerColors = new HashMap<>();
    public final List<Campaign> campaigns = new ArrayList<>();
    public final List<Shipment> shipments = new ArrayList<>();
    public final List<RoadPlan> roads = new ArrayList<>();
    /** house id -> player -> personal standing delta. */
    public final Map<String, Map<UUID, Integer>> standing = new HashMap<>();
    public long lastStandingDrift;
    /** The realm's record, relations between houses, envoys' offers, and the clocks that drive them. */
    public final List<Chronicle.Entry> chronicle = new ArrayList<>();
    public final Map<String, Integer> relations = new HashMap<>();
    public final List<net.aiagent256.stormhold.house.Politics.Contract> contracts = new ArrayList<>();
    public long lastDawn = -1, lastPolitics, lastMend;

    public static HoldingManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(HoldingManager::load, HoldingManager::new, NAME);
    }

    public House house(String id) {
        House h = houses.get(id);
        if (h == null) {
            RandomSource r = RandomSource.create(id.hashCode() * 31L + 7);
            DyeColor base = DyeColor.byId(r.nextInt(16));
            DyeColor accent = DyeColor.byId(r.nextInt(16));
            if (accent == base) accent = base == DyeColor.WHITE ? DyeColor.BLACK : DyeColor.WHITE;
            String name = id.equals("outlaws") ? "of the Brigands" : NameGen.house(r);
            h = new House(id, name, base, accent, r.nextInt(64), r.nextBoolean());
            h.disposition = id.equals("outlaws") ? -100 : r.nextInt(3) == 0 ? 40 : 0;
            houses.put(id, h);
            setDirty();
        }
        return h;
    }

    /** Houses own regions of roughly 1500 blocks, so neighbouring castles share banners. */
    public House houseForRegion(ServerLevel level, BlockPos pos) {
        int rx = Math.floorDiv(pos.getX(), 1536), rz = Math.floorDiv(pos.getZ(), 1536);
        long mix = level.getSeed() ^ (rx * 341873128712L) ^ (rz * 132897987541L);
        return house("h" + Long.toHexString(mix & 0xffffffffL));
    }

    public DyeColor playerColor(UUID player) {
        return playerColors.getOrDefault(player, DyeColor.BLUE);
    }

    public DyeColor colorOf(String side) {
        UUID p = Sides.playerId(side);
        if (p != null) return playerColor(p);
        String hid = Sides.houseId(side);
        return hid != null ? house(hid).base : DyeColor.GRAY;
    }

    @Nullable
    public House houseOf(String side) {
        String hid = Sides.houseId(side);
        return hid != null ? house(hid) : null;
    }

    @Nullable
    public Holding at(BlockPos pos, int margin) {
        for (Holding h : holdings.values()) {
            if (h.box.inflatedBy(margin).isInside(pos)) return h;
        }
        return null;
    }

    @Nullable
    public Holding byName(String name) {
        for (Holding h : holdings.values()) if (h.name.equalsIgnoreCase(name)) return h;
        return null;
    }

    public List<Holding> ownedBy(UUID player) {
        String side = Sides.player(player);
        List<Holding> out = new ArrayList<>();
        for (Holding h : holdings.values()) if (side.equals(h.owner)) out.add(h);
        return out;
    }

    public void add(Holding h) {
        holdings.put(h.id, h);
        byKey.put(h.key, h.id);
        setDirty();
    }

    // ------------------------------------------------------------ persistence

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag hl = new ListTag();
        for (Holding h : holdings.values()) hl.add(h.save());
        tag.put("Holdings", hl);
        ListTag houseList = new ListTag();
        for (House h : houses.values()) houseList.add(h.save());
        tag.put("Houses", houseList);
        CompoundTag colors = new CompoundTag();
        playerColors.forEach((k, v) -> colors.putInt(k.toString(), v.getId()));
        tag.put("PlayerColors", colors);
        ListTag cl = new ListTag();
        for (Campaign c : campaigns) cl.add(c.save());
        tag.put("Campaigns", cl);
        ListTag sl = new ListTag();
        for (Shipment s : shipments) sl.add(s.save());
        tag.put("Shipments", sl);
        ListTag rl = new ListTag();
        for (RoadPlan r : roads) rl.add(r.save());
        tag.put("Roads", rl);
        CompoundTag st = new CompoundTag();
        standing.forEach((house, m) -> {
            CompoundTag pm = new CompoundTag();
            m.forEach((p, v) -> pm.putInt(p.toString(), v));
            st.put(house, pm);
        });
        tag.put("Standing", st);
        tag.putLong("StandingDrift", lastStandingDrift);
        tag.put("Chronicle", Chronicle.save(chronicle));
        CompoundTag rel = new CompoundTag();
        relations.forEach(rel::putInt);
        tag.put("Relations", rel);
        ListTag ct = new ListTag();
        for (var c : contracts) ct.add(c.save());
        tag.put("Contracts", ct);
        tag.putLong("LastDawn", lastDawn);
        tag.putLong("LastPolitics", lastPolitics);
        tag.putLong("LastMend", lastMend);
        return tag;
    }

    public static HoldingManager load(CompoundTag tag) {
        HoldingManager m = new HoldingManager();
        ListTag hl = tag.getList("Holdings", Tag.TAG_COMPOUND);
        for (int i = 0; i < hl.size(); i++) {
            Holding h = Holding.load(hl.getCompound(i));
            m.holdings.put(h.id, h);
            m.byKey.put(h.key, h.id);
        }
        ListTag houseList = tag.getList("Houses", Tag.TAG_COMPOUND);
        for (int i = 0; i < houseList.size(); i++) {
            House h = House.load(houseList.getCompound(i));
            m.houses.put(h.id, h);
        }
        CompoundTag colors = tag.getCompound("PlayerColors");
        for (String k : colors.getAllKeys()) {
            try {
                m.playerColors.put(UUID.fromString(k), DyeColor.byId(colors.getInt(k)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        ListTag cl = tag.getList("Campaigns", Tag.TAG_COMPOUND);
        for (int i = 0; i < cl.size(); i++) m.campaigns.add(Campaign.load(cl.getCompound(i)));
        ListTag sl = tag.getList("Shipments", Tag.TAG_COMPOUND);
        for (int i = 0; i < sl.size(); i++) m.shipments.add(Shipment.load(sl.getCompound(i)));
        ListTag rl = tag.getList("Roads", Tag.TAG_COMPOUND);
        for (int i = 0; i < rl.size(); i++) m.roads.add(RoadPlan.load(rl.getCompound(i)));
        CompoundTag st = tag.getCompound("Standing");
        for (String house : st.getAllKeys()) {
            CompoundTag pm = st.getCompound(house);
            Map<UUID, Integer> pmap = new HashMap<>();
            for (String k : pm.getAllKeys()) {
                try {
                    pmap.put(UUID.fromString(k), pm.getInt(k));
                } catch (IllegalArgumentException ignored) {
                }
            }
            m.standing.put(house, pmap);
        }
        m.lastStandingDrift = tag.getLong("StandingDrift");
        Chronicle.load(tag.getList("Chronicle", Tag.TAG_COMPOUND), m.chronicle);
        CompoundTag rel = tag.getCompound("Relations");
        for (String k : rel.getAllKeys()) m.relations.put(k, rel.getInt(k));
        ListTag ct = tag.getList("Contracts", Tag.TAG_COMPOUND);
        for (int i = 0; i < ct.size(); i++) m.contracts.add(net.aiagent256.stormhold.house.Politics.Contract.load(ct.getCompound(i)));
        m.lastDawn = tag.contains("LastDawn") ? tag.getLong("LastDawn") : -1;
        m.lastPolitics = tag.getLong("LastPolitics");
        m.lastMend = tag.getLong("LastMend");
        return m;
    }
}
