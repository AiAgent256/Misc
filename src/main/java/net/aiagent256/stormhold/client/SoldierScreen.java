package net.aiagent256.stormhold.client;

import net.aiagent256.stormhold.menu.SoldierMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/** One row of kit over the player's inventory, drawn on the vanilla chest background. */
public class SoldierScreen extends AbstractContainerScreen<SoldierMenu> {
    private static final ResourceLocation BG = new ResourceLocation("textures/gui/container/generic_54.png");

    public SoldierScreen(SoldierMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageHeight = 114 + 18;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mx, int my) {
        int x = (this.width - this.imageWidth) / 2, y = (this.height - this.imageHeight) / 2;
        g.blit(BG, x, y, 0, 0, this.imageWidth, 18 + 17);
        g.blit(BG, x, y + 18 + 17, 0, 126, this.imageWidth, 96);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        super.renderLabels(g, mx, my);
        var man = this.menu.man();
        if (man != null) {
            String hp = "❤ " + (int) Math.ceil(man.getHealth()) + "/" + (int) man.getMaxHealth();
            g.drawString(this.font, hp, this.imageWidth - 8 - this.font.width(hp), this.titleLabelY, 0xB02020, false);
            g.drawString(this.font, "pouch", 8 + 6 * 18 + 1, this.inventoryLabelY, 0x404040, false);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        this.renderBackground(g);
        super.render(g, mx, my, partial);
        this.renderTooltip(g, mx, my);
    }
}
