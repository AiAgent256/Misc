package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Stormhold;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A siege tower, raised where you stand: a timber tower on wheels with ladders up the inside, a
 * drawbridge at wall height and a War Table on the ground floor, so the whole command screen is at
 * the foot of the wall you're about to take. Costs planks and iron from your pack, then the realm.
 */
public final class Siege {
    public static final int PLANKS = 48, IRON = 8;

    public static boolean raise(ServerLevel level, HoldingManager mgr, ServerPlayer player) {
        FiefPiece piece = DataRegistries.fiefPiece("siege_tower");
        if (piece == null) return false;
        var tmpl = level.getStructureManager().get(new ResourceLocation(Stormhold.MODID, piece.template));
        if (tmpl.isEmpty()) return false;
        StructureTemplate template = tmpl.get();
        if (!player.isCreative()) {
            int planks = Stores.packPlusRealm(mgr, null, player, Items.SPRUCE_PLANKS) + Stores.packPlusRealm(mgr, null, player, Items.OAK_PLANKS)
                    + Stores.packPlusRealm(mgr, null, player, Items.DARK_OAK_PLANKS);
            int iron = Stores.packPlusRealm(mgr, null, player, Items.IRON_INGOT);
            if (planks < PLANKS || iron < IRON) {
                player.sendSystemMessage(Component.literal("A siege tower takes " + PLANKS + " planks and " + IRON + " iron (pack or realm stores); you have "
                        + planks + " and " + iron + ".").withStyle(ChatFormatting.RED));
                return false;
            }
        }
        Direction look = player.getDirection();
        Rotation rot = switch (look) {
            case NORTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.CLOCKWISE_90;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        StructurePlaceSettings settings = Fief.settings(rot, null);
        BoundingBox bb0 = template.getBoundingBox(settings, BlockPos.ZERO);
        int w = bb0.getXSpan(), d = bb0.getZSpan();
        BlockPos c = player.blockPosition().relative(look, 6);
        int x0 = c.getX() - w / 2, z0 = c.getZ() - d / 2;
        int[] hs = new int[5];
        int k = 0, water = 0;
        for (int[] o : new int[][]{{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}, {w / 2, d / 2}}) {
            int x = x0 + o[0], z = z0 + o[1];
            if (!level.isLoaded(new BlockPos(x, 64, z))) return false;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (!level.getBlockState(new BlockPos(x, y - 1, z)).getFluidState().isEmpty()) water++;
            hs[k++] = y;
        }
        java.util.Arrays.sort(hs);
        if (water > 0 || hs[4] - hs[0] > 4) {
            player.sendSystemMessage(Component.literal("The ground ahead is too rough or wet for a siege tower; find flatter land.").withStyle(ChatFormatting.RED));
            return false;
        }
        int y = hs[2];
        if (!player.isCreative()) {
            int owed = PLANKS;
            owed = Stores.takeFromPackThenRealm(mgr, null, player, Items.SPRUCE_PLANKS, owed);
            if (owed > 0) owed = Stores.takeFromPackThenRealm(mgr, null, player, Items.OAK_PLANKS, owed);
            if (owed > 0) Stores.takeFromPackThenRealm(mgr, null, player, Items.DARK_OAK_PLANKS, owed);
            Stores.takeFromPackThenRealm(mgr, null, player, Items.IRON_INGOT, IRON);
        }
        Fief.flatten(level, x0, z0, w, d, y, piece.sizeY, false);
        BlockPos origin = new BlockPos(x0 - bb0.minX(), y - piece.ground, z0 - bb0.minZ());
        template.placeInWorld(level, origin, origin, settings, level.random, 2);
        level.playSound(null, c, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.5F, 0.7F);
        level.playSound(null, c, SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 0.8F, 1.2F);
        player.sendSystemMessage(HoldingEvents.gold("Your men raise a siege tower. Its War Table commands the realm; the drawbridge is at the top."));
        return true;
    }

    private Siege() {
    }
}
