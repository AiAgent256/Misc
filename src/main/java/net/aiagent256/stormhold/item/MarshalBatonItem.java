package net.aiagent256.stormhold.item;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.holding.Orders;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Command the men around you. Point at something and use it:
 * one of your own men, and he's told apart from the rest (holds where he stands while they follow you,
 * or comes with you while they hold) or back in with them; a creature or enemy soldier, and every man goes for it; a block, and they clear the building around it
 * (sneak: they hold that spot); the open air, and they switch between following and attacking anything
 * hostile near you (sneak: fall in).
 */
public class MarshalBatonItem extends Item {
    public static final double RANGE = 64;

    public MarshalBatonItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        player.getCooldowns().addCooldown(this, 10);
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(RANGE));
        BlockHitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        Vec3 reach = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        AABB sweep = player.getBoundingBox().expandTowards(reach.subtract(eye)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, reach, sweep,
                e -> e instanceof LivingEntity le && le.isAlive() && e != player && !e.isSpectator(),
                eye.distanceToSqr(reach));
        if (hit != null && hit.getEntity() instanceof GarrisonEntity man && mine(man, player)) {
            Orders.toggle(sl, sp, man);                    // one of yours: told apart from the rest, or back with them
        } else if (hit != null && hit.getEntity() instanceof LivingEntity target) {
            Orders.attack(sl, sp, target);
        } else if (block.getType() == HitResult.Type.BLOCK) {
            BlockPos at = block.getBlockPos().relative(block.getDirection());
            if (player.isShiftKeyDown()) Orders.hold(sl, sp, at);
            else Orders.clear(sl, sp, at);
        } else if (player.isShiftKeyDown()) {
            Orders.fallIn(sl, sp);
        } else {
            Orders.toggleStance(sl, sp);
        }
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    private static boolean mine(Entity e, Player p) {
        return e instanceof GarrisonEntity g && p.getUUID().equals(g.owner());
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("item.stormhold.marshal_baton.tip1"));
        tip.add(Component.translatable("item.stormhold.marshal_baton.tip2"));
        tip.add(Component.translatable("item.stormhold.marshal_baton.tip3"));
        tip.add(Component.translatable("item.stormhold.marshal_baton.tip4"));
    }
}
