package net.aiagent256.stormhold.net;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.compat.RecruitsCompat;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.holding.Campaign;
import net.aiagent256.stormhold.holding.Campaigns;
import net.aiagent256.stormhold.holding.Company;
import net.aiagent256.stormhold.holding.Economy;
import net.aiagent256.stormhold.holding.Holding;
import net.aiagent256.stormhold.holding.HoldingEvents;
import net.aiagent256.stormhold.holding.HoldingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class Net {
    private static final String VERSION = "10";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(Stormhold.id("main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    public static final int DEFEND = 0, PRODUCE = 1, ATTACK = 2, SET_ATTACK = 3, SET_SUPPLY = 4, CLEAR_SUPPLY = 5,
            MUSTER = 6, REFRESH = 7, MARCH_NOW = 8, MUSTER_KNIGHTS = 9, MUSTER_ARCHERS = 10, MUSTER_CROSSBOWMEN = 11, DISMISS = 12, ARM = 13, SEND_COLUMN = 14, KEEP_WITH_ME = 15, EXPAND_FIEF = 16, REPAIR = 17, FORTIFY = 18, OPEN_REALM = 19,
            SET_MINERS = 20, SET_SMITHS = 21, PROJECT = 22, SCOUT = 23, TREASURY = 24, VASSAL = 25, REVOKE = 26, COUNCIL = 27,
            COUNCIL_APPOINT = 28, COUNCIL_DISMISS = 29, MATTER_CHOOSE = 30, MARCH_ALL = 31, TIDY = 32, TARGET_ALL = 33,
            SET_SEAT = 34, RUSH = 35, BUY_CLAIM = 36, TOURNEY = 37, TRUCE = 38, SEND_HOME = 39, ATTACK_ALL = 40,
            BANNERS = 41, SURPLUS = 42;

    public static void register() {
        CHANNEL.registerMessage(0, WarTableData.class, WarTableData::write, WarTableData::read, WarTableData::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, WarTableAction.class, WarTableAction::write, WarTableAction::read, WarTableAction::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, RealmData.class, RealmData::write, RealmData::read, Net::handleRealm,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private static void handleRealm(RealmData data, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.aiagent256.stormhold.client.ClientHooks.showRealm(data)));
        ctx.get().setPacketHandled(true);
    }

    /** The Realm page: the map of what the player knows, his houses and his record. */
    public static void openRealm(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), RealmData.build(player));
    }

    // ------------------------------------------------------------ server -> client

    /** One seat of the lord's council as the War Table shows it; holder is empty for an empty seat. */
    public record Seat(int ordinal, String title, String holder, int wage, String does, String brief) {
    }

    /** A matter waiting on the lord's word: the story and its two answers. */
    public record Decision(UUID id, String text, String first, String second) {
    }

    public record WarTableData(List<HoldingInfo> holdings, boolean recruits, List<Seat> council, int treasury,
                               @org.jetbrains.annotations.Nullable Decision decision, List<net.aiagent256.stormhold.holding.WarPlan.Target> targets,
                               boolean sellSurplus) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(holdings.size());
            for (HoldingInfo h : holdings) h.write(b);
            b.writeBoolean(recruits);
            b.writeVarInt(council.size());
            for (Seat s : council) {
                b.writeVarInt(s.ordinal());
                b.writeUtf(s.title());
                b.writeUtf(s.holder());
                b.writeVarInt(s.wage());
                b.writeUtf(s.does());
                b.writeUtf(s.brief());
            }
            b.writeVarInt(treasury);
            b.writeBoolean(decision != null);
            if (decision != null) {
                b.writeUUID(decision.id());
                b.writeUtf(decision.text());
                b.writeUtf(decision.first());
                b.writeUtf(decision.second());
            }
            b.writeVarInt(targets.size());
            for (var t : targets) t.write(b);
            b.writeBoolean(sellSurplus);
        }

        static WarTableData read(FriendlyByteBuf b) {
            int n = b.readVarInt();
            List<HoldingInfo> l = new ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(HoldingInfo.read(b));
            boolean recruits = b.readBoolean();
            int k = b.readVarInt();
            List<Seat> c = new ArrayList<>(k);
            for (int i = 0; i < k; i++) c.add(new Seat(b.readVarInt(), b.readUtf(), b.readUtf(), b.readVarInt(), b.readUtf(), b.readUtf()));
            int treasury = b.readVarInt();
            Decision d = b.readBoolean() ? new Decision(b.readUUID(), b.readUtf(), b.readUtf(), b.readUtf()) : null;
            int nt = b.readVarInt();
            List<net.aiagent256.stormhold.holding.WarPlan.Target> targets = new ArrayList<>(nt);
            for (int i = 0; i < nt; i++) targets.add(net.aiagent256.stormhold.holding.WarPlan.Target.read(b));
            return new WarTableData(l, recruits, c, treasury, d, targets, b.readBoolean());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> net.aiagent256.stormhold.client.ClientHooks.showWarTable(this)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static void openWarTable(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), build(player));
    }

    /** Every castle of the player's, a loyal vassal's too, sends its war party at the target; what went, in chat. */
    private static void marchAll(ServerLevel level, HoldingManager mgr, ServerPlayer player, BlockPos target) {
        Holding enemy = mgr.atXZ(target, 16);
        var plan = enemy != null ? net.aiagent256.stormhold.holding.WarPlan.plan(level, mgr, player.getUUID(), enemy) : null;
        List<String> went = new ArrayList<>(), stayed = new ArrayList<>();
        int[] men = new int[4];
        for (Holding o : mgr.ownedBy(player.getUUID())) {
            if (o == enemy) continue;
            if (!net.aiagent256.stormhold.holding.Vassals.answers(o)) {
                stayed.add(o.name + " (" + o.lordName + ", loyalty " + o.loyalty + ")");
                continue;
            }
            Campaign c = Campaigns.launchParty(level, mgr, o, target);
            if (c != null) {
                went.add(o.name);
                Role[] rs = {Role.CAPTAIN, Role.KNIGHT, Role.ARCHER, Role.CROSSBOWMAN};
                for (int i = 0; i < 4; i++) men[i] += c.counts.getOrDefault(rs[i], 0);
            } else {
                stayed.add(o.name);
            }
        }
        player.sendSystemMessage(Component.literal(went.isEmpty() ? "No castle of yours has men to spare, or each has a war party out already."
                : went.size() + (went.size() == 1 ? " war party marches" : " war parties march") + (enemy != null ? " on " + enemy.name : " on the target")
                + " from " + String.join(", ", went) + ": " + net.aiagent256.stormhold.holding.WarPlan.men(men) + "."
                + (plan != null && !plan.parts().isEmpty() ? " Chance: " + plan.pct() + "%, " + net.aiagent256.stormhold.holding.WarPlan.verdict(plan.pct()) + "." : "")
                + " They wait at the walls for each other and go in together."
                + (stayed.isEmpty() ? "" : " Staying home: " + String.join(", ", stayed) + "."))
                .withStyle(went.isEmpty() ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.GOLD));
    }

    /** The odds against the castle a place is set to march on, in chat. */
    private static void tellOdds(ServerLevel level, HoldingManager mgr, ServerPlayer player, Holding h) {
        String odds = net.aiagent256.stormhold.holding.Readiness.tableLine(level, mgr, h, h.attackTarget);
        if (odds != null) player.sendSystemMessage(Component.literal(odds).withStyle(net.minecraft.ChatFormatting.GOLD));
    }

    private static WarTableData build(ServerPlayer player) {
        List<HoldingInfo> out = new ArrayList<>();
        for (ServerLevel level : player.server.getAllLevels()) {
            HoldingManager mgr = HoldingManager.get(level);
            net.aiagent256.stormhold.holding.Seats.ensure(level, mgr, player.getUUID());
            for (Holding h : mgr.ownedBy(player.getUUID())) {
                int[] counts = {h.count(Role.CAPTAIN), h.count(Role.KNIGHT), h.count(Role.ARCHER), h.count(Role.CROSSBOWMAN)};
                int[] cap = {h.capacity(Role.CAPTAIN), h.capacity(Role.KNIGHT), h.capacity(Role.ARCHER), h.capacity(Role.CROSSBOWMAN)};
                StringBuilder status = new StringBuilder();
                for (Campaign c : mgr.campaigns) {
                    if (c.stage == Campaign.Stage.DONE) continue;
                    if (c.kind == Campaign.Kind.RAID && h.id.equals(c.target)) status.append("Under attack by ").append(c.total()).append(" men. ");
                    if (c.kind == Campaign.Kind.ILLAGERS && h.id.equals(c.target)) status.append("Illager warband at the walls: ").append(c.total()).append(". ");
                    if (c.kind == Campaign.Kind.PARTY && h.id.equals(c.origin)) status.append("War party out: ").append(c.total()).append(" men, ")
                            .append(c.stage.name().toLowerCase()).append(". ");
                    if (c.kind == Campaign.Kind.SCOUT && h.id.equals(c.origin)) status.append("Scouts out, back in about ")
                            .append(net.aiagent256.stormhold.holding.Scouts.time((int) Math.ceil(((c.stage == Campaign.Stage.MARCH ? 2 : 1) - c.progress)
                                    * c.length() / net.aiagent256.stormhold.holding.Scouts.SPEED))).append(". ");
                    if (c.kind == Campaign.Kind.COLUMN && h.id.equals(c.origin)) status.append("Column of ").append(c.total())
                            .append(" riding to you, ").append(net.aiagent256.stormhold.holding.Columns.eta(c)).append(" out. ");
                    if (c.kind == Campaign.Kind.COLUMN && c.origin == null && h.id.equals(c.target)) status.append(c.total())
                            .append(" of your men riding home here, ").append(net.aiagent256.stormhold.holding.Columns.eta(c)).append(" out. ");
                }
                if (!h.work.equals(net.aiagent256.stormhold.holding.Builder.NONE)) {
                    int left = h.workQueue == null ? -1 : h.workQueue.size();
                    status.append("Builder ").append(net.aiagent256.stormhold.holding.Builder.doing(h))
                            .append(left < 0 ? ", surveying. " : ": " + left + " blocks to go. ");
                } else if (h.fortify > 0) {
                    status.append("Fortified, stage ").append(h.fortify).append(". ");
                }
                if (net.aiagent256.stormhold.holding.Vassals.is(h)) status.append("Vassal: ").append(h.lordName).append(", loyalty ")
                        .append(h.loyalty).append(", sends ").append(Math.round(net.aiagent256.stormhold.holding.Vassals.share(h) * 100)).append("% of rents. ");
                if (h.reinforceKeep > 0) status.append("Supply line: keeps ").append(h.reinforceKeep).append(" men with you. ");
                if (net.aiagent256.stormhold.holding.Seats.is(mgr, h)) status.append("Your seat. ");
                if (net.aiagent256.stormhold.holding.Heirs.claimed(h)) status.append("Claimed by ").append(h.heir).append(". ");
                var truce = net.aiagent256.stormhold.holding.Truces.get(mgr, h.houseId, player.getUUID());
                if (truce != null) status.append("Truce with ").append(mgr.house(h.houseId).title()).append(", ")
                        .append(net.aiagent256.stormhold.holding.Truces.daysLeft(level, truce)).append(" days left. ");
                if (h.nextRaid > 0 && truce == null && !net.aiagent256.stormhold.house.Standing.friendly(mgr, h.houseId, player.getUUID())) {
                    long days = Math.max(0, (h.nextRaid - level.getGameTime()) / 24000);
                    status.append(h.raidWarned ? "Raid expected within a day." : "Next raid in about " + days + " days.");
                }
                String fief = h.fiefReport.isEmpty()
                        ? (h.fiefPlan != null && !h.fiefPlan.isEmpty() ? "The fief is being laid out: " + h.fiefPlan.size() + " buildings to go."
                        : h.fief.isEmpty() ? "No fief." : h.fiefName() + " of " + h.fief.size() + " buildings; census at dawn.")
                        : h.fiefReport;
                if (h.fiefPlan != null && !h.fiefPlan.isEmpty() && !h.fiefReport.isEmpty()) fief += " Building: " + h.fiefPlan.get(0).replace('_', ' ') + ".";
                int kit = net.aiagent256.stormhold.holding.Frontier.level(level, h);
                String labour = "Kit level " + kit + " (" + net.aiagent256.stormhold.holding.Labour.kitName(kit) + "). "
                        + (h.labourReport.isEmpty() ? "" : h.labourReport + " ")
                        + "Working: " + h.minersAt + " of " + h.miners + " miners, " + h.smithsAt + " of " + h.smiths + " smiths.";
                String odds = h.attackTarget == null ? "" : java.util.Objects.requireNonNullElse(
                        net.aiagent256.stormhold.holding.Readiness.tableLine(level, mgr, h, h.attackTarget),
                        "No castle of another house stands at the target: a war party marches there and back.");
                if (h.forgePaid && h.forgeTarget > 0) labour += " The forge is at level " + h.forgeTarget + ": " + h.forgeWork + " of "
                        + net.aiagent256.stormhold.holding.Labour.SMITH_DAYS + " smith-days done.";
                String tourney = java.util.Objects.requireNonNullElse(net.aiagent256.stormhold.holding.Tourney.refusal(level, mgr, h, player), "");
                out.add(new HoldingInfo(h.id, h.name, h.isTown() ? 0 : h.tier, h.mode.name(), counts, cap, Economy.stockSummary(h), h.center(),
                        h.attackTarget, h.supplyTarget, status.toString(), fief, Economy.realmSummary(mgr, h, player.getUUID()),
                        h.miners, h.smiths, labour, odds, h.tidy, net.aiagent256.stormhold.holding.Seats.is(mgr, h),
                        net.aiagent256.stormhold.holding.Labour.rushCost(level, h), net.aiagent256.stormhold.holding.Heirs.line(mgr, h),
                        net.aiagent256.stormhold.holding.Heirs.price(h), tourney));
            }
        }
        // the council is the lord's, not a castle's: who sits, what each seat does, and the emeralds that pay them
        ServerLevel over = player.server.overworld();
        HoldingManager record = HoldingManager.get(over);
        var seats = record.councils.getOrDefault(player.getUUID(), new java.util.EnumMap<>(net.aiagent256.stormhold.holding.Council.Seat.class));
        List<Seat> council = new ArrayList<>();
        for (var s : net.aiagent256.stormhold.holding.Council.Seat.values())
            council.add(new Seat(s.ordinal(), s.title, seats.getOrDefault(s, ""), s.wage, s.does, s.brief));
        int treasury = 0;
        Holding any = null;
        for (ServerLevel level : player.server.getAllLevels()) {
            var mine = HoldingManager.get(level).ownedBy(player.getUUID());
            if (!mine.isEmpty()) {
                any = mine.get(0);
                treasury = net.aiagent256.stormhold.holding.Stores.realmStock(HoldingManager.get(level), any, player.getUUID(),
                        net.aiagent256.stormhold.holding.Rewards.EMERALD);
                break;
            }
        }
        Decision decision = null;
        for (ServerLevel level : player.server.getAllLevels()) {
            HoldingManager m = HoldingManager.get(level);
            var open = net.aiagent256.stormhold.holding.Events.open(m, player.getUUID());
            if (open != null) {
                var card = net.aiagent256.stormhold.holding.Events.card(m, open);
                decision = new Decision(open.id, card.text(), card.first(), card.second());
                break;
            }
        }
        // the castles you know near your own, with what you'd send at each and the chance: in the world you stand in
        var targets = net.aiagent256.stormhold.holding.WarPlan.targets(player.serverLevel(), HoldingManager.get(player.serverLevel()), player.getUUID());
        return new WarTableData(out, RecruitsCompat.loaded(), council, treasury, decision, targets,
                net.aiagent256.stormhold.holding.Surplus.selling(player.server, player.getUUID()));
    }

    // ------------------------------------------------------------ client -> server

    public record WarTableAction(UUID holding, int action, BlockPos pos, int amount) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(holding);
            b.writeVarInt(action);
            b.writeBlockPos(pos);
            b.writeVarInt(amount);
        }

        static WarTableAction read(FriendlyByteBuf b) {
            return new WarTableAction(b.readUUID(), b.readVarInt(), b.readBlockPos(), b.readVarInt());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            ctx.get().enqueueWork(() -> {
                if (player != null) apply(player, this);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static void sendAction(UUID holding, int action, BlockPos pos, int amount) {
        CHANNEL.sendToServer(new WarTableAction(holding, action, pos, amount));
    }

    /** A Knight musters ten at a time, a Lord twenty, a Warden forty, a King eighty. */
    private static int musterCap(HoldingManager mgr, ServerPlayer player) {
        int cap = net.aiagent256.stormhold.holding.Titles.musterCap(net.aiagent256.stormhold.holding.Titles.rank(mgr, player.getUUID()));
        if (cap > 40 && player.server.getPlayerList().getPlayer(player.getUUID()) == null) return 40;
        return cap;
    }

    private static void apply(ServerPlayer player, WarTableAction a) {
        if (a.action() == OPEN_REALM) {
            openRealm(player);
            return;
        }
        if (a.action() == REFRESH && a.holding().equals(new UUID(0, 0))) {
            openWarTable(player);
            return;
        }
        if (a.action() == TARGET_ALL) {
            // picked on the Realm map: every castle of yours (a vassal too, while his loyalty holds) takes it for its target
            for (ServerLevel level : player.server.getAllLevels()) {
                HoldingManager mgr = HoldingManager.get(level);
                Holding enemy = mgr.atXZ(a.pos(), 16);
                List<Holding> mine = mgr.ownedBy(player.getUUID());
                if (mine.isEmpty() || enemy == null) continue;
                int n = 0;
                for (Holding h : mine) {
                    if (!net.aiagent256.stormhold.holding.Vassals.answers(h) || h == enemy) continue;
                    h.attackTarget = enemy.center();
                    n++;
                }
                mgr.setDirty();
                player.sendSystemMessage(net.aiagent256.stormhold.holding.HoldingEvents.gold(enemy.name + " is the target of " + n
                        + (n == 1 ? " castle" : " castles") + " of yours. " + Campaigns.allOdds(level, mgr, player.getUUID(), enemy)
                        + " Press \"All castles march\" on the Orders tab to send them."));
                break;
            }
            openWarTable(player);
            return;
        }
        if (a.action() == ATTACK_ALL) {
            // from the Attack page: every castle of yours takes the castle at pos for its target and marches on it
            ServerLevel level = player.serverLevel();
            HoldingManager mgr = HoldingManager.get(level);
            Holding enemy = mgr.atXZ(a.pos(), 16);
            if (enemy != null && !enemy.owner.equals(Sides.player(player.getUUID()))) {
                for (Holding o : mgr.ownedBy(player.getUUID())) if (net.aiagent256.stormhold.holding.Vassals.answers(o)) o.attackTarget = enemy.center();
                mgr.setDirty();
                marchAll(level, mgr, player, enemy.center());
            }
            openWarTable(player);
            return;
        }
        if (a.action() == BANNERS) {
            net.aiagent256.stormhold.holding.Banners.call(player);
            openWarTable(player);
            return;
        }
        if (a.action() == SURPLUS) {
            net.aiagent256.stormhold.holding.Surplus.toggle(player);
            openWarTable(player);
            return;
        }
        if (a.action() == SEND_HOME) {
            net.aiagent256.stormhold.holding.Company.sendHome(player.serverLevel(), HoldingManager.get(player.serverLevel()), player);
            openWarTable(player);
            return;
        }
        if (a.action() == TRUCE) {
            // from the Realm page: the house by its id's hash, paid (0) or with a hostage (1)
            ServerLevel level = player.serverLevel();
            HoldingManager mgr = HoldingManager.get(level);
            String house = null;
            for (String id : mgr.houses.keySet()) if (id.hashCode() == a.amount()) house = id;
            if (house != null) net.aiagent256.stormhold.holding.Truces.ask(level, mgr, player, house, a.pos().getX() == 1);
            openRealm(player);
            return;
        }
        if (a.action() == MATTER_CHOOSE) {
            for (ServerLevel level : player.server.getAllLevels())
                if (net.aiagent256.stormhold.holding.Events.choose(level, HoldingManager.get(level), player.getUUID(), a.holding(), a.amount() == 1 ? 1 : 2)) break;
            openWarTable(player);
            return;
        }
        if (a.action() == COUNCIL_APPOINT || a.action() == COUNCIL_DISMISS) {
            var seats = net.aiagent256.stormhold.holding.Council.Seat.values();
            if (a.amount() >= 0 && a.amount() < seats.length) {
                if (a.action() == COUNCIL_APPOINT) net.aiagent256.stormhold.holding.Council.appoint(player.serverLevel(), player, seats[a.amount()]);
                else net.aiagent256.stormhold.holding.Council.dismiss(player.serverLevel(), player, seats[a.amount()]);
            }
            openWarTable(player);
            return;
        }
        String me = Sides.player(player.getUUID());
        for (ServerLevel level : player.server.getAllLevels()) {
            HoldingManager mgr = HoldingManager.get(level);
            Holding h = mgr.holdings.get(a.holding());
            if (h == null) continue;
            if (!me.equals(h.owner)) return;
            switch (a.action()) {
                case DEFEND -> h.mode = Holding.Mode.DEFEND;
                case PRODUCE -> h.mode = Holding.Mode.PRODUCE;
                case ATTACK -> {
                    h.mode = Holding.Mode.ATTACK;
                    if (h.attackTarget == null) player.sendSystemMessage(Component.literal("Set a target to march on."));
                }
                case SET_ATTACK -> {
                    h.attackTarget = a.pos();
                    tellOdds(level, mgr, player, h);
                }
                case SET_SUPPLY -> h.supplyTarget = a.pos();
                case CLEAR_SUPPLY -> h.supplyTarget = null;
                case MARCH_NOW -> {
                    if (h.attackTarget == null) {
                        player.sendSystemMessage(Component.literal("Set a target first."));
                    } else {
                        tellOdds(level, mgr, player, h);
                        if (Campaigns.launchParty(level, mgr, h, h.attackTarget) == null)
                            player.sendSystemMessage(Component.literal("Not enough men to spare, or a war party is already out."));
                    }
                }
                case MUSTER, MUSTER_KNIGHTS -> Company.muster(level, mgr, player, h, Role.KNIGHT, Math.max(1, Math.min(musterCap(mgr, player), a.amount())));
                case MUSTER_ARCHERS -> Company.muster(level, mgr, player, h, Role.ARCHER, Math.max(1, Math.min(musterCap(mgr, player), a.amount())));
                case MUSTER_CROSSBOWMEN -> Company.muster(level, mgr, player, h, Role.CROSSBOWMAN, Math.max(1, Math.min(musterCap(mgr, player), a.amount())));
                case DISMISS -> Company.dismiss(level, mgr, player, h);
                case SEND_COLUMN -> {
                    if (net.aiagent256.stormhold.holding.Columns.active(mgr, h)) {
                        player.sendSystemMessage(Component.literal("A column from " + h.name + " is already on the road."));
                    } else {
                        var want = new java.util.EnumMap<Role, Integer>(Role.class);
                        want.put(Role.KNIGHT, Math.max(0, Math.min(60, a.pos().getX())));
                        want.put(Role.ARCHER, Math.max(0, Math.min(60, a.pos().getY())));
                        want.put(Role.CROSSBOWMAN, Math.max(0, Math.min(60, a.pos().getZ())));
                        net.aiagent256.stormhold.holding.Columns.send(level, mgr, player, h, want);
                    }
                }
                case EXPAND_FIEF -> {
                    var bread = new net.minecraft.resources.ResourceLocation("minecraft", "bread");
                    var iron = new net.minecraft.resources.ResourceLocation("minecraft", "iron_ingot");
                    if (h.fiefPlan == null) net.aiagent256.stormhold.holding.Fief.plan(level, h);
                    if (h.fiefPlan.size() >= 3) {
                        player.sendSystemMessage(Component.literal("The builders at " + h.name + " already have their hands full."));
                    } else if (net.aiagent256.stormhold.holding.Stores.stockWithChests(mgr, h, player.getUUID(), bread) < 32
                            || net.aiagent256.stormhold.holding.Stores.stockWithChests(mgr, h, player.getUUID(), iron) < 8) {
                        player.sendSystemMessage(Component.literal("Raising a building takes 32 bread and 8 iron from the realm's stores or this castle's chests.")
                                .withStyle(net.minecraft.ChatFormatting.RED));
                    } else {
                        net.aiagent256.stormhold.holding.Stores.takeWithChests(mgr, h, player.getUUID(), bread, 32);
                        net.aiagent256.stormhold.holding.Stores.takeWithChests(mgr, h, player.getUUID(), iron, 8);
                        String next = net.aiagent256.stormhold.holding.Fief.nextExtra(h);
                        h.fiefPlan.add(next);
                        player.sendSystemMessage(HoldingEvents.gold("The people of " + h.name + " will raise a " + next.replace('_', ' ') + "."));
                    }
                }
                case SET_MINERS -> player.sendSystemMessage(net.aiagent256.stormhold.holding.Labour.set(h,
                        net.aiagent256.stormhold.holding.Labour.MINER, a.amount()));
                case SET_SMITHS -> player.sendSystemMessage(net.aiagent256.stormhold.holding.Labour.set(h,
                        net.aiagent256.stormhold.holding.Labour.SMITH, a.amount()));
                case REPAIR -> net.aiagent256.stormhold.holding.Builder.order(level, mgr, h, net.aiagent256.stormhold.holding.Builder.REPAIR, player);
                case FORTIFY -> net.aiagent256.stormhold.holding.Builder.order(level, mgr, h, net.aiagent256.stormhold.holding.Builder.FORTIFY, player);
                case SCOUT -> net.aiagent256.stormhold.holding.Scouts.send(level, mgr, h, player, a.pos());
                case SET_SEAT -> net.aiagent256.stormhold.holding.Seats.set(level, mgr, h, player);
                case RUSH -> net.aiagent256.stormhold.holding.Labour.rush(level, mgr, h, player);
                case BUY_CLAIM -> net.aiagent256.stormhold.holding.Heirs.buyOut(level, mgr, h, player);
                case TOURNEY -> {
                    if (net.aiagent256.stormhold.holding.Tourney.start(level, mgr, h, player)) return;   // the table's closed for the fight
                }
                case TIDY -> {
                    h.tidy = !h.tidy;
                    player.sendSystemMessage(net.aiagent256.stormhold.holding.HoldingEvents.gold(h.tidy
                            ? "The steward of " + h.name + " will keep its chests in order: the spoils chest emptied into them, each kind of "
                            + "thing gathered into the chest with the most of it, every chest stacked and sorted. A chest with a sign on it he leaves alone."
                            : "The steward of " + h.name + " leaves the chests as they are."));
                    if (h.tidy) net.aiagent256.stormhold.holding.Tidy.tick(level, mgr, h);
                }
                case MARCH_ALL -> {
                    if (h.attackTarget == null) {
                        player.sendSystemMessage(Component.literal("Set a target first."));
                    } else {
                        tellOdds(level, mgr, player, h);
                        marchAll(level, mgr, player, h.attackTarget);
                    }
                }
                case VASSAL -> {
                    if (net.aiagent256.stormhold.holding.Vassals.is(h)) net.aiagent256.stormhold.holding.Vassals.gift(level, mgr, h, player);
                    else net.aiagent256.stormhold.holding.Vassals.grant(level, mgr, h, player);
                }
                case REVOKE -> net.aiagent256.stormhold.holding.Vassals.revoke(level, mgr, h, player);
                case COUNCIL -> {
                    net.aiagent256.stormhold.holding.Council.show(level, player);
                    return;
                }
                case TREASURY -> {
                    var em = new net.minecraft.resources.ResourceLocation("minecraft", "emerald");
                    int have = net.aiagent256.stormhold.holding.Stores.realmStock(mgr, h, player.getUUID(), em);
                    int n = Math.min(64, have);
                    if (n <= 0) {
                        player.sendSystemMessage(Component.literal("The realm's treasury is empty.").withStyle(net.minecraft.ChatFormatting.RED));
                    } else {
                        net.aiagent256.stormhold.holding.Stores.take(mgr, h, player.getUUID(), em, n);
                        net.minecraft.world.item.ItemStack purse = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, n);
                        if (!player.getInventory().add(purse)) player.drop(purse, false);
                        player.sendSystemMessage(HoldingEvents.gold("You take " + n + " emeralds from the treasury; " + (have - n)
                                + " left in the realm's stores."));
                    }
                }
                case PROJECT -> {
                    String[] ps = net.aiagent256.stormhold.holding.Builder.PROJECTS;
                    if (a.amount() >= 0 && a.amount() < ps.length) net.aiagent256.stormhold.holding.Builder.order(level, mgr, h, ps[a.amount()], player);
                }
                case KEEP_WITH_ME -> {
                    h.reinforceKeep = Math.max(0, Math.min(80, a.amount()));
                    h.nextColumn = 0;
                    int back = h.reinforceKeep > 0 ? 0 : net.aiagent256.stormhold.holding.Columns.recallSupply(mgr, h);
                    player.sendSystemMessage(h.reinforceKeep > 0
                            ? HoldingEvents.gold(h.name + " will keep " + h.reinforceKeep + " men with you, sending a column whenever you run short.")
                            : Component.literal("Supply line from " + h.name + " cancelled." + (back > 0 ? " The column it sent turns back: "
                            + back + " men home to " + h.name + "." : "")));
                }
                case ARM -> {
                    var kit = net.aiagent256.stormhold.holding.Armoury.Kit.byTier(a.amount());
                    if (kit != null) net.aiagent256.stormhold.holding.Armoury.arm(level, mgr, player, h, kit, a.pos().getX() != 0);
                }
                default -> {
                }
            }
            mgr.setDirty();
            break;
        }
        openWarTable(player);
    }

    private Net() {
    }
}
