package net.aiagent256.stormhold.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/** Humans in armour, wearing the default player faces. */
public class GarrisonRenderer extends HumanoidMobRenderer<GarrisonEntity, PlayerModel<GarrisonEntity>> {
    private static final String[] SKINS = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};
    private static final ResourceLocation[] TEXTURES = new ResourceLocation[SKINS.length];

    static {
        for (int i = 0; i < SKINS.length; i++) {
            TEXTURES[i] = new ResourceLocation("minecraft", "textures/entity/player/wide/" + SKINS[i] + ".png");
        }
    }

    public GarrisonRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
    }

    @Override
    public ResourceLocation getTextureLocation(GarrisonEntity e) {
        return TEXTURES[Math.floorMod(e.getUUID().hashCode(), TEXTURES.length)];
    }

    @Override
    public void render(GarrisonEntity e, float yaw, float partial, PoseStack pose, MultiBufferSource buf, int light) {
        PlayerModel<GarrisonEntity> m = this.getModel();
        ItemStack main = e.getMainHandItem();
        HumanoidModel.ArmPose mainPose;
        if (main.isEmpty()) mainPose = HumanoidModel.ArmPose.EMPTY;
        else if (main.getItem() instanceof BowItem && e.isAggressive()) mainPose = HumanoidModel.ArmPose.BOW_AND_ARROW;
        else if (main.getItem() instanceof CrossbowItem && e.isAggressive()) mainPose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
        else mainPose = HumanoidModel.ArmPose.ITEM;
        HumanoidModel.ArmPose offPose = e.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        if (mainPose.isTwoHanded()) offPose = HumanoidModel.ArmPose.EMPTY;
        if (e.getMainArm() == HumanoidArm.RIGHT) {
            m.rightArmPose = mainPose;
            m.leftArmPose = offPose;
        } else {
            m.leftArmPose = mainPose;
            m.rightArmPose = offPose;
        }
        super.render(e, yaw, partial, pose, buf, light);
    }
}
