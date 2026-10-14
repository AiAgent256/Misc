# Stormhold design spec

Private mod for Andrew's Forge 1.20.1 "Dungeons and Homestead" pack. Not distributed.

## One-line

Castles held by rival houses. Kill the lord, raise your banner, hold it. Then the castle is yours: it defends itself, produces goods, ships them where you say, and marches on coordinates you pick.

## Decisions from the interview (2026-10-14)

- Built on top of Villager Recruits (required dependency). Captured garrison converts to Recruits units.
- Capture: lord must be dead, then raise banner at the keep's banner point and hold against the counterattack. No stealth capture.
- Captured castle modes: Defend, Produce, Attack (coordinates). Set from the war table.
- Resources travel by courier NPC along supply lines to a chest the player marks. Couriers can be ambushed. MineColonies warehouse delivery is phase 6.
- Rival houses raid the player's castles every few days. Lost castles can be retaken.
- Look: grey stone, Westeros. Massive. A siege should feel like a siege.
- Difficulty: untouched great castle is brutal solo in full diamond, but possible.
- Castles from other structure mods (Dungeons Arise, Castle Dungeons, Ember's) get Stormhold systems attached by config. Stormhold's own castle is the top tier only and gets cut if it isn't better than theirs.

## Phases

1. Skeleton + CI (done when first jar builds green).
2. Garrison entities: Lord, Captain, Knight, Archer, Crossbowman. House system (name, banner color, hostility). Spawn into a bounded "holding" region.
3. Holding + capture: a Holding is a region + house + banner point + lord. Attach a Holding to any structure by structure tag via config. Capture flow and counterattack wave.
4. War table block + GUI. Modes. Garrison converts to Recruits on capture.
5. Raids: rival house war parties, timer per holding, siege ladders/rams if Siege Weapons is present.
6. Supply lines: tribute production, courier entity, marked chest targets, ambush events. Then the MineColonies warehouse hook.
7. Stormhold Great Castle: own structure, 120x120, 40-block walls, moat, two gatehouses, inner bailey, keep. Rendered for review before it ships.

## Holding (core data)

- id, house, tier (1 watchtower, 2 keep, 3 great castle)
- bounds (AABB), banner point (BlockPos), throne/lord spawn, wall posts, gate posts
- owner: house or player UUID
- mode: DEFEND | PRODUCE | ATTACK(target BlockPos)
- garrison roster (entity UUIDs), respawn timer
- stockpile (ItemStacks), supply target (BlockPos or MineColonies colony id)
- raid clock

Persisted in a SavedData per dimension.

## Attaching to other mods' structures

Config `stormhold-holdings.json` maps structure ids or tags to a holding template:

    { "structure": "dungeons_arise:keep_kayra", "tier": 3, "banner_offset": [0, 12, 0], "lord_offset": [0, 10, 0] }

On structure placement (StructureProcessor hook or chunk-load scan for structure starts) Stormhold creates a Holding over the structure's bounding box, spawns the garrison, and clears vanilla-mod mobs inside the bounds so the knights are the threat.

## Garrison

| Role | Count (tier 1/2/3) | Gear | Notes |
|---|---|---|---|
| Lord | 1/1/1 | netherite, house color cape | boss. 200 HP, shield bash, calls reinforcements at 50% |
| Captain | 0/1/2 | diamond | buffs nearby knights |
| Knight | 4/12/30 | iron to diamond by tier, shield | holds formation, pushes with shield |
| Archer | 2/8/20 | bow, leather | wall posts, shoots down |
| Crossbowman | 0/4/10 | crossbow, chain | gatehouse, high damage |

Knights do not wander outside bounds unless chasing. Alarm state: once any guard sees the player, the whole garrison goes hostile for 2 minutes and the gate closes.

## Capture

1. Lord dead (tracked by Holding).
2. Player right-clicks banner point with a Stormhold Banner (crafted: wool + stick + house dye of the player's chosen color).
3. Capture timer 90 seconds. Counterattack wave spawns at the gate: 50% of original garrison. Player must survive inside bounds. Leaving bounds pauses the timer.
4. On success: owner = player. Garrison respawns as player-owned Recruits units. Banners in the structure swap color. War table spawns next to the banner point.

## War table

Right-click opens a screen: list of owned holdings, each with mode toggle, garrison count, stockpile, supply target, next raid ETA. Attack mode takes coordinates and sends a war party (60% of garrison) that walks there, fights for 5 minutes, and returns.

## Supply lines

Produce mode: stockpile gains tier-based goods every in-game day (iron, gold, bread, arrows, emeralds). Courier spawns with the goods and walks to the supply target. Couriers can be killed and drop their cargo. Delivery goes into the target chest. MineColonies hook: if target is inside a colony, deliver to its warehouse via the colony API.

## Raids

Each rival house has a hostility clock per player-owned holding. Every 3 to 5 in-game days, a war party sized 80% of the target's garrison spawns 100 blocks away and attacks. If the banner point is held by raiders for 90 seconds, the holding flips back to the house. Player gets a chat warning one in-game day before.

## Out of scope

Multiplayer balance, AI-vs-AI wars between houses, mod compatibility beyond Recruits, Siege Weapons, and MineColonies.
