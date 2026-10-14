package net.aiagent256.stormhold.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;

/** Soldier of a non-player house. Marked as Enemy so golems, Recruits and other mods fight them. */
public class HouseSoldierEntity extends GarrisonEntity implements Enemy {
    public HouseSoldierEntity(EntityType<? extends GarrisonEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public boolean isHouse() {
        return true;
    }
}
