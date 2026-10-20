package net.aiagent256.stormhold.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether a chest still holds an unrolled loot table: a castle's stores leave those sealed. */
@Mixin(RandomizableContainerBlockEntity.class)
public interface LootTableAccessor {
    @Accessor("lootTable")
    ResourceLocation stormhold$lootTable();
}
