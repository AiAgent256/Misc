package net.aiagent256.stormhold.client;

import net.aiagent256.stormhold.Stormhold;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client events on the game bus. */
@Mod.EventBusSubscriber(modid = Stormhold.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientGameEvents {
    /** What the server answers while the War Table is open shows on the table, not hidden in the chat behind it. */
    @SubscribeEvent
    public static void chat(ClientChatReceivedEvent e) {
        if (Minecraft.getInstance().screen instanceof WarTableScreen s) s.notice(e.getMessage());
    }

    private ClientGameEvents() {
    }
}
