package net.aiagent256.stormhold.client;

import net.aiagent256.stormhold.net.Net;
import net.minecraft.client.Minecraft;

public final class ClientHooks {
    public static void showRealm(net.aiagent256.stormhold.net.RealmData data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof RealmScreen s) s.update(data);
        else mc.setScreen(new RealmScreen(data));
    }

    public static void showWarTable(Net.WarTableData data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof WarTableScreen s) {
            s.update(data);
        } else {
            mc.setScreen(new WarTableScreen(data));
        }
    }

    private ClientHooks() {
    }
}
