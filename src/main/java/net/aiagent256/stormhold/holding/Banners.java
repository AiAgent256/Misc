package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.entity.Role;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Calling the banners: every castle the lord holds, his vassals' too, sends its war party to wherever he is, as a
 * column riding at {@link Columns#SPEED} blocks a second. Each keeps what it keeps when it marches to war (three
 * knights, an archer and a crossbowman at home). When they reach him they fall in as his company, to be led with the
 * baton, and Send home sends each man back to his own castle when it's done. A castle under attack keeps its men, and
 * so does a vassal whose loyalty has run low. A host riding to its lord in force isn't picked off on the road.
 */
public final class Banners {
    /** Call them. How many men ride. */
    public static int call(ServerPlayer player) {
        List<String> come = new ArrayList<>(), stay = new ArrayList<>();
        int total = 0;
        for (ServerLevel level : player.server.getAllLevels()) {
            HoldingManager mgr = HoldingManager.get(level);
            for (Holding h : mgr.ownedBy(player.getUUID())) {
                if (Campaigns.active(mgr, h.id, Campaign.Kind.RAID) || Warbands.active(mgr, h.id)) {
                    stay.add(h.name + " (under attack)");
                    continue;
                }
                if (!Vassals.answers(h)) {
                    stay.add(h.name + " (" + h.lordName + ", loyalty " + h.loyalty + ")");
                    continue;
                }
                EnumMap<Role, Integer> want = new EnumMap<>(Role.class);
                for (Role r : WarPlan.ROLES) {
                    int n = Campaigns.sends(h, r);
                    if (n > 0) want.put(r, n);
                }
                Campaign c = want.isEmpty() ? null : Columns.send(level, mgr, player, h, want, true);
                if (c == null) {
                    stay.add(h.name + " (no one to spare)");
                    continue;
                }
                c.muster = true;
                total += c.total();
                come.add(h.name + (Vassals.is(h) ? " (" + h.vassal + ")" : "") + " " + c.total() + ", " + Columns.eta(c));
            }
        }
        if (total > 0) {
            player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(2).value(), SoundSource.PLAYERS, 1.5F, 0.8F);
            player.sendSystemMessage(HoldingEvents.gold("You call the banners. Riding to you: " + String.join("; ", come) + ". " + total
                    + " men in all. They fall in as your company when they reach you; lead them with the baton, and Send home (Company tab) "
                    + "when it's done."));
        } else {
            player.sendSystemMessage(Component.literal("No castle of yours can answer the call.").withStyle(ChatFormatting.RED));
        }
        if (!stay.isEmpty()) player.sendSystemMessage(Component.literal("Staying home: " + String.join(", ", stay) + ".")
                .withStyle(ChatFormatting.GRAY));
        return total;
    }

    private Banners() {
    }
}
