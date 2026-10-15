# Stormhold

Private Forge 1.20.1 mod for the Dungeons and Homestead pack. Castles held by rival houses, sieges, capture, and armies you command afterward.

## Playing it

**Finding castles.** Stormhold generates three tiers of its own castles (watchtowers, holdfasts, great castles) in five regional styles chosen by biome: Northern grey stone (taiga, snow), Riverlands brick and timber (plains, forest, swamp), Reach white stone with green copper roofs (flower fields, meadows, cherry groves), Dornish sandstone (desert, badlands, savanna), Stormlands blackstone (dark forest). Castles from When Dungeons Arise, IDAS, Illager Invasion and Dungeons and Taverns are garrisoned too (see config). `/stormhold nearest` names the closest known castle.

**The garrison.** Knights patrol the baileys, archers and crossbowmen hold the walls, a lord sits in the great hall. They wear Epic Knights armour and carry shields in their house's colours. Attack one and the whole castle comes for you.

**Siege rules while a house holds the castle.**
- Walls can't be broken or built on. Ladders, scaffolding, torches and TNT are allowed.
- Portcullises, doors, glass and iron bars can be broken (pickaxe, TNT, or Siege Weapons rams).
- Explosions only break those siege-breakable blocks.
- Chests stay sealed until the lord is dead.

**Taking it.**
1. Kill the lord. At half health he calls his household guard.
2. Carry a War Banner (crafted: any banner + iron ingot + stick; sneak-use in the air to change colour) to the banner stand at the top of the keep and use it on the stand.
3. The garrison counterattacks. Hold the stand with no defenders within 12 blocks for 90 seconds. A bar shows progress.
4. The castle is yours: banners turn your colour, half a garrison swears to you, and a War Table appears in the hall.

**Commanding (War Table).** Lists every castle you hold.
- *Defend*: faster reinforcement, stronger against raids.
- *Produce*: makes goods every day (bread, iron, gold, arrows, emeralds at higher tiers). Surplus goes to the supply target by courier.
- *Attack*: sends a war party to the target coordinates every few minutes. They file out of the gate, march, fight for five minutes, and come home. A party you watch that can't find its way (walls, water, cliffs) carries on as a count and reappears further down the road. If the target is a house castle whose lord is dead, they can take it for you.
- *Supply to*: any chest, barrel, or modded inventory (MineColonies huts work), or another castle you hold. Sneak-use your War Banner on a chest to mark it as a depot and the table pre-fills it. Couriers walk the road and can be ambushed by brigands.
- *Company*: pick how many knights, archers and crossbowmen you want (shift-click for fives) and *Muster*. They walk out of the garrison and fall in beside you; each castle keeps at least one of each. *Dismiss* sends every man of yours within 24 blocks into that castle's garrison. Both need you within 128 blocks of the castle. With Villager Recruits installed your company is Recruits units (use its follow/guard orders); without it they're Stormhold soldiers who follow you, catch up if you ride off, and go after whatever attacks you.

**War Horn.** A War Table you carry: War Banner + gold ingot + 4 copper ingots. Sound it anywhere to open the same screen, so you can call your men out of a castle as you ride past it and send them home when you're done.

**Houses and standing.** Every house has an opinion of you, from sworn enemy to sworn friend. About a third start friendly. `/stormhold houses` lists them. A friendly house's guards ignore you, its lord sells bread (an emerald) or iron (sneak + emerald), it never raids you, and it sends knights when someone else does. If its lord is dead you can take the castle by bending the knee: raise your banner and the garrison swears to you at once, no counterattack. You earn standing by killing monsters at their gates and running supply lines to their castles. You lose it by hitting their guards, marching on them, or taking their castles. Standing drifts back toward the house's nature one point a day.

**Your own holding.** Build a castle or town, sneak-use a War Banner on two opposite corners (12 to 192 blocks a side), then place a War Table inside. Stormhold scans your walls for guard posts and the place becomes a holding you own: empty at first, trained up from bread and iron you put on the War Table (use the table while holding supplies), raided by the local house if it dislikes you, and commanded like any captured castle.

**The Great City.** Once per house region (about every 1500 blocks) a capital generates: 256 blocks across, three terraces climbing to the keep, harbour, sept, market with a notice board, barracks, Dragonpit. It is the seat of the house that rules the region and starts friendly: walk in, trade with its king (an emerald buys bread, sneak for iron), use it as a base. Its garrison is 80 strong but only the district you're in is populated at any time. Take it (kill the king, raise your banner, hold the donjon) and every leaderless castle of that house bends the knee to you.

**Living in the city.** At the barracks, use the Muster Post holding emeralds: 6 hires a knight, sneak for an archer (5), sneak with an empty off-hand for a crossbowman (5). They join your Villager Recruits company. Any door marked for sale in the city (use it to see the price, 32 emeralds) buys you the house: its chests open for you, you can build inside, and a bed there is your bed in the capital. The Notice Board in the market square lists jobs (clear a bandit camp, break a raider warband 200 to 300 blocks out); click one in chat to take it, kill them, and the city pays emeralds and standing. All of this needs the city's house to like you, or the city to be yours.

**Renown.** Every castle has a renown from 1 (a poor place) to 5 (a famed seat), rolled from its size, how far it is from spawn, and luck. You're told it in chat as you come within sight, and `/stormhold info` shows it. Renown sets the garrison's kit: chain at 1, iron at 2, Protection I at 3, diamond with Protection II at 4, diamond Protection IV with Sharpness III and Unbreaking at 5, netherite lords. Soldiers get 12% more health per rank. It also sets the payout: after the lord falls, the first chests opened in the keep carry extra treasure, from a golden apple at 2 up to two Protection IV pieces, a Sharpness V sword, netherite, an enchanted golden apple, Mending and a totem at 5. Watchtowers cap at 3, holdfasts at 4; the Great City is always 5.

**Fighting the garrison.** Knights reach as far as you do, three blocks in any direction, so standing on a ledge two blocks up doesn't make you safe. Knights use ladders, vines and scaffolding to get at you, including the siege ladders you leave on the wall. A knight who takes hits from someone he can't reach for a few seconds backs off out of reach and leaves you to the archers. Archers work their stretch of wall: if the shot is blocked or too long they move along the battlements to a spot that can see you, and if nothing on the wall can (you're inside, under them) they come down for a while and fight in the yard. Get up on the wall next to one and he hits you with the bow.

**Raids.** Every 3 to 5 days the house you took the castle from marches to retake it. You get a warning a day ahead. If you're away it's fought out by numbers; Defend stance helps.

**Lived-in world.** Villagers work inside the walls, fields and orchards appear outside, and a road is laid from the gate to the nearest village as the chunks load. Castles are sited on high, dry ground and keep clear of villages and towns from any mod (anything in `#minecraft:village`, plus a few extras in `#stormhold:castle_avoid`); a watchtower or holdfast never lands inside a great castle.

## Commands
`/stormhold list | nearest | info | houses` for everyone; `capture | raid | killlord | befriend` for ops (testing).

## Building
CI builds on every push to `main` and runs headless game tests. The jar lands on the `dist` branch, logs on `ci-logs`. Castle structures come from `tools/castlegen` (`python3 build_all.py`).
