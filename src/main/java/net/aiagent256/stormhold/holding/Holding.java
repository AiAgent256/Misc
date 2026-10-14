package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A castle someone holds. Counts here are authoritative; entities are spawned from them. */
public class Holding {
    public enum Mode {DEFEND, PRODUCE, ATTACK}

    private static final int[][] CAPACITY = {
            // captain, knight, archer, crossbowman
            {1, 6, 4, 1},
            {1, 12, 8, 3},
            {2, 22, 14, 8},
            {3, 36, 26, 14},
    };
    public static final int MAX_TIER = 4;

    public static String tierName(int tier) {
        return switch (tier) {
            case 1 -> "Watchtower";
            case 2 -> "Holdfast";
            case 3 -> "Great castle";
            default -> "City";
        };
    }

    public static String renownName(int renown) {
        return switch (Math.max(1, Math.min(5, renown))) {
            case 1 -> "a poor";
            case 2 -> "a modest";
            case 3 -> "a prosperous";
            case 4 -> "a rich";
            default -> "a famed";
        };
    }

    public static String lordTitle(int tier) {
        return switch (tier) {
            case 1 -> "Castellan ";
            case 2 -> "Ser ";
            case 3 -> "Lord ";
            default -> "King ";
        };
    }

    public UUID id = UUID.randomUUID();
    public String key = "";
    public String name = "";
    public ResourceLocation structure = new ResourceLocation("minecraft", "empty");
    @Nullable public String layout;
    public String theme = "north";
    public int tier = 1;
    /** 1 (a backwater) to 5 (famed): gear, health and treasure scale with it. */
    public int renown = 2;
    /** Treasury chests still carrying a renown bonus (the first few opened in the keep after the lord falls). */
    public int bonusLeft;
    public BoundingBox box = new BoundingBox(0, 0, 0, 0, 0, 0);
    public String houseId = "outlaws";
    public String owner = Sides.OUTLAWS;
    public boolean scanned;
    public boolean lordAlive = true;
    public String lordName = "";
    public BlockPos bannerPos = BlockPos.ZERO, lordPos = BlockPos.ZERO, warTablePos = BlockPos.ZERO,
            gatePos = BlockPos.ZERO, rallyPos = BlockPos.ZERO;
    public final EnumMap<Role, List<BlockPos>> posts = new EnumMap<>(Role.class);
    public final List<BlockPos> banners = new ArrayList<>();
    /** City features by name: barracks, notice_board, house (door positions), inn, smithy. */
    public final Map<String, List<BlockPos>> extras = new HashMap<>();
    /** House doors bought by players. */
    public final Map<BlockPos, UUID> ownedHouses = new HashMap<>();
    public final List<Posting> postings = new ArrayList<>();
    public long nextPosting;
    public boolean cityBlocksPlaced;
    public final Set<UUID> greeted = new HashSet<>();
    public final EnumMap<Role, Integer> garrison = new EnumMap<>(Role.class);
    public Mode mode = Mode.DEFEND;
    @Nullable public BlockPos attackTarget;
    @Nullable public BlockPos supplyTarget;
    public final Map<ResourceLocation, Integer> stock = new HashMap<>();
    public long nextProduce, nextRaid, nextReplenish, nextCampaign;
    public boolean raidWarned;
    public boolean villagersSpawned;
    public boolean bannersDirty = true;
    public boolean surroundingsDone;
    @Nullable public String contestSide;
    public int contestProgress;
    @Nullable public UUID claimant;
    public boolean counterattackSpawned;

    // runtime only
    public final Map<UUID, Role> live = new HashMap<>();
    public final Set<BlockPos> usedPosts = new HashSet<>();
    @Nullable public ServerBossEvent bar;
    public int idleSeconds;

    public static int capacity(int tier, Role role) {
        int t = Math.max(1, Math.min(MAX_TIER, tier)) - 1;
        int base = switch (role) {
            case CAPTAIN -> CAPACITY[t][0];
            case KNIGHT -> CAPACITY[t][1];
            case ARCHER -> CAPACITY[t][2];
            case CROSSBOWMAN -> CAPACITY[t][3];
            default -> 0;
        };
        if (role == Role.CAPTAIN) return base;
        return Math.max(role == Role.KNIGHT ? 2 : 0, (int) Math.round(base * Config.GARRISON_SCALE.get()));
    }

    public int capacity(Role role) {
        return capacity(tier, role);
    }

    public int count(Role role) {
        return garrison.getOrDefault(role, 0);
    }

    public void add(Role role, int n) {
        garrison.put(role, Math.max(0, count(role) + n));
    }

    public void fillGarrison(double fraction) {
        garrison.clear();
        for (Role r : Role.TROOPS) garrison.put(r, (int) Math.ceil(capacity(r) * fraction));
    }

    public int troops() {
        int n = 0;
        for (Role r : Role.TROOPS) n += count(r);
        return n;
    }

    public double strength() {
        double s = 0;
        for (Role r : Role.TROOPS) s += count(r) * r.strength() * (1.0 + 0.15 * (renown - 1));
        if (lordAlive && isHouseOwned()) s += Role.LORD.strength();
        return s;
    }

    /** A bought house: the door and a modest box behind it that the buyer may use freely. */
    @Nullable
    public UUID houseOwnerAt(BlockPos p) {
        for (var e : ownedHouses.entrySet()) {
            BlockPos d = e.getKey();
            if (Math.abs(p.getX() - d.getX()) <= 7 && Math.abs(p.getZ() - d.getZ()) <= 7 && p.getY() >= d.getY() - 1 && p.getY() <= d.getY() + 12)
                return e.getValue();
        }
        return null;
    }

    public boolean isCity() {
        return tier >= 4;
    }

    public boolean isHouseOwned() {
        return Sides.isHouse(owner);
    }

    public boolean isPlayerOwned() {
        return Sides.isPlayer(owner);
    }

    @Nullable
    public UUID ownerPlayer() {
        return Sides.playerId(owner);
    }

    public BlockPos center() {
        return box.getCenter();
    }

    public int stock(ResourceLocation item) {
        return stock.getOrDefault(item, 0);
    }

    public void addStock(ResourceLocation item, int n) {
        int v = stock(item) + n;
        if (v <= 0) stock.remove(item);
        else stock.put(item, v);
    }

    // ------------------------------------------------------------ persistence

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", id);
        t.putString("Key", key);
        t.putString("Name", name);
        t.putString("Structure", structure.toString());
        if (layout != null) t.putString("Layout", layout);
        t.putString("Theme", theme);
        t.putInt("Tier", tier);
        t.putInt("Renown", renown);
        t.putInt("BonusLeft", bonusLeft);
        t.putIntArray("Box", new int[]{box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()});
        t.putString("House", houseId);
        t.putString("Owner", owner);
        t.putBoolean("Scanned", scanned);
        t.putBoolean("LordAlive", lordAlive);
        t.putString("LordName", lordName);
        t.put("Banner", NbtUtils.writeBlockPos(bannerPos));
        t.put("Lord", NbtUtils.writeBlockPos(lordPos));
        t.put("WarTable", NbtUtils.writeBlockPos(warTablePos));
        t.put("Gate", NbtUtils.writeBlockPos(gatePos));
        t.put("Rally", NbtUtils.writeBlockPos(rallyPos));
        CompoundTag p = new CompoundTag();
        posts.forEach((role, list) -> p.put(role.name(), posList(list)));
        t.put("Posts", p);
        t.put("Banners", posList(banners));
        CompoundTag ex = new CompoundTag();
        extras.forEach((k, v) -> ex.put(k, posList(v)));
        t.put("Extras", ex);
        ListTag oh = new ListTag();
        ownedHouses.forEach((p2, u) -> {
            CompoundTag e = NbtUtils.writeBlockPos(p2);
            e.putUUID("Owner", u);
            oh.add(e);
        });
        t.put("OwnedHouses", oh);
        ListTag pl = new ListTag();
        for (Posting p2 : postings) pl.add(p2.save());
        t.put("Postings", pl);
        t.putLong("NextPosting", nextPosting);
        t.putBoolean("CityBlocks", cityBlocksPlaced);
        CompoundTag g = new CompoundTag();
        garrison.forEach((role, n) -> g.putInt(role.name(), n));
        t.put("Garrison", g);
        t.putString("Mode", mode.name());
        if (attackTarget != null) t.put("AttackTarget", NbtUtils.writeBlockPos(attackTarget));
        if (supplyTarget != null) t.put("SupplyTarget", NbtUtils.writeBlockPos(supplyTarget));
        CompoundTag s = new CompoundTag();
        stock.forEach((k, v) -> s.putInt(k.toString(), v));
        t.put("Stock", s);
        t.putLong("NextProduce", nextProduce);
        t.putLong("NextRaid", nextRaid);
        t.putLong("NextReplenish", nextReplenish);
        t.putLong("NextCampaign", nextCampaign);
        t.putBoolean("RaidWarned", raidWarned);
        t.putBoolean("Villagers", villagersSpawned);
        t.putBoolean("BannersDirty", bannersDirty);
        t.putBoolean("Surroundings", surroundingsDone);
        return t;
    }

    public static Holding load(CompoundTag t) {
        Holding h = new Holding();
        h.id = t.getUUID("Id");
        h.key = t.getString("Key");
        h.name = t.getString("Name");
        h.structure = new ResourceLocation(t.getString("Structure"));
        h.layout = t.contains("Layout") ? t.getString("Layout") : null;
        h.theme = t.getString("Theme");
        h.tier = t.getInt("Tier");
        h.renown = t.contains("Renown") ? Math.max(1, Math.min(5, t.getInt("Renown"))) : Math.min(5, h.tier + 1);
        h.bonusLeft = t.getInt("BonusLeft");
        int[] b = t.getIntArray("Box");
        if (b.length == 6) h.box = new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]);
        h.houseId = t.getString("House");
        h.owner = t.getString("Owner");
        h.scanned = t.getBoolean("Scanned");
        h.lordAlive = t.getBoolean("LordAlive");
        h.lordName = t.getString("LordName");
        h.bannerPos = NbtUtils.readBlockPos(t.getCompound("Banner"));
        h.lordPos = NbtUtils.readBlockPos(t.getCompound("Lord"));
        h.warTablePos = NbtUtils.readBlockPos(t.getCompound("WarTable"));
        h.gatePos = NbtUtils.readBlockPos(t.getCompound("Gate"));
        h.rallyPos = NbtUtils.readBlockPos(t.getCompound("Rally"));
        CompoundTag p = t.getCompound("Posts");
        for (String k : p.getAllKeys()) {
            try {
                h.posts.put(Role.valueOf(k), readPosList(p.getList(k, Tag.TAG_COMPOUND)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        h.banners.addAll(readPosList(t.getList("Banners", Tag.TAG_COMPOUND)));
        CompoundTag ex = t.getCompound("Extras");
        for (String k : ex.getAllKeys()) h.extras.put(k, readPosList(ex.getList(k, Tag.TAG_COMPOUND)));
        ListTag oh = t.getList("OwnedHouses", Tag.TAG_COMPOUND);
        for (int i = 0; i < oh.size(); i++) {
            CompoundTag e = oh.getCompound(i);
            if (e.hasUUID("Owner")) h.ownedHouses.put(NbtUtils.readBlockPos(e), e.getUUID("Owner"));
        }
        ListTag pl = t.getList("Postings", Tag.TAG_COMPOUND);
        for (int i = 0; i < pl.size(); i++) h.postings.add(Posting.load(pl.getCompound(i)));
        h.nextPosting = t.getLong("NextPosting");
        h.cityBlocksPlaced = t.getBoolean("CityBlocks");
        CompoundTag g = t.getCompound("Garrison");
        for (String k : g.getAllKeys()) {
            try {
                h.garrison.put(Role.valueOf(k), g.getInt(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
        try {
            h.mode = Mode.valueOf(t.getString("Mode"));
        } catch (IllegalArgumentException e) {
            h.mode = Mode.DEFEND;
        }
        if (t.contains("AttackTarget")) h.attackTarget = NbtUtils.readBlockPos(t.getCompound("AttackTarget"));
        if (t.contains("SupplyTarget")) h.supplyTarget = NbtUtils.readBlockPos(t.getCompound("SupplyTarget"));
        CompoundTag s = t.getCompound("Stock");
        for (String k : s.getAllKeys()) h.stock.put(new ResourceLocation(k), s.getInt(k));
        h.nextProduce = t.getLong("NextProduce");
        h.nextRaid = t.getLong("NextRaid");
        h.nextReplenish = t.getLong("NextReplenish");
        h.nextCampaign = t.getLong("NextCampaign");
        h.raidWarned = t.getBoolean("RaidWarned");
        h.villagersSpawned = t.getBoolean("Villagers");
        h.bannersDirty = t.getBoolean("BannersDirty");
        h.surroundingsDone = t.getBoolean("Surroundings");
        return h;
    }

    static ListTag posList(List<BlockPos> list) {
        ListTag l = new ListTag();
        for (BlockPos p : list) l.add(NbtUtils.writeBlockPos(p));
        return l;
    }

    static List<BlockPos> readPosList(ListTag l) {
        List<BlockPos> out = new ArrayList<>(l.size());
        for (int i = 0; i < l.size(); i++) out.add(NbtUtils.readBlockPos(l.getCompound(i)));
        return out;
    }
}
