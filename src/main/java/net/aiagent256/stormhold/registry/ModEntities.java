package net.aiagent256.stormhold.registry;

import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.HouseSoldierEntity;
import net.aiagent256.stormhold.entity.RetainerEntity;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.HashMap;
import java.util.Map;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Stormhold.MODID);
    private static final Map<String, Role> ROLE_BY_NAME = new HashMap<>();

    public static final RegistryObject<EntityType<HouseSoldierEntity>> HOUSE_LORD = house("house_lord", Role.LORD);
    public static final RegistryObject<EntityType<HouseSoldierEntity>> HOUSE_CAPTAIN = house("house_captain", Role.CAPTAIN);
    public static final RegistryObject<EntityType<HouseSoldierEntity>> HOUSE_KNIGHT = house("house_knight", Role.KNIGHT);
    public static final RegistryObject<EntityType<HouseSoldierEntity>> HOUSE_ARCHER = house("house_archer", Role.ARCHER);
    public static final RegistryObject<EntityType<HouseSoldierEntity>> HOUSE_CROSSBOWMAN = house("house_crossbowman", Role.CROSSBOWMAN);
    public static final RegistryObject<EntityType<RetainerEntity>> RETAINER_CAPTAIN = retainer("retainer_captain", Role.CAPTAIN);
    public static final RegistryObject<EntityType<RetainerEntity>> RETAINER_KNIGHT = retainer("retainer_knight", Role.KNIGHT);
    public static final RegistryObject<EntityType<RetainerEntity>> RETAINER_ARCHER = retainer("retainer_archer", Role.ARCHER);
    public static final RegistryObject<EntityType<RetainerEntity>> RETAINER_CROSSBOWMAN = retainer("retainer_crossbowman", Role.CROSSBOWMAN);
    public static final RegistryObject<EntityType<RetainerEntity>> COURIER = retainer("courier", Role.COURIER);

    public static final RegistryObject<EntityType<net.aiagent256.stormhold.entity.DirewolfEntity>> DIREWOLF = ENTITIES.register("direwolf",
            () -> EntityType.Builder.<net.aiagent256.stormhold.entity.DirewolfEntity>of(net.aiagent256.stormhold.entity.DirewolfEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 0.85F).clientTrackingRange(10).build(Stormhold.MODID + ":direwolf"));

    private static RegistryObject<EntityType<HouseSoldierEntity>> house(String name, Role role) {
        ROLE_BY_NAME.put(name, role);
        return ENTITIES.register(name, () -> EntityType.Builder.<HouseSoldierEntity>of(HouseSoldierEntity::new, MobCategory.MISC)
                .sized(0.6F, 1.95F).clientTrackingRange(10).build(Stormhold.MODID + ":" + name));
    }

    private static RegistryObject<EntityType<RetainerEntity>> retainer(String name, Role role) {
        ROLE_BY_NAME.put(name, role);
        return ENTITIES.register(name, () -> EntityType.Builder.<RetainerEntity>of(RetainerEntity::new, MobCategory.MISC)
                .sized(0.6F, 1.95F).clientTrackingRange(10).build(Stormhold.MODID + ":" + name));
    }

    public static Role roleOf(EntityType<?> type) {
        var key = ForgeRegistries.ENTITY_TYPES.getKey(type);
        if (key == null) return Role.KNIGHT;
        return ROLE_BY_NAME.getOrDefault(key.getPath(), Role.KNIGHT);
    }

    public static EntityType<? extends GarrisonEntity> typeFor(Role role, boolean house) {
        if (house) {
            return switch (role) {
                case LORD -> HOUSE_LORD.get();
                case CAPTAIN -> HOUSE_CAPTAIN.get();
                case ARCHER -> HOUSE_ARCHER.get();
                case CROSSBOWMAN -> HOUSE_CROSSBOWMAN.get();
                default -> HOUSE_KNIGHT.get();
            };
        }
        return switch (role) {
            case CAPTAIN, LORD -> RETAINER_CAPTAIN.get();
            case ARCHER -> RETAINER_ARCHER.get();
            case CROSSBOWMAN -> RETAINER_CROSSBOWMAN.get();
            case COURIER -> COURIER.get();
            default -> RETAINER_KNIGHT.get();
        };
    }

    private ModEntities() {
    }
}
