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
- *Attack*: sends a war party to the target coordinates every few minutes. They march, fight for five minutes, and come home. If the target is a house castle whose lord is dead, they can take it for you.
- *Supply to*: any chest, barrel, or modded inventory (MineColonies huts work), or another castle you hold. Sneak-use your War Banner on a chest to mark it as a depot and the table pre-fills it. Couriers walk the road and can be ambushed by brigands.
- *Muster to me*: turns five garrison soldiers into Villager Recruits units hired to you.

**Raids.** Every 3 to 5 days the house you took the castle from marches to retake it. You get a warning a day ahead. If you're away it's fought out by numbers; Defend stance helps.

**Lived-in world.** Villagers work inside the walls, fields and orchards appear outside, and a road is laid from the gate to the nearest village as the chunks load.

## Commands
`/stormhold list | nearest | info` for everyone; `capture | raid | killlord` for ops (testing).

## Building
CI builds on every push to `main` and runs headless game tests. The jar lands on the `dist` branch, logs on `ci-logs`. Castle structures come from `tools/castlegen` (`python3 build_all.py`).
