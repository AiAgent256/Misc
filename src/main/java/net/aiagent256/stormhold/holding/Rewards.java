package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What winning pays, and what a lord's places pay him. Raids broken, warbands broken and castles taken
 * pay a purse; a house's soldiers carry coin; and every morning each place you hold sends its rents
 * and dues to the treasury chest at your seat.
 */
public final class Rewards {
    public static final ResourceLocation EMERALD = new ResourceLocation("minecraft", "emerald");

    /**
     * Emeralds to the lord: into the treasury chest at his seat (a chest signed "Treasury", set up beside its War Table
     * the first time), or at the place they were won if the seat's not loaded; what won't fit, or with neither loaded,
     * into the stores. Never into his pack. Where they went, for the message ("the treasury at Stormhold").
     */
    public static String pay(ServerLevel level, HoldingManager mgr, @Nullable UUID player, int emeralds, @Nullable Holding fallback) {
        Holding seat = player == null ? null : mgr.seatOf(player);
        if (seat == null && player != null) {
            List<Holding> mine = mgr.ownedBy(player);
            if (!mine.isEmpty()) seat = mine.get(0);
        }
        if (emeralds <= 0) return "the treasury";
        for (Holding h : seat == fallback || fallback == null ? new Holding[]{seat} : new Holding[]{seat, fallback}) {
            if (h == null || h.warTablePos.equals(BlockPos.ZERO) || !level.isLoaded(h.warTablePos)) continue;
            BlockPos at = Steward.treasuryChest(level, h);
            if (at == null) at = coffer(level, h);
            net.minecraft.world.Container chest = at == null ? null : Steward.container(level, at);
            if (chest == null) continue;
            int left = emeralds;
            while (left > 0) {
                int n = Math.min(64, left);
                ItemStack rest = Spoils.put(chest, new ItemStack(Items.EMERALD, n));
                left -= n - rest.getCount();
                if (!rest.isEmpty()) break;
            }
            chest.setChanged();
            if (left > 0) {
                h.addStock(EMERALD, left);
                mgr.setDirty();
                return "the treasury at " + h.name + " (" + left + " that wouldn't fit to its stores)";
            }
            return "the treasury at " + h.name;
        }
        Holding into = seat != null ? seat : fallback;
        if (into != null) {
            into.addStock(EMERALD, emeralds);
            mgr.setDirty();
            return "the stores of " + into.name;
        }
        return "nowhere";
    }

    /** A chest beside the War Table with a "Treasury" sign on it, the first time there's coin to put in it. */
    @Nullable
    static BlockPos coffer(ServerLevel level, Holding h) {
        BlockPos table = h.warTablePos;
        if (table.equals(BlockPos.ZERO) || !level.isLoaded(table)) return null;
        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos p = table.offset(dx, 0, dz);
                    if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir() || !level.getBlockState(p.above(2)).isAir()) continue;
                    if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP) || !h.box.isInside(p)) continue;
                    level.setBlock(p, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
                    level.setBlock(p.above(), net.minecraft.world.level.block.Blocks.OAK_SIGN.defaultBlockState(), 3);
                    if (level.getBlockEntity(p.above()) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                        sign.setText(new net.minecraft.world.level.block.entity.SignText().setMessage(0, Component.literal("Treasury")), true);
                        sign.setChanged();
                    }
                    Steward.TREASURY_AT.put(h.id, p);
                    HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("Your steward has set up a treasury chest beside the War Table at "
                            + h.name + " (" + p.getX() + ", " + p.getY() + ", " + p.getZ() + "). Rents, tolls, tribute, spoils and purses go in it "
                            + "from now on. Sign any chest of your seat \"Treasury\" to use that one instead.").withStyle(ChatFormatting.GOLD));
                    return p;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ rents

    /** A day's rents and dues from one place: by size, renown, people and how far out it stands. */
    public static int rent(ServerLevel level, Holding h) {
        int base = h.isCity() ? 18 : h.isTown() ? 8 : h.isNether() ? 8 : switch (h.tier) {
            case 1 -> 3;
            case 2 -> 6;
            default -> 10;
        };
        int n = base + Math.max(1, h.renown) + Math.min(8, h.fiefPop / 3) + 2 * (Frontier.zone(level, h.center()) - 1);
        if (h.mode == Holding.Mode.PRODUCE) n += n / 4;
        if (Vassals.is(h)) n = (int) Math.round(n * Vassals.share(h));
        if (h.ruined) n = Math.max(1, (int) Math.round(n * Ruins.RENT));
        if (Council.has(h.ownerPlayer(), Council.Seat.TREASURER)) n = (int) Math.round(n * 1.2);
        return n;
    }

    /** At dawn: every place the lord holds that isn't under attack pays its rent to him. The account's line, or null. */
    @Nullable
    public static Component rents(ServerLevel level, HoldingManager mgr, UUID player, List<Holding> mine) {
        List<String> parts = new ArrayList<>();
        int total = 0;
        List<String> besieged = new ArrayList<>();
        for (Holding h : mine) {
            if (Campaigns.active(mgr, h.id, Campaign.Kind.RAID) || Warbands.active(mgr, h.id)) {
                besieged.add(h.name);
                continue;
            }
            int r = rent(level, h);
            total += r;
            parts.add(h.name + " " + r + (Vassals.is(h) ? " (vassal)" : ""));
        }
        if (total <= 0 && besieged.isEmpty()) return null;
        String where = "to " + pay(level, mgr, player, total, mine.get(0));
        return Component.literal("Rents and dues: " + String.join(", ", parts) + (parts.isEmpty() ? "nothing" : "") + ". " + total
                + " emeralds " + where + "." + (besieged.isEmpty() ? "" : " " + String.join(", ", besieged) + " paid nothing: under attack."))
                .withStyle(ChatFormatting.GREEN);
    }

    // ------------------------------------------------------------ winning

    /** What a castle's treasury holds when you take it. */
    public static int treasury(ServerLevel level, Holding h) {
        int base = h.isCity() ? 120 : h.isTown() ? 25 : h.isNether() ? 45 : switch (h.tier) {
            case 1 -> 15;
            case 2 -> 30;
            default -> 60;
        };
        double zone = 1 + 0.25 * (Frontier.zone(level, h.center()) - 1);
        int n = (int) Math.round((base + 5 * Math.max(1, h.renown)) * zone);
        return h.ruined ? n / 2 : n;   // the brigands' hoard, not a lord's treasury
    }

    /** The castle is yours: its treasury with it. */
    public static void seized(ServerLevel level, HoldingManager mgr, Holding h, UUID player, boolean peaceful) {
        int n = peaceful ? treasury(level, h) / 2 : treasury(level, h);
        String where = pay(level, mgr, player, n, h);
        HoldingEvents.tell(level, player, Component.literal("The treasury of " + h.name + " is yours: " + n + " emeralds, to " + where + ".")
                .withStyle(ChatFormatting.GOLD));
    }

    /** A raid broken at your walls: the spoils of the field, three emeralds a man they brought. */
    public static int raidSpoils(Campaign c, boolean watched) {
        int men = c.spoils > 0 ? c.spoils : 10;
        return watched ? 3 * men : (3 * men) / 2;
    }

    /** The coin a soldier of a house carries, for whoever cuts him down: more for officers and far out. */
    public static int purse(RandomSource r, Role role, String side, int zone) {
        if (Sides.OUTLAWS.equals(side)) return r.nextInt(2);
        int z = Math.max(0, zone - 1);
        return switch (role) {
            case LORD -> 12 + r.nextInt(9) + 2 * z;
            case CAPTAIN -> 3 + r.nextInt(4) + z;
            case KNIGHT, ARCHER, CROSSBOWMAN -> r.nextInt(3) + z / 2;
            default -> 0;
        };
    }

    private Rewards() {
    }
}
