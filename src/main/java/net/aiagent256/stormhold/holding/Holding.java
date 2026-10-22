package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
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

    /** A market town: a settlement between the castles, held by a reeve, with a town watch for a garrison. */
    public boolean isTown() {
        return layout != null && (layout.startsWith("town_") || layout.startsWith("hill_town"));
    }

    /** A holdfast of the Nether: the holdfast plans in blackstone, under the roof of the world. */
    public boolean isNether() {
        return structure != null && structure.getPath().startsWith("nether_");
    }

    public String kindName() {
        return (ruined ? "Ruined " + (isTown() ? "market town" : isNether() ? "Nether holdfast" : tierName(tier).toLowerCase(java.util.Locale.ROOT))
                : isTown() ? "Market town" : isNether() ? "Nether holdfast" : tierName(tier));
    }

    public String lordTitle() {
        return isTown() ? "Reeve " : lordTitle(tier);
    }

    public static String tierName(int tier) {
        return switch (tier) {
            case 0 -> "Market town";
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
    /** Pieces of the zone's own kit still in the treasury chests (-1: not counted yet), and which slots have gone. */
    public int gearLeft = -1, gearGiven;
    /** Kit your men took off the enemy dead on campaign, riding home to the spoils chest. */
    public final List<net.minecraft.world.item.ItemStack> spoilsCarried = new ArrayList<>();
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
    /** Posts beside a machicolation and the side the man there leans out over the hole from, {dx, dz}. */
    public final Map<BlockPos, int[]> lips = new HashMap<>();
    /** House doors bought by players. */
    public final Map<BlockPos, UUID> ownedHouses = new HashMap<>();
    /** The keeper standing at each shop post, and the day the shops last restocked. */
    public final Map<BlockPos, UUID> shopkeepers = new HashMap<>();
    /** Trades each player has done at this place's shops: regulars get a discount (see Shops). */
    public final Map<UUID, Integer> custom = new HashMap<>();
    public long shopsDay = -1;
    /** 1 once the plans' shop posts and homes for sale have been read into a place built before it had them. */
    public int shopsVersion;
    /** 1 once a great castle has looked for room for its yard market (and put it up, if there was room). */
    public int marketVersion;
    /** Labour: how many of the people the War Table sets to the mine and the forge, how many are at it, and the forge's work. */
    public int miners, smiths, minersAt, smithsAt, forged, forgeWork, forgeTarget;
    public boolean forgePaid;
    public String labourReport = "";
    public final List<Posting> postings = new ArrayList<>();
    public long nextPosting;
    public boolean cityBlocksPlaced;
    public final Set<UUID> greeted = new HashSet<>();
    public final EnumMap<Role, Integer> garrison = new EnumMap<>(Role.class);
    public Mode mode = Mode.DEFEND;
    @Nullable public BlockPos attackTarget;
    @Nullable public BlockPos supplyTarget;
    public final Map<ResourceLocation, Integer> stock = new HashMap<>();
    public long nextProduce, nextRaid, nextReplenish, nextCampaign, nextColumn;
    /** Supply line: keep this many men with the owner, sending columns to top up. 0 = off. */
    public int reinforceKeep;
    public boolean raidWarned;
    /** When the next illager warband comes for this place, if a player holds it, and whether he's been warned. */
    public long nextWarband;
    public boolean warbandWarned;
    /** When the castle last took damage (a dragon, a blast, a broken gate); the masons come once it's quiet. 0 if none. */
    public long damagedAt;
    /** When a portcullis of this castle was last broken: it stays down {@link Gates#DOWN_TICKS} from then. 0 if whole. */
    public long gateBrokenAt;
    /** Not saved: when one of the garrison was last hurt by anyone. The masons wait for the fighting to stop too. */
    public long foughtAt;
    public boolean villagersSpawned;
    public boolean bannersDirty = true;
    public boolean surroundingsDone;
    /** Not saved: when a wild dragon was last seen at this castle. */
    public long dragonAt;
    /** The fief: what's still to build (piece names), what stands (name + box), and the last census. */
    @Nullable public List<String> fiefPlan;
    public final List<FiefBuilding> fief = new ArrayList<>();
    public int fiefTries;
    public String fiefReport = "";
    public int fiefPop;
    public final Map<String, Integer> fiefJobs = new java.util.LinkedHashMap<>();
    public int fiefHerd;
    /** What the fields, pens and mine gave the last day someone was there to see it: the day's work when nobody is. */
    public final Map<String, Integer> fiefReal = new java.util.LinkedHashMap<>();

    public record FiefBuilding(String piece, BoundingBox box) {
    }

    /** Working faces of the fief's mines, keyed by the mine building's corner. */
    public final Map<Long, Produce.Dig> digs = new HashMap<>();

    /** The Master Builder and his current job. The queue is rebuilt from the plans when needed. */
    /** Days of peace and plenty in a row; growth happens on multiples of it. */
    public int growth;
    public long lastRaidAt;
    public String work = Builder.NONE;
    public int fortify;
    public int workDone;
    public String builderName = "";
    @Nullable public UUID builder;
    /** Where the castle's waystone was set (see WaystonesCompat); null until it has one. */
    @Nullable public BlockPos waystone;
    @Nullable public java.util.ArrayDeque<Builder.Job> workQueue;
    /** The steward's name, and what he's done since the morning's account (not saved). */
    public String stewardName = "";
    public int stewardSold, stewardEarned, stewardStored, stewardSent, stewardStocked, stewardBought, stewardSpent;
    /** Things the garrison has put in the spoils chest since the morning (not saved). */
    public int spoilsTaken;
    /** Scouts' maps waiting to go up in the hall. */
    public final List<net.minecraft.world.item.ItemStack> pendingMaps = new ArrayList<>();
    /**
     * The map room's wall: top-left frame cell x, y, z, the way the frames face (a Direction's 3D index), frames
     * across and down, and the map squares (MapRoom.CELL blocks) of the castle itself. Null until scouts come home.
     */
    @Nullable public int[] mapRoom;
    /** The steward keeps the chests in order (Tidy). */
    public boolean tidy;
    /** Not saved: items the steward has moved between chests since the last dawn. */
    public int tidied;
    /** Not saved: when the castle last looked for a map-room wall and found none. */
    public long mapRoomTriedAt;
    /** A ruin (see {@link Ruins}): brigand-held, a few men on the ground, until the Master Builder restores it. */
    public boolean ruined;
    /** The vassal lord who holds it in the owner's name (his first name), and his loyalty, 0 to 100. */
    @Nullable public String vassal;
    public int loyalty;
    /** The places the builder has laid a road to from here. */
    public final java.util.Set<UUID> roadsTo = new java.util.LinkedHashSet<>();
    /** Brigand camps sitting on those roads, by the place at the far end: no tolls while they stand. */
    public final Map<UUID, UUID> roadCamps = new HashMap<>();
    /**
     * The son of the lord it was taken from, who wants it back (see {@link Heirs}): his name, his father's, and
     * how many of his attacks have been thrown back. Empty when nobody claims it.
     */
    public String heir = "", heirFather = "";
    public int heirBeaten;
    /** When a tournament was last held here (see {@link Tourney}). */
    public long lastTourney;
    /** Men your other castles sent to hold this one while it's attacked, by castle: {knights, archers} (see {@link Relief}). */
    public final Map<UUID, int[]> relief = new HashMap<>();
    /** The garrison's knights and archers once they'd come, to tell how many came through. */
    public int[] reliefAfter = {0, 0};

    public int fiefLevel() {
        int cottages = 0;
        for (FiefBuilding b : fief) if (b.piece().startsWith("cottage")) cottages++;
        return fief.isEmpty() ? 0 : cottages >= 6 ? 3 : cottages >= 4 ? 2 : 1;
    }

    public String fiefName() {
        return switch (fiefLevel()) {
            case 3 -> "Town";
            case 2 -> "Village";
            case 1 -> "Hamlet";
            default -> "No fief";
        };
    }
    @Nullable public String contestSide;
    public int contestProgress;
    public int contestDefenders;
    @Nullable public UUID claimant;
    public boolean counterattackSpawned;
    /** Not saved: reinforcement waves sent against the stand during this contest. */
    public int wavesSent;

    // runtime only
    public final Map<UUID, Role> live = new HashMap<>();
    public final Set<BlockPos> usedPosts = new HashSet<>();
    /** Portcullis planes, found on first tick near the castle; null until scanned. Not saved. */
    public java.util.List<Gates.Plane> gates;
    /** When to look again for gate planes whose chunks weren't loaded at the last scan; 0 if none are missing. Not saved. */
    public long gatesRescanAt;
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

    /** Capacity for this castle: the tier's, no more men than it has beds, plus 15% per stage of fortification. */
    public int capacity(Role role) {
        int base = capacity(tier, role);
        if (ruined) return Ruins.capacity(base, role);
        if (role == Role.CAPTAIN) return base;
        Layout l = layout != null ? DataRegistries.layout(layout) : null;
        if (l != null && l.beds > 0) {
            int total = 0;
            for (Role r : Role.TROOPS) total += capacity(tier, r);
            if (l.beds < total) base = Math.max(role == Role.KNIGHT ? 2 : 0, (int) Math.round(base * (double) l.beds / total));
        }
        if (Council.has(ownerPlayer(), Council.Seat.MARSHAL)) base = (int) Math.round(base * 1.15);
        if (fortify <= 0) return base;
        return (int) Math.round(base * (1 + 0.15 * fortify));
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
        if (lordAlive && (isHouseOwned() || Vassals.is(this))) s += Role.LORD.strength();
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

    /**
     * The plans this castle was built from, if they still describe it. A layout that has since been redrawn
     * at another size (the castle grew in a later build) is no guide to a castle already standing: repairs and
     * gate restoration would set the new plan down over the old walls.
     */
    @Nullable
    public Layout plans() {
        Layout l = layout != null ? DataRegistries.layout(layout) : null;
        if (l == null || box == null) return null;
        if (box.getXSpan() != l.sizeX || box.getZSpan() != l.sizeZ) return null;
        return l;
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
        t.putInt("GearLeft", gearLeft);
        t.putInt("GearGiven", gearGiven);
        if (!spoilsCarried.isEmpty()) {
            net.minecraft.nbt.ListTag carried = new net.minecraft.nbt.ListTag();
            for (net.minecraft.world.item.ItemStack st : spoilsCarried) carried.add(st.save(new CompoundTag()));
            t.put("SpoilsCarried", carried);
        }
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
        ListTag lp = new ListTag();
        lips.forEach((q, d) -> lp.add(new net.minecraft.nbt.IntArrayTag(new int[]{q.getX(), q.getY(), q.getZ(), d[0], d[1]})));
        t.put("Lips", lp);
        ListTag oh = new ListTag();
        ownedHouses.forEach((p2, u) -> {
            CompoundTag e = NbtUtils.writeBlockPos(p2);
            e.putUUID("Owner", u);
            oh.add(e);
        });
        t.put("OwnedHouses", oh);
        ListTag sk = new ListTag();
        shopkeepers.forEach((p2, u) -> {
            CompoundTag e = NbtUtils.writeBlockPos(p2);
            e.putUUID("Keeper", u);
            sk.add(e);
        });
        t.put("Shopkeepers", sk);
        t.putLong("ShopsDay", shopsDay);
        t.putInt("ShopsVersion", shopsVersion);
        t.putInt("MarketVersion", marketVersion);
        CompoundTag cu = new CompoundTag();
        custom.forEach((k, v) -> cu.putInt(k.toString(), v));
        t.put("Custom", cu);
        t.putInt("Miners", miners);
        t.putInt("Smiths", smiths);
        t.putInt("MinersAt", minersAt);
        t.putInt("SmithsAt", smithsAt);
        t.putInt("Forged", forged);
        t.putInt("ForgeWork", forgeWork);
        t.putInt("ForgeTarget", forgeTarget);
        t.putBoolean("ForgePaid", forgePaid);
        t.putString("LabourReport", labourReport);
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
        t.putLong("NextColumn", nextColumn);
        t.putInt("ReinforceKeep", reinforceKeep);
        t.putBoolean("RaidWarned", raidWarned);
        t.putLong("NextWarband", nextWarband);
        t.putBoolean("WarbandWarned", warbandWarned);
        t.putLong("DamagedAt", damagedAt);
        t.putLong("GateBrokenAt", gateBrokenAt);
        t.putBoolean("Villagers", villagersSpawned);
        t.putBoolean("BannersDirty", bannersDirty);
        t.putBoolean("Surroundings", surroundingsDone);
        if (fiefPlan != null) {
            ListTag fp = new ListTag();
            for (String n : fiefPlan) fp.add(StringTag.valueOf(n));
            t.put("FiefPlan", fp);
        }
        ListTag fb = new ListTag();
        for (FiefBuilding b : fief) {
            CompoundTag bt = new CompoundTag();
            bt.putString("Piece", b.piece());
            bt.putIntArray("Box", new int[]{b.box().minX(), b.box().minY(), b.box().minZ(), b.box().maxX(), b.box().maxY(), b.box().maxZ()});
            fb.add(bt);
        }
        t.put("Fief", fb);
        t.putInt("FiefTries", fiefTries);
        CompoundTag dg = new CompoundTag();
        digs.forEach((k, v) -> dg.put(Long.toString(k), v.save()));
        t.put("Digs", dg);
        t.putInt("Growth", growth);
        t.putLong("LastRaidAt", lastRaidAt);
        t.putString("Work", work);
        t.putInt("Fortify", fortify);
        t.putInt("WorkDone", workDone);
        ListTag roadTags = new ListTag();
        for (UUID r : roadsTo) roadTags.add(NbtUtils.createUUID(r));
        t.put("RoadsTo", roadTags);
        CompoundTag campTags = new CompoundTag();
        roadCamps.forEach((k, v) -> campTags.putUUID(k.toString(), v));
        t.put("RoadCamps", campTags);
        t.putString("Steward", stewardName);
        if (vassal != null) t.putString("Vassal", vassal);
        if (ruined) t.putBoolean("Ruined", true);
        t.putInt("Loyalty", loyalty);
        ListTag mapTags = new ListTag();
        for (var m : pendingMaps) mapTags.add(m.save(new CompoundTag()));
        t.put("PendingMaps", mapTags);
        if (mapRoom != null) t.putIntArray("MapRoom", mapRoom);
        t.putBoolean("Tidy", tidy);
        if (!heir.isEmpty()) {
            t.putString("Heir", heir);
            t.putString("HeirFather", heirFather);
            t.putInt("HeirBeaten", heirBeaten);
        }
        t.putLong("LastTourney", lastTourney);
        if (!relief.isEmpty()) t.put("Relief", Relief.save(this));
        t.putString("BuilderName", builderName);
        if (builder != null) t.putUUID("Builder", builder);
        if (waystone != null) t.put("Waystone", NbtUtils.writeBlockPos(waystone));
        t.putString("FiefReport", fiefReport);
        t.putInt("FiefPop", fiefPop);
        t.putInt("FiefHerd", fiefHerd);
        CompoundTag fj = new CompoundTag();
        fiefJobs.forEach(fj::putInt);
        t.put("FiefJobs", fj);
        CompoundTag fr = new CompoundTag();
        fiefReal.forEach(fr::putInt);
        t.put("FiefReal", fr);
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
        h.gearLeft = t.contains("GearLeft") ? t.getInt("GearLeft") : -1;
        h.gearGiven = t.getInt("GearGiven");
        for (net.minecraft.nbt.Tag x : t.getList("SpoilsCarried", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            net.minecraft.world.item.ItemStack st = net.minecraft.world.item.ItemStack.of((CompoundTag) x);
            if (!st.isEmpty()) h.spoilsCarried.add(st);
        }
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
        ListTag lp = t.getList("Lips", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < lp.size(); i++) {
            int[] a = lp.getIntArray(i);
            if (a.length == 5) h.lips.put(new BlockPos(a[0], a[1], a[2]), new int[]{a[3], a[4]});
        }
        ListTag oh = t.getList("OwnedHouses", Tag.TAG_COMPOUND);
        for (int i = 0; i < oh.size(); i++) {
            CompoundTag e = oh.getCompound(i);
            if (e.hasUUID("Owner")) h.ownedHouses.put(NbtUtils.readBlockPos(e), e.getUUID("Owner"));
        }
        ListTag sk = t.getList("Shopkeepers", Tag.TAG_COMPOUND);
        for (int i = 0; i < sk.size(); i++) {
            CompoundTag e = sk.getCompound(i);
            if (e.hasUUID("Keeper")) h.shopkeepers.put(NbtUtils.readBlockPos(e), e.getUUID("Keeper"));
        }
        h.shopsDay = t.contains("ShopsDay") ? t.getLong("ShopsDay") : -1;
        h.shopsVersion = t.getInt("ShopsVersion");
        h.marketVersion = t.getInt("MarketVersion");
        CompoundTag cu = t.getCompound("Custom");
        for (String k : cu.getAllKeys()) {
            try {
                h.custom.put(UUID.fromString(k), cu.getInt(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
        h.miners = t.getInt("Miners");
        h.smiths = t.getInt("Smiths");
        h.minersAt = t.getInt("MinersAt");
        h.smithsAt = t.getInt("SmithsAt");
        h.forged = t.getInt("Forged");
        h.forgeWork = t.getInt("ForgeWork");
        h.forgeTarget = t.getInt("ForgeTarget");
        h.forgePaid = t.getBoolean("ForgePaid");
        h.labourReport = t.getString("LabourReport");
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
        h.nextColumn = t.getLong("NextColumn");
        h.reinforceKeep = t.getInt("ReinforceKeep");
        h.raidWarned = t.getBoolean("RaidWarned");
        h.nextWarband = t.getLong("NextWarband");
        h.warbandWarned = t.getBoolean("WarbandWarned");
        h.damagedAt = t.getLong("DamagedAt");
        h.gateBrokenAt = t.getLong("GateBrokenAt");
        h.villagersSpawned = t.getBoolean("Villagers");
        h.bannersDirty = t.getBoolean("BannersDirty");
        h.surroundingsDone = t.getBoolean("Surroundings");
        if (t.contains("FiefPlan")) {
            h.fiefPlan = new ArrayList<>();
            for (var e : t.getList("FiefPlan", 8)) h.fiefPlan.add(e.getAsString());
        }
        for (var e : t.getList("Fief", 10)) {
            CompoundTag bt = (CompoundTag) e;
            int[] fb2 = bt.getIntArray("Box");
            if (fb2.length == 6) h.fief.add(new FiefBuilding(bt.getString("Piece"), new BoundingBox(fb2[0], fb2[1], fb2[2], fb2[3], fb2[4], fb2[5])));
        }
        h.fiefTries = t.getInt("FiefTries");
        CompoundTag dg = t.getCompound("Digs");
        for (String k : dg.getAllKeys()) {
            try {
                h.digs.put(Long.parseLong(k), Produce.Dig.load(dg.getCompound(k)));
            } catch (Exception ignored) {
            }
        }
        h.growth = t.getInt("Growth");
        h.lastRaidAt = t.getLong("LastRaidAt");
        if (t.contains("Work")) h.work = t.getString("Work");
        h.fortify = t.getInt("Fortify");
        h.workDone = t.getInt("WorkDone");
        for (var r : t.getList("RoadsTo", 11)) h.roadsTo.add(NbtUtils.loadUUID(r));
        CompoundTag campTags = t.getCompound("RoadCamps");
        for (String k : campTags.getAllKeys()) h.roadCamps.put(UUID.fromString(k), campTags.getUUID(k));
        h.stewardName = t.getString("Steward");
        h.vassal = t.contains("Vassal") ? t.getString("Vassal") : null;
        h.ruined = t.getBoolean("Ruined");
        h.loyalty = t.getInt("Loyalty");
        for (var m : t.getList("PendingMaps", 10)) h.pendingMaps.add(net.minecraft.world.item.ItemStack.of((CompoundTag) m));
        h.tidy = t.getBoolean("Tidy");
        h.heir = t.getString("Heir");
        h.heirFather = t.getString("HeirFather");
        h.heirBeaten = t.getInt("HeirBeaten");
        h.lastTourney = t.getLong("LastTourney");
        if (t.contains("Relief")) Relief.load(h, t.getCompound("Relief"));
        if (t.contains("MapRoom")) {
            int[] mr = t.getIntArray("MapRoom");
            if (mr.length == 9) h.mapRoom = mr;
        }
        h.builderName = t.getString("BuilderName");
        if (t.hasUUID("Builder")) h.builder = t.getUUID("Builder");
        if (t.contains("Waystone")) h.waystone = NbtUtils.readBlockPos(t.getCompound("Waystone"));
        h.fiefReport = t.getString("FiefReport");
        h.fiefPop = t.getInt("FiefPop");
        h.fiefHerd = t.getInt("FiefHerd");
        CompoundTag fj = t.getCompound("FiefJobs");
        for (String k : fj.getAllKeys()) h.fiefJobs.put(k, fj.getInt(k));
        CompoundTag fr = t.getCompound("FiefReal");
        for (String k : fr.getAllKeys()) h.fiefReal.put(k, fr.getInt(k));
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
