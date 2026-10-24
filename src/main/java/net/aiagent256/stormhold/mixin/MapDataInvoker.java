package net.aiagent256.stormhold.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Named marks on a map, as the game puts a banner's name on one: the great map's castles and their names. */
@Mixin(MapItemSavedData.class)
public interface MapDataInvoker {
    @Invoker("addDecoration")
    void stormhold$addDecoration(MapDecoration.Type type, @Nullable LevelAccessor level, String id, double x, double z,
                                 double rotation, @Nullable Component name);

    @Invoker("removeDecoration")
    void stormhold$removeDecoration(String id);
}
