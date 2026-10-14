package net.aiagent256.stormhold.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/** Soldier sworn to a player. */
public class RetainerEntity extends GarrisonEntity {
    public RetainerEntity(EntityType<? extends GarrisonEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public boolean isHouse() {
        return false;
    }
}
