package net.aiagent256.stormhold.holding;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * A castle's treasury chests carry the kit of the land it stands in: the farther out, the better the
 * metal. Rolled the first time each chest is opened, on top of its own loot (see {@link Treasure#zoneKit}).
 */
public class FrontierLoot extends LootModifier {
    public static final Supplier<Codec<FrontierLoot>> CODEC = Suppliers.memoize(() ->
            RecordCodecBuilder.create(inst -> codecStart(inst).apply(inst, FrontierLoot::new)));

    public FrontierLoot(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected @NotNull ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext ctx) {
        if (!Treasure.isTreasury(ctx.getQueriedLootTableId())) return loot;
        Vec3 o = ctx.getParamOrNull(LootContextParams.ORIGIN);
        if (o == null) return loot;
        loot.addAll(Treasure.zoneKit(ctx.getLevel(), BlockPos.containing(o), ctx.getRandom()));
        return loot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
