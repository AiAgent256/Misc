package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Loadouts;
import net.aiagent256.stormhold.house.NameGen;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Your people at work for the garrison. In a castle you hold, the War Table sets some of its commoners to
 * the mine or the forge. Those chosen take the trade (toolsmith's apron for a miner, armourer's for a smith),
 * their name and trade over their heads.
 *
 * <p>Miners bring up the day's ore into the stores, smelted: coal, iron, copper, gold, and now and then
 * redstone, lapis, emeralds and diamonds. The deep seams of the frontier give more diamonds, and from zone 7
 * ancient debris (netherite scrap).
 *
 * <p>Smiths forge the garrison's kit from the stores' metal, a level at a time above what the castle has:
 * iron for levels 2 and 3, diamonds for 4 to 6, netherite for 7 to 9, and six smith-days of work for each.
 * When a level is done every man on the walls is re-armed at it, and the men who come after are kitted
 * at it too.
 */
public final class Labour {
    public static final String TAG = "StormholdJob";
    public static final String HOLDING_TAG = "StormholdJobHolding";
    private static final String WAS_TAG = "StormholdJobWas";
    public static final String MINER = "miner", SMITH = "smith";
    /** Most of one trade a castle can set to work. */
    public static final int MAX = 8;
    /** Smith-days of work to forge one level of kit. */
    public static final int SMITH_DAYS = 6;

    private static final ResourceLocation COAL = new ResourceLocation("minecraft", "coal"),
            IRON = new ResourceLocation("minecraft", "iron_ingot"), DIAMOND = new ResourceLocation("minecraft", "diamond"),
            NETHERITE = new ResourceLocation("minecraft", "netherite_ingot"),
            SCRAP = new ResourceLocation("minecraft", "netherite_scrap"), GOLD = new ResourceLocation("minecraft", "gold_ingot");

    public static int wanted(Holding h, String job) {
        return job.equals(MINER) ? h.miners : h.smiths;
    }

    /** The War Table's order: how many of the castle's people to set to a trade. */
    public static Component set(Holding h, String job, int n) {
        n = Math.max(0, Math.min(MAX, n));
        if (job.equals(MINER)) h.miners = n;
        else h.smiths = n;
        String trade = job.equals(MINER) ? "the mine" : "the forge";
        return HoldingEvents.gold(n == 0 ? "Nobody at " + h.name + " works " + trade + " now."
                : n + " of the people of " + h.name + " will work " + trade + " (as many as there are to spare).");
    }

    // ------------------------------------------------------------ choosing the workers

    /** Keep the right number at each trade: take on more from the idle, let go of any too many. */
    public static void tick(ServerLevel level, HoldingManager mgr, Holding h) {
        if (h.id == null) return;
        boolean ours = h.isPlayerOwned();
        boolean anyone = h.minersAt > 0 || h.smithsAt > 0 || (ours && (h.miners > 0 || h.smiths > 0));
        if (!anyone) return;
        BlockPos c = h.center();
        if (!level.isLoaded(c)) return;
        java.util.Set<UUID> builders = new java.util.HashSet<>();
        for (Holding o : mgr.holdings.values()) if (o.builder != null) builders.add(o.builder);
        AABB area = new AABB(c).inflate(Fief.REACH, 80, Fief.REACH);
        List<Villager> all = level.getEntitiesOfClass(Villager.class, area, v -> v.isAlive() && !v.isBaby());
        Map<String, List<Villager>> working = new LinkedHashMap<>();
        working.put(MINER, new ArrayList<>());
        working.put(SMITH, new ArrayList<>());
        List<Villager> idle = new ArrayList<>();
        for (Villager v : all) {
            var d = v.getPersistentData();
            if (d.contains(TAG) && d.hasUUID(HOLDING_TAG) && h.id.equals(d.getUUID(HOLDING_TAG))) {
                working.computeIfAbsent(d.getString(TAG), k -> new ArrayList<>()).add(v);
            } else if (!d.contains(TAG) && free(builders, v)) {
                idle.add(v);
            }
        }
        // the jobless first, then the nearest
        idle.sort(Comparator.<Villager>comparingInt(v -> jobless(v) ? 0 : 1)
                .thenComparingDouble(v -> v.distanceToSqr(c.getX(), c.getY(), c.getZ())));
        boolean dirty = false;
        for (String job : List.of(MINER, SMITH)) {
            List<Villager> have = working.get(job);
            int want = ours ? wanted(h, job) : 0;
            while (have.size() > want) {
                release(have.remove(have.size() - 1));
                dirty = true;
            }
            while (have.size() < want && !idle.isEmpty()) {
                Villager v = idle.remove(0);
                hire(level, h, v, job);
                have.add(v);
                dirty = true;
            }
            int n = have.size();
            if (job.equals(MINER) && h.minersAt != n) {
                h.minersAt = n;
                dirty = true;
            } else if (job.equals(SMITH) && h.smithsAt != n) {
                h.smithsAt = n;
                dirty = true;
            }
        }
        if (dirty) mgr.setDirty();
    }

    /**
     * A commoner who can be spared: nobody's trading partner yet (no more experience than a castle's people
     * start with), not a shopkeeper, not any castle's Master Builder, not one standing at a post.
     */
    private static boolean free(java.util.Set<UUID> builders, Villager v) {
        if (v.isNoAi() || v.getPersistentData().contains(Shops.TAG) || builders.contains(v.getUUID())) return false;
        return v.getVillagerXp() <= 10;
    }

    private static boolean jobless(Villager v) {
        var p = v.getVillagerData().getProfession();
        return p == VillagerProfession.NONE || p == VillagerProfession.NITWIT;
    }

    private static void hire(ServerLevel level, Holding h, Villager v, String job) {
        // what he was, to be given back when he's let go
        CompoundTag was = new CompoundTag();
        ResourceLocation wasProf = ForgeRegistries.VILLAGER_PROFESSIONS.getKey(v.getVillagerData().getProfession());
        was.putString("Profession", wasProf == null ? "minecraft:none" : wasProf.toString());
        was.putInt("Level", v.getVillagerData().getLevel());
        was.putInt("Xp", v.getVillagerXp());
        if (!v.getOffers().isEmpty()) was.put("Offers", v.getOffers().createTag());
        if (v.hasCustomName()) was.putString("Name", Component.Serializer.toJson(v.getCustomName()));
        was.putBoolean("NameShown", v.isCustomNameVisible());
        v.getPersistentData().put(WAS_TAG, was);
        v.getPersistentData().putString(TAG, job);
        v.getPersistentData().putUUID(HOLDING_TAG, h.id);
        VillagerProfession prof = job.equals(MINER) ? VillagerProfession.TOOLSMITH : VillagerProfession.ARMORER;
        var data = v.getVillagerData();
        v.setVillagerData(data.setProfession(prof).setLevel(Math.max(2, data.getLevel())));
        if (v.getVillagerXp() < 10) v.setVillagerXp(10);
        String first = v.hasCustomName() ? v.getCustomName().getString().split(" ")[0] : NameGen.commoner(level.random, "none").split(" ")[0];
        v.setCustomName(Component.literal(first + " the " + (job.equals(MINER) ? "Miner" : "Smith")));
        v.setCustomNameVisible(true);
        v.setPersistenceRequired();
    }

    /** Let a worker go: back to the trade, the trades and the name he had. */
    static void release(Villager v) {
        var d = v.getPersistentData();
        d.remove(TAG);
        d.remove(HOLDING_TAG);
        CompoundTag was = d.getCompound(WAS_TAG);
        d.remove(WAS_TAG);
        if (was.isEmpty()) {
            if (v.hasCustomName()) v.setCustomName(Component.literal(v.getCustomName().getString().split(" ")[0]));
            v.setCustomNameVisible(false);
            return;
        }
        VillagerProfession prof = ForgeRegistries.VILLAGER_PROFESSIONS.getValue(new ResourceLocation(was.getString("Profession")));
        if (prof == null) prof = VillagerProfession.NONE;
        v.setVillagerData(v.getVillagerData().setProfession(prof).setLevel(Math.max(1, was.getInt("Level"))));
        v.setVillagerXp(was.getInt("Xp"));
        if (was.contains("Offers")) {
            var offers = new net.minecraft.world.item.trading.MerchantOffers(was.getCompound("Offers"));
            v.getOffers().clear();
            v.getOffers().addAll(offers);
        }
        v.setCustomName(was.contains("Name") ? Component.Serializer.fromJson(was.getString("Name")) : null);
        v.setCustomNameVisible(was.getBoolean("NameShown"));
    }

    // ------------------------------------------------------------ the day's work

    /** At dawn, in a castle you hold: the miners' haul into the stores and the smiths' day at the forge. */
    public static void daily(ServerLevel level, HoldingManager mgr, Holding h) {
        if (!h.isPlayerOwned()) return;
        List<String> said = new ArrayList<>();
        int miners = Math.min(h.minersAt, h.miners), smiths = Math.min(h.smithsAt, h.smiths);
        if (miners > 0) said.add(mine(level, h, miners));
        if (smiths > 0) said.add(forge(level, mgr, h, smiths));
        h.labourReport = String.join(" ", said);
        mgr.setDirty();
    }

    /** One day's ore from n miners, by the zone the castle stands in. Into the stores; returns the report. */
    public static String mine(ServerLevel level, Holding h, int n) {
        var r = level.random;
        int zone = Frontier.zone(level, h.center());
        Map<String, Integer> haul = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            haul.merge("coal", 6, Integer::sum);
            haul.merge("iron_ingot", 3, Integer::sum);
            haul.merge("copper_ingot", 2, Integer::sum);
            if (r.nextFloat() < 0.5F) haul.merge("gold_ingot", 1, Integer::sum);
            if (r.nextFloat() < 0.4F) haul.merge("redstone", 4, Integer::sum);
            if (r.nextFloat() < 0.3F) haul.merge("lapis_lazuli", 3, Integer::sum);
            if (r.nextFloat() < 0.05F) haul.merge("emerald", 1, Integer::sum);
            if (r.nextFloat() < 0.08F + 0.03F * (zone - 1)) haul.merge("diamond", 1, Integer::sum);
            if (zone >= 7 && r.nextFloat() < 0.02F * (zone - 6)) haul.merge("netherite_scrap", 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        for (var e : haul.entrySet()) {
            h.addStock(new ResourceLocation("minecraft", e.getKey()), e.getValue());
            parts.add(e.getValue() + " " + e.getKey().replace("_ingot", "").replace("_lazuli", "").replace('_', ' '));
        }
        return "Miners " + n + " brought up " + String.join(", ", parts) + ".";
    }

    /** The metal one level of kit costs, as {iron, diamonds, netherite}. */
    public static int[] cost(int level) {
        if (level <= 3) return new int[]{24, 0, 0};
        if (level <= 6) return new int[]{8, 12, 0};
        return new int[]{0, 8, 4};
    }

    public static String kitName(int level) {
        return level >= 9 ? "netherite, Protection IV" : level >= 7 ? "netherite" : level >= 4 ? "diamond" : "iron";
    }

    /** One day at the forge for n smiths. Returns the report. */
    public static String forge(ServerLevel level, HoldingManager mgr, Holding h, int n) {
        int target = target(level, h);
        if (target > Frontier.MAX) return "Smiths " + n + ": the garrison's kit is the best there is.";
        if (!h.forgePaid && !payMetal(mgr, h, target)) {
            int[] c = cost(target);
            return "Smiths " + n + " wait on metal for " + kitName(target) + " kit (level " + target + "): " + costText(c)
                    + (c[2] > 0 ? " (4 netherite scrap and 4 gold make an ingot)" : "") + ".";
        }
        h.forgeWork += n;
        if (h.forgeWork < SMITH_DAYS) {
            return "Smiths " + n + " are forging " + kitName(target) + " kit (level " + target + "): " + h.forgeWork + " of "
                    + SMITH_DAYS + " smith-days done.";
        }
        int armed = finish(level, mgr, h, target);
        return "Smiths " + n + " finished " + kitName(target) + " kit (level " + target + "); " + armed + " men on the walls re-armed.";
    }

    /** The level the forge is working toward: the one paid for, else the next above what the castle has. */
    public static int target(ServerLevel level, Holding h) {
        return h.forgePaid && h.forgeTarget > 0 ? h.forgeTarget : Frontier.level(level, h) + 1;
    }

    /** Whether the stores and the castle's chests hold the metal for a level. */
    public static boolean hasMetal(HoldingManager mgr, Holding h, int target) {
        UUID owner = h.ownerPlayer();
        int[] c = cost(target);
        int ingots = Stores.stockWithChests(mgr, h, owner, NETHERITE);
        int fromScrap = Math.min(Stores.stockWithChests(mgr, h, owner, SCRAP) / 4, Stores.stockWithChests(mgr, h, owner, GOLD) / 4);
        return Stores.stockWithChests(mgr, h, owner, IRON) >= c[0] && Stores.stockWithChests(mgr, h, owner, DIAMOND) >= c[1]
                && ingots + fromScrap >= c[2];
    }

    /** Take the metal for a level from the stores and the castle's chests and set the forge to it. False if it isn't there. */
    static boolean payMetal(HoldingManager mgr, Holding h, int target) {
        if (!hasMetal(mgr, h, target)) return false;
        UUID owner = h.ownerPlayer();
        int[] c = cost(target);
        int ingots = Stores.stockWithChests(mgr, h, owner, NETHERITE);
        Stores.takeWithChests(mgr, h, owner, IRON, c[0]);
        Stores.takeWithChests(mgr, h, owner, DIAMOND, c[1]);
        int fromIngots = Math.min(ingots, c[2]);
        Stores.takeWithChests(mgr, h, owner, NETHERITE, fromIngots);
        int smelt = c[2] - fromIngots;
        Stores.takeWithChests(mgr, h, owner, SCRAP, 4 * smelt);
        Stores.takeWithChests(mgr, h, owner, GOLD, 4 * smelt);
        h.forgePaid = true;
        h.forgeTarget = target;
        h.forgeWork = 0;
        return true;
    }

    /** The level is done: the castle's kit goes up and every man on the walls is re-armed. How many were. */
    static int finish(ServerLevel level, HoldingManager mgr, Holding h, int target) {
        h.forged = Math.max(h.forged, target);
        h.forgeWork = 0;
        h.forgePaid = false;
        h.forgeTarget = 0;
        mgr.setDirty();
        int armed = rearm(level, mgr, h);
        Chronicle.log(level, mgr, Chronicle.GROWTH, "The smiths of " + h.name + " have forged " + kitName(target)
                + " kit for its garrison (level " + target + ").", h.ownerPlayer());
        return armed;
    }

    /** Emeralds a smith-day of the level still to do costs, by the level: hired hands and work through the night. */
    public static final int RUSH_PER_LEVEL_DAY = 2;

    /** What finishing the next level now costs in emeralds on top of its metal: level x smith-days left x 2. -1 at the top. */
    public static int rushCost(ServerLevel level, Holding h) {
        int target = target(level, h);
        if (target > Frontier.MAX) return -1;
        int left = SMITH_DAYS - (h.forgePaid ? Math.min(SMITH_DAYS, h.forgeWork) : 0);
        return target * left * RUSH_PER_LEVEL_DAY;
    }

    /** Buy the next level of kit now: its metal (unless the forge has it already) and the rush fee, then every man re-armed. */
    public static boolean rush(ServerLevel level, HoldingManager mgr, Holding h, Player player) {
        int target = target(level, h);
        if (target > Frontier.MAX) {
            player.sendSystemMessage(Component.literal(h.name + "'s garrison has the best kit there is.").withStyle(ChatFormatting.GOLD));
            return false;
        }
        int left = SMITH_DAYS - (h.forgePaid ? Math.min(SMITH_DAYS, h.forgeWork) : 0);
        int fee = rushCost(level, h);
        int have = Stores.packPlusRealm(mgr, h, player, Items.EMERALD);
        if (!h.forgePaid && !hasMetal(mgr, h, target)) {
            player.sendSystemMessage(Component.literal(kitName(target) + " kit (level " + target + ") takes " + costText(cost(target))
                    + " from the stores or " + h.name + "'s chests, and that isn't there yet.").withStyle(ChatFormatting.RED));
            return false;
        }
        if (have < fee) {
            player.sendSystemMessage(Component.literal("Finishing level " + target + " now costs " + fee + " emeralds (level " + target + " x "
                    + left + " smith-days left x " + RUSH_PER_LEVEL_DAY + "); you have " + have + " in your pack and the stores.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        boolean metalNow = !h.forgePaid;
        if (metalNow) payMetal(mgr, h, target);
        Stores.takeFromPackThenRealm(mgr, h, player, Items.EMERALD, fee);
        int armed = finish(level, mgr, h, target);
        player.sendSystemMessage(HoldingEvents.gold("Hired hands work the forges of " + h.name + " through the night: " + kitName(target)
                + " kit (level " + target + ") is done and " + armed + " men on the walls are re-armed. Paid " + fee + " emeralds (level "
                + target + " x " + left + " smith-days left x " + RUSH_PER_LEVEL_DAY + ")" + (metalNow ? " and " + costText(cost(target)) : "") + "."));
        return true;
    }

    private static String costText(int[] c) {
        List<String> p = new ArrayList<>();
        if (c[0] > 0) p.add(c[0] + " iron");
        if (c[1] > 0) p.add(c[1] + " diamonds");
        if (c[2] > 0) p.add(c[2] + " netherite ingots");
        return String.join(", ", p);
    }

    /** Every man of the garrison standing now, re-armed at the castle's kit level. */
    public static int rearm(ServerLevel level, HoldingManager mgr, Holding h) {
        int lvl = Frontier.level(level, h), n = 0;
        for (UUID id : new ArrayList<>(h.live.keySet())) {
            if (!(level.getEntity(id) instanceof GarrisonEntity g) || !g.isAlive() || g.owner() != null) continue;
            Loadouts.equip(g, g.role(), g.tier(), lvl, g.color(), mgr.houseOf(h.owner));
            n++;
        }
        return n;
    }

    @Nullable
    public static String jobOf(Villager v) {
        return v.getPersistentData().contains(TAG) ? v.getPersistentData().getString(TAG) : null;
    }

    private Labour() {
    }
}
