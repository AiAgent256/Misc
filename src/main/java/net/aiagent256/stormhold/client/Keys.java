package net.aiagent256.stormhold.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Keys of our own: K opens the Realm map, anywhere. Change it under Options, Controls, Key Binds, Stormhold. */
public final class Keys {
    public static final KeyMapping REALM = new KeyMapping("key.stormhold.realm", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K,
            "key.categories.stormhold");

    private Keys() {
    }
}
