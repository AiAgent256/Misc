package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * How a fight for a place is likely to go, before you start it. A rule of thumb, the same one the advice
 * gives: you need about a third as many men as you'll face all told, at the same kit; every step of kit you
 * are behind (chain, iron, diamond, netherite, Protection IV) needs half as many again.
 */
public final class Readiness {
    /** Kit steps: 0 chain, 1 iron, 2 diamond, 3 netherite, 4 netherite with Protection IV. The same steps the knights are armed by. */
    public static int tier(int level) {
        return level >= 9 ? 4 : level >= 7 ? 3 : level >= 4 ? 2 : level >= 2 ? 1 : 0;
    }

    public static String kitName(int tier) {
        return switch (tier) {
            case 4 -> "netherite, Protection IV";
            case 3 -> "netherite";
            case 2 -> "diamond";
            case 1 -> "iron";
            default -> "chain";
        };
    }

    /** What a man is worth at a kit step, against a man in chain. */
    public static double factor(int tier) {
        return Math.pow(1.5, tier);
    }

    /** You count for this many men, at your own kit. */
    public static final int YOU = 3;

    /** Men at kit step {@code ours} you'd want, besides yourself at {@code mine}, to take a place of {@code total} men at {@code theirs}. */
    public static int needed(int total, int theirs, int ours, int mine) {
        double want = total / 3.0 * factor(theirs) - YOU * factor(mine);
        return Math.max(0, (int) Math.ceil(want / factor(ours)));
    }

    /** Your side against theirs: 1.0 is the rule of thumb met. */
    public static double ratio(int total, int theirs, int men, int ours, int mine) {
        double want = Math.max(1, total / 3.0 * factor(theirs));
        return (men * factor(ours) + YOU * factor(mine)) / want;
    }

    public static String verdict(double r) {
        return r >= 1.5 ? "you should win it" : r >= 1.0 ? "an even fight" : r >= 0.6 ? "hard: expect to lose men" : "too strong for you yet";
    }

    /** The kit step of what the player has on: by the armour's worth, Protection IV all round on netherite counting one more. */
    public static int worn(ServerPlayer p) {
        double worth = 0;
        int prot4 = 0;
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = p.getItemBySlot(s);
            if (st.getItem() instanceof ArmorItem a) worth += a.getDefense() + a.getToughness();
            if (EnchantmentHelper.getItemEnchantmentLevel(Enchantments.ALL_DAMAGE_PROTECTION, st) >= 4) prot4++;
        }
        // full sets: chain 12, iron 15, diamond 28, netherite 32
        int t = worth >= 30 ? 3 : worth >= 24 ? 2 : worth >= 14 ? 1 : 0;
        return t == 3 && prot4 == 4 ? 4 : t;
    }

    /** The men riding with the player: how many, and the kit step most of them are at. */
    public static int[] company(ServerLevel level, ServerPlayer p) {
        List<GarrisonEntity> men = level.getEntitiesOfClass(GarrisonEntity.class, new AABB(p.blockPosition()).inflate(64),
                g -> g.isAlive() && p.getUUID().equals(g.owner()));
        if (men.isEmpty()) return new int[]{0, 0};
        int[] at = new int[5];
        for (GarrisonEntity g : men) at[tier(g.renown())]++;
        int best = 0;
        for (int i = 1; i < 5; i++) if (at[i] > at[best]) best = i;
        return new int[]{men.size(), best};
    }

    /** The kit step the player's men come at: the best his places can arm them. */
    public static int menTier(ServerLevel level, HoldingManager mgr, UUID player) {
        int best = -1;
        for (Holding h : mgr.ownedBy(player)) best = Math.max(best, tier(Frontier.level(level, h)));
        return Math.max(0, best);
    }

    // ------------------------------------------------------------ the lines shown

    /** For the guide, at a place you don't hold: what it'll take, and how you stand now. */
    @Nullable
    public static String guideLine(ServerLevel level, HoldingManager mgr, Holding h, ServerPlayer p) {
        if (h.isPlayerOwned() && p.getUUID().equals(h.ownerPlayer())) return null;
        int total = HoldingEvents.defendersInAll(h);
        if (total <= 0) return null;
        int theirs = tier(Frontier.level(level, h)), mine = worn(p);
        int[] comp = company(level, p);
        int menAt = comp[0] > 0 ? comp[1] : menTier(level, mgr, p.getUUID());
        int need = needed(total, theirs, menAt, mine);
        int needUp = menAt < 4 ? needed(total, theirs, menAt + 1, mine) : need;
        int cap = Titles.musterCap(Titles.rank(mgr, p.getUUID()));
        StringBuilder s = new StringBuilder("About " + total + " men to fight through, in " + kitName(theirs) + ". You in "
                + kitName(mine) + " count for " + YOU + "; bring about " + need + " in " + kitName(menAt));
        if (needUp < need) s.append(" (").append(needUp).append(" in ").append(kitName(menAt + 1)).append(")");
        s.append(". With you now: ").append(comp[0]).append(comp[0] == 1 ? " man" : " men").append(", ")
                .append(verdict(ratio(total, theirs, comp[0], comp[1], mine))).append(".");
        if (need > cap) s.append(" Your title calls ").append(cap).append(" at a time: send columns, or win a bigger title first.");
        return s.toString();
    }

    /** For the War Table: the castle it's set to march on, if there is one near the mark. */
    @Nullable
    public static String tableLine(ServerLevel level, HoldingManager mgr, Holding from, @Nullable BlockPos target) {
        if (target == null) return null;
        Holding enemy = mgr.atXZ(target, 16);
        if (enemy == null || enemy.owner.equals(from.owner)) return null;
        int total = HoldingEvents.defendersInAll(enemy);
        int theirs = tier(Frontier.level(level, enemy)), ours = tier(Frontier.level(level, from));
        int party = 0;
        for (Role r : new Role[]{Role.CAPTAIN, Role.KNIGHT, Role.ARCHER, Role.CROSSBOWMAN}) party += Campaigns.sends(from, r);
        String led = verdict(ratio(total, theirs, party, ours, ours));
        String alone = Campaigns.odds(level, from, enemy);
        return "Against " + enemy.name + ": about " + total + " men in " + kitName(theirs) + ". A war party from here is " + party
                + " in " + kitName(ours) + ". Led by you: " + led + ". Sent alone: " + alone + ".";
    }

    private Readiness() {
    }
}
