package net.aiagent256.stormhold.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Planted on a fallen castle's banner stand to claim it. Sneak-use to change your colour. */
public class WarBannerItem extends Item {
    public WarBannerItem(Properties props) {
        super(props);
    }

    public static DyeColor color(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("Color") ? DyeColor.byId(stack.getTag().getInt("Color")) : DyeColor.BLUE;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            DyeColor next = DyeColor.byId((color(stack).getId() + 1) % 16);
            stack.getOrCreateTag().putInt("Color", next.getId());
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("Your colours: " + next.getName().replace('_', ' ')), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }

    /** Sneak-use on a chest or any inventory block to mark it as a supply depot for the War Table. */
    @Override
    public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return net.minecraft.world.InteractionResult.PASS;
        var be = ctx.getLevel().getBlockEntity(ctx.getClickedPos());
        boolean inventory = be instanceof net.minecraft.world.Container
                || (be != null && be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null).isPresent());
        net.minecraft.core.BlockPos p = ctx.getClickedPos();
        if (!inventory) {
            // plain block: mark a corner of a holding you're founding
            if (ctx.getLevel().getBlockState(p).is(net.aiagent256.stormhold.registry.ModBlocks.BANNER_STAND.get())) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            var tag = ctx.getItemInHand().getOrCreateTag();
            boolean second = tag.contains("Corner1") && !tag.contains("Corner2");
            tag.put(second ? "Corner2" : "Corner1", net.minecraft.nbt.NbtUtils.writeBlockPos(p));
            if (!second) tag.remove("Corner2");
            if (!ctx.getLevel().isClientSide) {
                if (second) {
                    net.minecraft.core.BlockPos a = corner(ctx.getItemInHand(), 1);
                    int sx = Math.abs(p.getX() - a.getX()) + 1, sz = Math.abs(p.getZ() - a.getZ()) + 1;
                    player.displayClientMessage(Component.literal("Second corner marked: " + sx + " by " + sz
                            + " blocks. Place a War Table inside to found your holding."), true);
                } else {
                    player.displayClientMessage(Component.literal("First corner marked at " + p.toShortString()
                            + ". Sneak-use on the opposite corner."), true);
                }
            }
            return net.minecraft.world.InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
        }
        ctx.getItemInHand().getOrCreateTag().put("Depot", net.minecraft.nbt.NbtUtils.writeBlockPos(p));
        if (!ctx.getLevel().isClientSide) {
            player.displayClientMessage(Component.literal("Supply depot marked at " + p.toShortString()
                    + ". Open a War Table to route a castle's surplus here."), true);
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
    }

    @Nullable
    public static net.minecraft.core.BlockPos corner(ItemStack stack, int n) {
        if (!stack.hasTag() || !stack.getTag().contains("Corner" + n)) return null;
        return net.minecraft.nbt.NbtUtils.readBlockPos(stack.getTag().getCompound("Corner" + n));
    }

    @Nullable
    public static net.minecraft.core.BlockPos depot(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains("Depot")) return null;
        return net.minecraft.nbt.NbtUtils.readBlockPos(stack.getTag().getCompound("Depot"));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        net.minecraft.core.BlockPos d = depot(stack);
        if (d != null) tip.add(Component.literal("Depot: " + d.toShortString()).withStyle(ChatFormatting.GRAY));
        net.minecraft.core.BlockPos c1 = corner(stack, 1), c2 = corner(stack, 2);
        if (c1 != null) tip.add(Component.literal("Corner 1: " + c1.toShortString() + (c2 != null ? "  Corner 2: " + c2.toShortString() : "")).withStyle(ChatFormatting.GRAY));
        tip.add(Component.literal("Sneak + use on a block to mark a corner of your own holding.").withStyle(ChatFormatting.DARK_GRAY));
        tip.add(Component.literal("Colours: " + color(stack).getName().replace('_', ' ')).withStyle(ChatFormatting.GRAY));
        tip.add(Component.literal("Use on a castle's banner stand once its lord is dead.").withStyle(ChatFormatting.DARK_GRAY));
        tip.add(Component.literal("Sneak + use to change colours.").withStyle(ChatFormatting.DARK_GRAY));
    }
}
