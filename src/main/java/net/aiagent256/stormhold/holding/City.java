package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.compat.RecruitsCompat;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.NameGen;
import net.aiagent256.stormhold.house.Standing;
import net.aiagent256.stormhold.registry.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** What you can do in a Great City: hire soldiers, buy a house, take work from the notice board. */
public final class City {
    public static final int KNIGHT_PRICE = 6, ARCHER_PRICE = 5, CROSSBOW_PRICE = 5;
    public static final int HOUSE_PRICE = 24;
    public static final int MAX_POSTINGS = 3;

    // ------------------------------------------------------------ setup

    /** Put the muster post and the notice board down once the city's chunks are loaded. */
    public static void placeBlocks(ServerLevel level, Holding h) {
        if (h.cityBlocksPlaced || !(h.isCity() || h.isTown())) return;
        if (h.isTown()) {
            // a town's board is the signboard in the market: give its blank signs their label
            List<BlockPos> nb = h.extras.get("notice_board");
            if (nb != null && !nb.isEmpty()) {
                if (!level.isLoaded(nb.get(0))) return;
                Shops.labelBoard(level, nb.get(0));
            }
            h.cityBlocksPlaced = true;
            return;
        }
        List<BlockPos> b = h.extras.get("barracks"), n = h.extras.get("notice_board");
        if (b == null || n == null || b.isEmpty() || n.isEmpty()) {
            h.cityBlocksPlaced = true;
            return;
        }
        if (!level.isLoaded(b.get(0)) || !level.isLoaded(n.get(0))) return;
        place(level, b.get(0), ModBlocks.MUSTER_POST.get().defaultBlockState());
        place(level, n.get(0), ModBlocks.NOTICE_BOARD.get().defaultBlockState());
        h.cityBlocksPlaced = true;
    }

    private static void place(ServerLevel level, BlockPos p, BlockState s) {
        BlockState here = level.getBlockState(p);
        if (here.isAir() || here.canBeReplaced()) level.setBlock(p, s, 3);
    }

    @Nullable
    public static Holding cityAt(HoldingManager mgr, BlockPos p) {
        Holding h = mgr.at(p, 2);
        return h != null && h.isCity() ? h : null;
    }

    /** A city or a market town: somewhere with shops, a notice board and homes for sale. */
    @Nullable
    public static Holding marketAt(HoldingManager mgr, BlockPos p) {
        Holding h = mgr.at(p, 2);
        return h != null && (h.isCity() || h.isTown()) ? h : null;
    }

    /** What a home costs: less in a market town than in the capital. */
    public static int housePrice(Holding h) {
        return h.isTown() ? 18 : HOUSE_PRICE;
    }

    /** Can this player deal with the city: it's theirs, or its house likes them. */
    public static boolean welcome(HoldingManager mgr, Holding h, Player p) {
        if (h.owner.equals(Sides.player(p.getUUID()))) return true;
        return h.isHouseOwned() && Standing.friendly(mgr, h.houseId, p.getUUID());
    }

    /** Let in and sold to: anyone the house doesn't hold hostile. Hiring its soldiers still wants friendship. */
    public static boolean tolerated(HoldingManager mgr, Holding h, Player p) {
        if (h.owner.equals(Sides.player(p.getUUID()))) return true;
        return h.isHouseOwned() && !Standing.hostile(mgr, h.houseId, p.getUUID());
    }

    private static int take(Player p, int emeralds) {
        int have = p.getInventory().countItem(Items.EMERALD);
        if (have < emeralds) return have;
        int left = emeralds;
        for (ItemStack s : p.getInventory().items) {
            if (!s.is(Items.EMERALD)) continue;
            int n = Math.min(left, s.getCount());
            s.shrink(n);
            left -= n;
            if (left == 0) break;
        }
        return -1;
    }

    // ------------------------------------------------------------ hiring

    public static void hire(ServerLevel level, ServerPlayer p, BlockPos post) {
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = cityAt(mgr, post);
        if (h == null) return;
        if (!welcome(mgr, h, p)) {
            p.displayClientMessage(Component.literal("The captain of the watch won't take your coin. " + mgr.house(h.houseId).title()
                    + " counts you a " + Standing.word(Standing.get(mgr, h.houseId, p.getUUID())) + ".").withStyle(ChatFormatting.RED), true);
            return;
        }
        ItemStack held = p.getMainHandItem();
        if (!held.is(Items.EMERALD)) {
            p.displayClientMessage(Component.literal("Muster post: hold emeralds. " + KNIGHT_PRICE + " hires a knight, "
                    + ARCHER_PRICE + " an archer (sneak), " + CROSSBOW_PRICE + " a crossbowman (sneak, off-hand empty).")
                    .withStyle(ChatFormatting.GOLD), true);
            return;
        }
        Role r = !p.isShiftKeyDown() ? Role.KNIGHT : p.getOffhandItem().isEmpty() ? Role.CROSSBOWMAN : Role.ARCHER;
        int price = r == Role.KNIGHT ? KNIGHT_PRICE : r == Role.ARCHER ? ARCHER_PRICE : CROSSBOW_PRICE;
        int short_ = take(p, price);
        if (short_ >= 0) {
            p.displayClientMessage(Component.literal("A " + r.name().toLowerCase() + " costs " + price + " emeralds; you have " + short_ + ".")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        boolean viaRecruits = Company.useRecruits() && RecruitsCompat.muster(level, p, r);
        if (!viaRecruits) {
            BlockPos at = Garrisons.standable(level, p.blockPosition().east(2));
            GarrisonEntity e = Garrisons.spawn(level, mgr, null, r, at != null ? at : p.blockPosition(), Sides.player(p.getUUID()), false);
            if (e != null) Company.enlist(level, mgr, e, p, null);
        }
        level.playSound(null, post, SoundEvents.VILLAGER_YES, SoundSource.PLAYERS, 1.0F, 1.0F);
        p.displayClientMessage(Component.literal("A " + r.name().toLowerCase() + " of " + h.name + " takes your coin"
                + (viaRecruits ? " and joins your company." : " and stands with you.")).withStyle(ChatFormatting.GREEN), true);
    }

    // ------------------------------------------------------------ houses

    /** Right-clicking a door that's for sale. Returns true if this door is one of the city's plots. */
    public static boolean useDoor(ServerLevel level, ServerPlayer p, BlockPos door) {
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = marketAt(mgr, door);
        if (h == null) return false;
        List<BlockPos> plots = h.extras.get("house");
        int price = housePrice(h);
        if (plots == null) return false;
        BlockPos plot = null;
        for (BlockPos q : plots) if (q.distManhattan(door) <= 2) plot = q;
        if (plot == null) return false;
        UUID owner = h.ownedHouses.get(plot);
        if (owner != null) {
            if (!owner.equals(p.getUUID())) {
                p.displayClientMessage(Component.literal("This house belongs to someone else.").withStyle(ChatFormatting.GRAY), true);
                return true;
            }
            return false; // your own door: open it
        }
        if (!tolerated(mgr, h, p)) {
            p.displayClientMessage(Component.literal("Nobody in " + h.name + " will sell to you.").withStyle(ChatFormatting.RED), true);
            return true;
        }
        if (!p.getMainHandItem().is(Items.EMERALD)) {
            p.displayClientMessage(Component.literal("For sale: a house in " + h.name + ", " + price
                    + " emeralds. Use the door holding emeralds to buy it.").withStyle(ChatFormatting.GOLD), true);
            return true;
        }
        int short_ = take(p, price);
        if (short_ >= 0) {
            p.displayClientMessage(Component.literal("The house costs " + price + " emeralds; you have " + short_ + ".")
                    .withStyle(ChatFormatting.RED), true);
            return true;
        }
        h.ownedHouses.put(plot, p.getUUID());
        mgr.setDirty();
        level.playSound(null, door, SoundEvents.VILLAGER_CELEBRATE, SoundSource.PLAYERS, 1.0F, 1.0F);
        p.sendSystemMessage(Component.literal("The house is yours. Its chests are yours to use, you may build inside it, and a bed here is your bed in " + h.name + ".")
                .withStyle(ChatFormatting.GREEN));
        return true;
    }

    /** Inside a house this player bought: siege rules and sealed stores don't apply. */
    public static boolean inOwnHouse(HoldingManager mgr, Holding h, Player p, BlockPos pos) {
        UUID o = h.houseOwnerAt(pos);
        return o != null && o.equals(p.getUUID());
    }

    // ------------------------------------------------------------ the notice board

    public static void tickPostings(ServerLevel level, HoldingManager mgr, Holding h, long now) {
        if (!(h.isCity() || h.isTown()) || !h.isHouseOwned()) return;
        boolean dirty = h.postings.removeIf(p -> p.takenBy == null && p.expires > 0 && now > p.expires);
        if (h.postings.size() < (h.isTown() ? 2 : MAX_POSTINGS) && now >= h.nextPosting) {
            h.nextPosting = now + 12000;
            h.postings.add(makePosting(level, h));
            dirty = true;
        }
        if (dirty) mgr.setDirty();
    }

    private static Posting makePosting(ServerLevel level, Holding h) {
        Posting p = new Posting();
        boolean big = level.random.nextInt(3) == 0;
        p.kind = big ? Posting.Kind.WARBAND : Posting.Kind.BANDIT_CAMP;
        double ang = level.random.nextDouble() * Math.PI * 2;
        int dist = 200 + level.random.nextInt(120);
        BlockPos c = h.center();
        p.at = new BlockPos(c.getX() + (int) (Math.cos(ang) * dist), c.getY(), c.getZ() + (int) (Math.sin(ang) * dist));
        String dir = Math.abs(Math.cos(ang)) > Math.abs(Math.sin(ang)) ? (Math.cos(ang) > 0 ? "east" : "west") : (Math.sin(ang) > 0 ? "south" : "north");
        p.title = big ? "Raiders under " + NameGen.first(level.random) + " the Black, camped " + dist + " blocks " + dir
                : "Brigands on the " + dir + " road, " + dist + " blocks out";
        p.reward = big ? 24 : 12;
        p.standing = big ? 30 : 15;
        p.expires = level.getGameTime() + 24000L * 3;
        return p;
    }

    public static void showBoard(ServerLevel level, ServerPlayer p, BlockPos board) {
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = marketAt(mgr, board);
        if (h == null) return;
        BlockPos from = p.blockPosition();
        p.sendSystemMessage(Component.literal("Notice board of " + h.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        // the shops, nearest first, each with the way to its nearest counter
        var posts = Shops.posts(h);
        if (!posts.isEmpty()) {
            p.sendSystemMessage(Component.literal("Shops").withStyle(ChatFormatting.GOLD));
            List<Object[]> rows = new java.util.ArrayList<>();
            for (var e : posts.entrySet()) {
                BlockPos best = null;
                for (BlockPos q : e.getValue()) if (best == null || q.distSqr(from) < best.distSqr(from)) best = q;
                if (best != null) rows.add(new Object[]{Shops.type(e.getKey()).title(), best});
            }
            rows.sort(java.util.Comparator.comparingDouble(r -> ((BlockPos) r[1]).distSqr(from)));
            for (Object[] r : rows) {
                BlockPos q = (BlockPos) r[1];
                p.sendSystemMessage(Component.literal("  " + r[0] + ": ").withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal(Guide.way(from, q) + " (" + q.getX() + ", " + q.getZ() + ")").withStyle(ChatFormatting.WHITE)));
            }
        }
        // homes for sale
        List<BlockPos> plots = h.extras.get("house");
        if (plots != null && !plots.isEmpty()) {
            BlockPos best = null;
            int free = 0;
            for (BlockPos q : plots) {
                if (h.ownedHouses.containsKey(q)) continue;
                free++;
                if (best == null || q.distSqr(from) < best.distSqr(from)) best = q;
            }
            p.sendSystemMessage(Component.literal("Houses for sale: " + free + (best == null ? "" : ", " + housePrice(h)
                    + " emeralds. Nearest door " + Guide.way(from, best) + " (" + best.getX() + ", " + best.getZ() + ")"
                    + ". Use the door holding emeralds.")).withStyle(ChatFormatting.GOLD));
        }
        // work
        p.sendSystemMessage(Component.literal("Work").withStyle(ChatFormatting.GOLD));
        if (h.postings.isEmpty()) {
            p.sendSystemMessage(Component.literal("  Nothing posted today.").withStyle(ChatFormatting.GRAY));
            return;
        }
        int i = 0;
        for (Posting post : h.postings) {
            String state = post.takenBy == null ? "" : post.takenBy.equals(p.getUUID()) ? " (yours)" : " (taken)";
            Component line = Component.literal("  " + (i + 1) + ". " + post.title + ". Reward " + post.reward + " emeralds" + state);
            if (post.takenBy == null) {
                line = line.copy().withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/stormhold accept " + h.id + " " + post.id))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to take this job"))));
            } else {
                line = line.copy().withStyle(ChatFormatting.GRAY);
            }
            p.sendSystemMessage(line);
            i++;
        }
    }

    /** A sign on a town's or a city's notice board, right-clicked: read the board. True if it was one. */
    public static boolean readBoardSign(ServerLevel level, ServerPlayer p, BlockPos sign) {
        Holding h = marketAt(HoldingManager.get(level), sign);
        if (h == null) return false;
        List<BlockPos> nb = h.extras.get("notice_board");
        if (nb == null) return false;
        for (BlockPos m : nb) {
            if (Math.abs(m.getX() - sign.getX()) <= 3 && Math.abs(m.getZ() - sign.getZ()) <= 3 && Math.abs(m.getY() - sign.getY()) <= 3) {
                showBoard(level, p, m);
                return true;
            }
        }
        return false;
    }

    public static boolean accept(ServerLevel level, ServerPlayer p, UUID holdingId, UUID postingId) {
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = mgr.holdings.get(holdingId);
        if (h == null) return false;
        Posting post = null;
        for (Posting x : h.postings) if (x.id.equals(postingId)) post = x;
        if (post == null || post.takenBy != null) return false;
        if (p.blockPosition().distSqr(h.center()) > 400.0 * 400.0) return false;
        post.takenBy = p.getUUID();
        post.expires = 0;
        Campaign c = new Campaign();
        c.kind = Campaign.Kind.CAMP;
        c.side = Sides.OUTLAWS;
        c.origin = h.id;
        c.from = post.at;
        c.to = post.at;
        c.stage = Campaign.Stage.SIEGE;
        c.siegeUntil = Long.MAX_VALUE;
        c.tier = post.kind == Posting.Kind.WARBAND ? 3 : 2;
        c.add(Role.CAPTAIN, post.kind == Posting.Kind.WARBAND ? 2 : 1);
        c.add(Role.KNIGHT, post.kind == Posting.Kind.WARBAND ? 7 : 4);
        c.add(Role.ARCHER, post.kind == Posting.Kind.WARBAND ? 3 : 1);
        c.add(Role.CROSSBOWMAN, post.kind == Posting.Kind.WARBAND ? 2 : 1);
        mgr.campaigns.add(c);
        post.campaign = c.id;
        mgr.setDirty();
        p.sendSystemMessage(Component.literal("You take the job: " + post.title + ". They're at about " + post.at.getX() + ", "
                + post.at.getZ() + ". Come back to the board when they're dead.").withStyle(ChatFormatting.GREEN));
        return true;
    }

    /** Called when a camp campaign has no one left in it. */
    public static void campCleared(ServerLevel level, HoldingManager mgr, Campaign c) {
        Holding h = c.origin != null ? mgr.holdings.get(c.origin) : null;
        if (h == null) return;
        Posting done = null;
        for (Posting p : h.postings) if (c.id.equals(p.campaign)) done = p;
        if (done == null) return;
        h.postings.remove(done);
        mgr.setDirty();
        if (done.takenBy == null) return;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(done.takenBy);
        Standing.change(level, mgr, h.houseId, done.takenBy, done.standing, "you cleared the " + (done.kind == Posting.Kind.WARBAND ? "warband" : "bandit camp"));
        if (p != null) {
            ItemStack pay = new ItemStack(Items.EMERALD, done.reward);
            if (!p.getInventory().add(pay)) p.drop(pay, false);
            p.sendSystemMessage(Component.literal("Job done: " + done.title + ". " + h.name + " pays " + done.reward + " emeralds.")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            // paid on the next visit: keep it simple and put the purse in the city's stores for the War Table
            h.addStock(new net.minecraft.resources.ResourceLocation("minecraft", "emerald"), done.reward);
        }
    }

    private City() {
    }
}
