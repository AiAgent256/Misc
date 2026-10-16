package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.house.House;
import net.aiagent256.stormhold.house.NameGen;
import net.aiagent256.stormhold.item.WarBannerItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

/**
 * Turning your own build into a holding: mark two opposite corners with a War Banner (sneak-use on
 * any plain block), then place a War Table inside. Walls are scanned for guard posts the same way
 * castles from other mods are.
 */
public final class Founding {
    public static final int MIN_SIDE = 12, MAX_SIDE = 192;

    /** Called when a War Table is placed. Returns the new holding, or null if this wasn't a founding. */
    @Nullable
    public static Holding tryFound(ServerLevel level, Player player, BlockPos table) {
        ItemStack banner = null;
        for (ItemStack s : player.getInventory().items) {
            if (s.getItem() instanceof WarBannerItem && WarBannerItem.corner(s, 1) != null && WarBannerItem.corner(s, 2) != null) {
                banner = s;
                break;
            }
        }
        if (banner == null) return null;
        BlockPos a = WarBannerItem.corner(banner, 1), b = WarBannerItem.corner(banner, 2);
        HoldingManager mgr = HoldingManager.get(level);
        if (mgr.at(table, 0) != null) return null; // already inside a castle
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        int minY = Math.min(Math.min(a.getY(), b.getY()), table.getY()) - 2;
        int maxY = Math.max(Math.max(a.getY(), b.getY()), table.getY()) + 24;
        int sx = maxX - minX + 1, sz = maxZ - minZ + 1;
        if (table.getX() < minX || table.getX() > maxX || table.getZ() < minZ || table.getZ() > maxZ) {
            player.sendSystemMessage(Component.literal("The War Table must stand inside the corners you marked ("
                    + a.toShortString() + " to " + b.toShortString() + ").").withStyle(ChatFormatting.RED));
            return null;
        }
        if (sx < MIN_SIDE || sz < MIN_SIDE) {
            player.sendSystemMessage(Component.literal("Too small for a holding: mark corners at least " + MIN_SIDE + " blocks apart.")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        if (sx > MAX_SIDE || sz > MAX_SIDE) {
            player.sendSystemMessage(Component.literal("Too large for one holding: keep each side under " + MAX_SIDE + " blocks.")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        BoundingBox box = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        for (Holding other : mgr.holdings.values()) {
            if (other.box.intersects(box)) {
                player.sendSystemMessage(Component.literal("That ground overlaps " + other.name + ".").withStyle(ChatFormatting.RED));
                return null;
            }
        }
        Holding h = new Holding();
        h.key = "founded@" + table.asLong();
        h.structure = Stormhold.id("founded");
        h.box = box;
        h.tier = Math.max(sx, sz) >= 100 ? 3 : Math.max(sx, sz) >= 48 ? 2 : 1;
        House region = mgr.houseForRegion(level, table);
        h.houseId = region.id;
        h.owner = Sides.player(player.getUUID());
        RandomSource rnd = RandomSource.create(table.asLong() ^ level.getSeed());
        h.name = NameGen.place(rnd, h.tier);
        h.lordName = player.getGameProfile().getName();
        h.lordAlive = false;
        h.scanned = false;
        h.villagersSpawned = true;
        h.surroundingsDone = true;
        h.mode = Holding.Mode.DEFEND;
        long now = level.getGameTime();
        h.nextRaid = now + HoldingEvents.raidDelay(level) * 2;
        h.nextProduce = now + 24000;
        h.nextReplenish = now + 1200;
        if (!PostScanner.allLoaded(level, h)) {
            player.sendSystemMessage(Component.literal("Part of that ground isn't loaded yet. Stand nearer the middle and place the table again.")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        PostScanner.scan(level, h);
        h.warTablePos = table;
        h.rallyPos = table;
        if (h.lordPos.equals(BlockPos.ZERO)) h.lordPos = table;
        mgr.playerColors.put(player.getUUID(), WarBannerItem.color(banner));
        mgr.add(h);
        banner.getOrCreateTag().remove("Corner1");
        banner.getOrCreateTag().remove("Corner2");
        h.bannersDirty = true;
        Garrisons.recolorBanners(level, mgr, h);
        level.playSound(null, table, SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 6.0F, 1.1F);
        String tier = h.kindName().toLowerCase();
        int posts = h.posts.values().stream().mapToInt(java.util.List::size).sum();
        player.sendSystemMessage(Component.literal("You found " + h.name + ", a " + tier + " of " + sx + " by " + sz
                + " blocks. " + posts + " guard posts found. Put bread and iron on the War Table to train soldiers; "
                + "a banner stand will rise at the highest point.").withStyle(ChatFormatting.GOLD));
        Stormhold.LOGGER.info("Stormhold: {} founded {} ({}x{}, tier {}) at {}", player.getGameProfile().getName(), h.name, sx, sz, h.tier, table);
        return h;
    }

    public static boolean isFounded(Holding h) {
        return h.structure.equals(Stormhold.id("founded"));
    }

    @SuppressWarnings("unused")
    private static final ResourceLocation KEEP = Stormhold.id("founded");

    private Founding() {
    }
}
