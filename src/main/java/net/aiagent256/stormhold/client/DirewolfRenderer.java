package net.aiagent256.stormhold.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.entity.DirewolfEntity;
import net.minecraft.client.model.WolfModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Wolf;

/**
 * The direwolf: the wolf's own model, grown by its level, in the white coat of a tamed wolf, with red eyes that show in
 * the dark and its barding drawn over it.
 */
public class DirewolfRenderer extends MobRenderer<Wolf, WolfModel<Wolf>> {
    private static final ResourceLocation COAT = new ResourceLocation("minecraft", "textures/entity/wolf/wolf_tame.png");
    private static final ResourceLocation[] BARDING = {null,
            new ResourceLocation(Stormhold.MODID, "textures/entity/direwolf/barding_iron.png"),
            new ResourceLocation(Stormhold.MODID, "textures/entity/direwolf/barding_diamond.png"),
            new ResourceLocation(Stormhold.MODID, "textures/entity/direwolf/barding_netherite.png")};
    private static final RenderType EYES = RenderType.eyes(new ResourceLocation(Stormhold.MODID, "textures/entity/direwolf/eyes.png"));

    public DirewolfRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new WolfModel<>(ctx.bakeLayer(ModelLayers.WOLF)), 0.5F);
        this.addLayer(new Barding(this));
        this.addLayer(new EyesLayer<>(this) {
            @Override
            public RenderType renderType() {
                return EYES;
            }
        });
    }

    @Override
    protected float getBob(Wolf wolf, float partial) {
        return wolf.getTailAngle();
    }

    @Override
    protected void scale(Wolf wolf, PoseStack pose, float partial) {
        float s = wolf instanceof DirewolfEntity d ? DirewolfEntity.scale(d.wolfLevel()) : 1.0F;
        pose.scale(s, s, s);
    }

    @Override
    public void render(Wolf wolf, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        this.shadowRadius = 0.5F * (wolf instanceof DirewolfEntity d ? DirewolfEntity.scale(d.wolfLevel()) : 1.0F);
        if (wolf.isWet()) {
            float f = wolf.getWetShade(partial);
            this.model.setColor(f, f, f);
        }
        super.render(wolf, yaw, partial, pose, buffers, light);
        if (wolf.isWet()) this.model.setColor(1.0F, 1.0F, 1.0F);
    }

    @Override
    public ResourceLocation getTextureLocation(Wolf wolf) {
        return COAT;
    }

    /** The barding: the same model a shade larger about its middle, in plate. */
    private static final class Barding extends RenderLayer<Wolf, WolfModel<Wolf>> {
        Barding(RenderLayerParent<Wolf, WolfModel<Wolf>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, Wolf wolf, float limbSwing, float limbSwingAmount,
                           float partial, float ageInTicks, float netHeadYaw, float headPitch) {
            if (!(wolf instanceof DirewolfEntity d) || d.barding() <= 0 || d.barding() > 3 || wolf.isInvisible()) return;
            pose.pushPose();
            pose.translate(0.0F, 0.875F, 0.0F);
            pose.scale(1.06F, 1.06F, 1.06F);
            pose.translate(0.0F, -0.875F, 0.0F);
            VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(BARDING[d.barding()]));
            this.getParentModel().renderToBuffer(pose, vc, light, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
            pose.popPose();
        }
    }
}
