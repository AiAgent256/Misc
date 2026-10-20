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

    /** K (or whatever it's bound to): the Realm map, with your castles on it, from anywhere. */
    @SubscribeEvent
    public static void tick(net.minecraftforge.event.TickEvent.ClientTickEvent e) {
        if (e.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        while (Keys.REALM.consumeClick()) {
            if (mc.player != null && mc.screen == null)
                net.aiagent256.stormhold.net.Net.sendAction(new java.util.UUID(0, 0), net.aiagent256.stormhold.net.Net.OPEN_REALM,
                        net.minecraft.core.BlockPos.ZERO, 0);
        }
    }

    private ClientGameEvents() {
    }
}
