package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Production, replenishing the garrison, and couriers carrying goods along supply lines. */
public final class Economy {
    private static final ResourceLocation BREAD = new ResourceLocation("minecraft", "bread");
    private static final ResourceLocation IRON = new ResourceLocation("minecraft", "iron_ingot");
    private static final double COURIER_SPEED = 2.5;

    private static final String[][] GOODS = {
            {"bread:8", "iron_ingot:4", "arrow:16", "coal:4", "wheat:8"},
            {"bread:16", "iron_ingot:8", "gold_ingot:3", "arrow:32", "coal:8", "leather:4", "wheat:12"},
            {"bread:32", "iron_ingot:16", "gold_ingot:8", "arrow:64", "emerald:4", "diamond:1", "coal:16",
                    "leather:8", "wheat:16"},
            {"bread:64", "iron_ingot:32", "gold_ingot:16", "arrow:128", "emerald:12", "diamond:3", "coal:32",
                    "leather:16", "wheat:32"},
    };

    public static void tickHolding(ServerLevel level, HoldingManager mgr, Holding h, long now) {
        if (!h.isPlayerOwned()) {
            if (!h.fief.isEmpty() && now >= h.nextProduce) {
                h.nextProduce = now + 24000;
                Fief.census(level, mgr, h, now);
            }
            return;
        }
        if (now >= h.nextProduce) {
            // the castle's own stores: the full ration only where there's no fief to feed it
            double own = h.fief.isEmpty() ? 1.0 : 0.3;
            if (h.mode == Holding.Mode.PRODUCE) {
                for (String g : GOODS[Math.max(1, Math.min(Holding.MAX_TIER, h.tier)) - 1]) {
                    String[] kv = g.split(":");
                    int n = (int) Math.round(Integer.parseInt(kv[1]) * own);
                    if (n > 0) h.addStock(new ResourceLocation("minecraft", kv[0]), n);
                }
            }
            Fief.census(level, mgr, h, now);
            Growth.daily(level, mgr, h, now);
            h.nextProduce = now + 24000;
            if (h.supplyTarget != null) ship(level, mgr, h);
            mgr.setDirty();
        } else if (h.nextProduce < now - 24000) {
            h.nextProduce = now + 24000;
        }
        if (now >= h.nextReplenish) {
            h.nextReplenish = now + (h.mode == Holding.Mode.DEFEND ? 1200 : 2400);
            java.util.UUID owner = h.ownerPlayer();
            for (Role r : new Role[]{Role.KNIGHT, Role.ARCHER, Role.CROSSBOWMAN, Role.CAPTAIN}) {
                if (h.count(r) < h.capacity(r) && Stores.realmStock(mgr, h, owner, BREAD) >= 4 && Stores.realmStock(mgr, h, owner, IRON) >= 2) {
                    Stores.take(mgr, h, owner, BREAD, 4);
                    Stores.take(mgr, h, owner, IRON, 2);
                    h.add(r, 1);
                    mgr.setDirty();
                    break;
                }
            }
        }
    }

    /** Load surplus onto a courier bound for the supply target. */
    public static void ship(ServerLevel level, HoldingManager mgr, Holding h) {
        if (h.supplyTarget == null) return;
        for (Shipment s : mgr.shipments) if (s.origin.equals(h.id)) return;
        List<ItemStack> cargo = new ArrayList<>();
        for (Iterator<Map.Entry<ResourceLocation, Integer>> it = h.stock.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<ResourceLocation, Integer> e = it.next();
            int reserve = e.getKey().equals(BREAD) ? 24 : e.getKey().equals(IRON) ? 12 : 0;
            int send = e.getValue() - reserve;
            if (send <= 0) continue;
            Item item = ForgeRegistries.ITEMS.getValue(e.getKey());
            if (item == null) continue;
            int left = send;
            while (left > 0) {
                int n = Math.min(left, item.getMaxStackSize());
                cargo.add(new ItemStack(item, n));
                left -= n;
            }
            e.setValue(e.getValue() - send);
        }
        h.stock.values().removeIf(v -> v <= 0);
        if (cargo.isEmpty()) return;
        Shipment s = new Shipment();
        s.origin = h.id;
        s.from = h.gatePos.equals(BlockPos.ZERO) ? h.center() : h.gatePos;
        s.to = h.supplyTarget;
        Holding dest = mgr.at(h.supplyTarget, 2);
        if (dest != null && dest != h) s.targetHolding = dest.id;
        s.cargo.addAll(cargo);
        mgr.shipments.add(s);
        mgr.setDirty();
    }

    public static void tickShipments(ServerLevel level, HoldingManager mgr) {
        for (Iterator<Shipment> it = mgr.shipments.iterator(); it.hasNext(); ) {
            Shipment s = it.next();
            Holding origin = mgr.holdings.get(s.origin);
            if (origin == null || !origin.isPlayerOwned()) {
                discardCourier(level, s);
                it.remove();
                continue;
            }
            Entity courier = s.courier != null ? level.getEntity(s.courier) : null;
            if (courier != null && courier.isAlive()) {
                double d = Math.sqrt(courier.distanceToSqr(s.to.getX() + 0.5, courier.getY(), s.to.getZ() + 0.5));
                s.progress = Math.max(s.progress, 1 - d / s.length());
                if (d < 4) {
                    deliver(level, mgr, s, origin);
                    courier.discard();
                    it.remove();
                }
                continue;
            }
            s.courier = null;
            BlockPos at = s.position();
            if (nearPlayer(level, at, 80) && level.isLoaded(at)) {
                spawnCourier(level, mgr, s, origin, at);
                continue;
            }
            s.progress += COURIER_SPEED / s.length();
            if (!s.ambushRolled && s.progress > 0.5) {
                s.ambushRolled = true;
                if (s.length() > 96 && level.random.nextFloat() < 0.1F) {
                    int lost = s.cargo.size() / 2;
                    for (int i = 0; i < lost && !s.cargo.isEmpty(); i++) s.cargo.remove(s.cargo.size() - 1);
                    HoldingEvents.tell(level, origin.ownerPlayer(), Component.literal("Brigands waylaid the courier from "
                            + origin.name + " and took part of the cargo."));
                }
            }
            if (s.progress >= 1) {
                deliver(level, mgr, s, origin);
                it.remove();
            }
            mgr.setDirty();
        }
    }

    private static boolean nearPlayer(ServerLevel level, BlockPos p, int r) {
        for (ServerPlayer sp : level.players()) {
            double dx = sp.getX() - p.getX(), dz = sp.getZ() - p.getZ();
            if (dx * dx + dz * dz < r * r) return true;
        }
        return false;
    }

    private static void spawnCourier(ServerLevel level, HoldingManager mgr, Shipment s, Holding origin, BlockPos at) {
        BlockPos p = new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ());
        GarrisonEntity c = Garrisons.spawn(level, mgr, null, Role.COURIER, p, origin.owner, false);
        if (c == null) return;
        c.setShipmentId(s.id);
        c.setMarchTarget(s.to);
        c.setPost(null);
        s.courier = c.getUUID();
        if (!s.ambushRolled && s.length() > 64 && s.progress < 0.7) {
            s.ambushRolled = true;
            if (level.random.nextFloat() < 0.25F) {
                double dx = s.to.getX() - p.getX(), dz = s.to.getZ() - p.getZ();
                double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
                int ax = p.getX() + (int) (dx / len * 14), az = p.getZ() + (int) (dz / len * 14);
                for (int i = 0; i < 3; i++) {
                    int x = ax + level.random.nextInt(5) - 2, z = az + level.random.nextInt(5) - 2;
                    BlockPos bp = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    if (!level.isLoaded(bp)) continue;
                    GarrisonEntity b = Garrisons.spawn(level, mgr, null, i == 0 ? Role.CROSSBOWMAN : Role.KNIGHT, bp, Sides.OUTLAWS, false);
                    if (b != null) {
                        b.setPost(null);
                        b.setTarget(c);
                    }
                }
                HoldingEvents.tell(level, origin.ownerPlayer(), Component.literal("Brigands are attacking your courier near "
                        + p.getX() + ", " + p.getZ() + "!"));
            }
        }
    }

    private static void discardCourier(ServerLevel level, Shipment s) {
        if (s.courier != null) {
            Entity e = level.getEntity(s.courier);
            if (e != null) e.discard();
        }
    }

    /** Hand over the cargo: another castle's stores, or any block with an inventory (chests, colony huts). */
    public static void deliver(ServerLevel level, HoldingManager mgr, Shipment s, Holding origin) {
        int delivered = 0;
        List<ItemStack> leftover = new ArrayList<>();
        Holding dest = s.targetHolding != null ? mgr.holdings.get(s.targetHolding) : null;
        if (dest != null) {
            for (ItemStack st : s.cargo) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
                if (id != null) dest.addStock(id, st.getCount());
                delivered += st.getCount();
            }
            if (dest.isHouseOwned() && delivered > 0) {
                net.aiagent256.stormhold.house.Standing.change(level, mgr, dest.houseId, origin.ownerPlayer(),
                        net.aiagent256.stormhold.house.Standing.SUPPLIED_CASTLE, "you supplied " + dest.name);
            }
        } else {
            level.getChunkAt(s.to);
            BlockEntity be = level.getBlockEntity(s.to);
            IItemHandler handler = null;
            if (be != null) {
                handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve()
                        .or(() -> be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).resolve()).orElse(null);
            }
            for (ItemStack st : s.cargo) {
                ItemStack rest = st.copy();
                if (handler != null) {
                    rest = ItemHandlerHelper.insertItemStacked(handler, rest, false);
                } else if (be instanceof Container c) {
                    rest = HopperBlockEntity.addItem(null, c, rest, null);
                }
                delivered += st.getCount() - rest.getCount();
                if (!rest.isEmpty()) leftover.add(rest);
            }
        }
        for (ItemStack st : leftover) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
            if (id != null) origin.addStock(id, st.getCount());
        }
        String where = dest != null ? dest.name : s.to.getX() + ", " + s.to.getY() + ", " + s.to.getZ();
        HoldingEvents.tell(level, origin.ownerPlayer(), HoldingEvents.gold("A courier from " + origin.name + " delivered "
                + delivered + " goods to " + where + (leftover.isEmpty() ? "." : ". The rest found no room and went home.")));
        mgr.setDirty();
    }

    /** The realm's stock of the things that matter, for the War Table. */
    public static String realmSummary(HoldingManager mgr, Holding h, java.util.UUID player) {
        String[][] keys = {{"bread", "bread"}, {"iron_ingot", "iron"}, {"cobblestone", "stone"}, {"stone_bricks", "stone"},
                {"stone", "stone"}, {"andesite", "stone"}, {"spruce_planks", "planks"}, {"oak_planks", "planks"},
                {"emerald", "emeralds"}, {"diamond", "diamonds"}, {"gold_ingot", "gold"}, {"arrow", "arrows"}, {"wheat", "wheat"},
                {"leather", "leather"}, {"coal", "coal"}, {"netherite_ingot", "netherite"}};
        java.util.Map<String, Integer> totals = new java.util.LinkedHashMap<>();
        for (String[] k : keys) {
            int n = Stores.realmStock(mgr, h, player, new ResourceLocation("minecraft", k[0]));
            if (n > 0) totals.merge(k[1], n, Integer::sum);
        }
        int castles = Stores.realm(mgr, h, player).size();
        StringBuilder sb = new StringBuilder("Realm (" + castles + (castles == 1 ? " castle): " : " castles): "));
        int i = 0;
        for (var e : totals.entrySet()) {
            if (i++ > 0) sb.append(", ");
            sb.append(e.getValue()).append(" ").append(e.getKey());
        }
        if (totals.isEmpty()) sb.append("nothing in store.");
        return sb.toString();
    }

    public static String stockSummary(Holding h) {
        StringBuilder sb = new StringBuilder();
        h.stock.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6).forEach(e -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getValue()).append(' ').append(e.getKey().getPath().replace('_', ' '));
        });
        return sb.length() == 0 ? "empty" : sb.toString();
    }

    private Economy() {
    }
}
