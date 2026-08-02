# Experimental multiplayer: eight Keepers and a Hero Commander

This branch raises the network capacity to nine human users. The first eight
users control Keepers; the ninth user controls the existing Good/hero faction.
It is protocol-incompatible with unmodified KeeperFX clients.

## Slot mapping

| Network user | Game player | Role | Default colour |
| ---: | --- | --- | --- |
| 1 | `PLAYER0` | Keeper | Red |
| 2 | `PLAYER1` | Keeper | Blue |
| 3 | `PLAYER2` | Keeper | Green |
| 4 | `PLAYER3` | Keeper | Yellow |
| 5 | `PLAYER4` | Keeper | Purple |
| 6 | `PLAYER5` | Keeper | Black |
| 7 | `PLAYER6` | Keeper | Orange |
| 8 | `PLAYER7` | Keeper | Cyan |
| 9 | `PLAYER_GOOD` | Hero Commander | White |

`PLAYER_NEUTRAL` remains simulation-only and never consumes a network slot.
The Hero Commander role is fixed to network user 9; it is not a Keeper slot
or a selectable role.

## Supported match shapes

- With eight users, all eight are Keepers. Use the alliance grid for a 4v4.
- With nine users, users 1-8 are Keepers and user 9 is the Hero Commander.
  Leave the hero unallied with the Keepers for an 8v1, or ally it as required
  by a custom scenario.
- Matches with fewer users retain their normal Keeper slots. `PLAYER_GOOD`
  remains computer-controlled unless network user 9 is present.

## Hero Commander behaviour

The Hero Commander has no Dungeon Heart, rooms, or normal Keeper economy. The
human primarily uses the free Possession power to directly control any creature
owned by `PLAYER_GOOD`; other Good creatures continue to use their normal
creature AI.

The hero side remains alive while it owns at least one active creature. It
loses when the last Good creature dies. Keepers still lose when their last
Dungeon Heart is destroyed. Automatic multiplayer victory checks ignore living
allies and only wait for enemy factions, which allows team games to finish.

## Map requirements

An eight-Keeper map must provide a valid start and Dungeon Heart for all of:

`PLAYER0`, `PLAYER1`, `PLAYER2`, `PLAYER3`, `PLAYER4`, `PLAYER5`, `PLAYER6`,
and `PLAYER7`.

An 8v1 map must also give `PLAYER_GOOD` at least one creature. The creature may
be present in the map data or be created unconditionally during the first
script tick, for example:

```text
ADD_CREATURE_TO_LEVEL(PLAYER_GOOD,AVATAR,1,1,10,0)
```

Here `1` must resolve to a valid map location according to the normal script
command rules. After the first script tick, the engine reveals the Good
starting party and centers the Hero Commander's camera on its first creature.
If no Good creature exists by the next turn, the hero side loses.

Existing `COMPUTER_PLAYER(PLAYER_GOOD,ROAMING)` commands are ignored only when
the Good player is controlled by network user 9. This prevents a map script
from replacing the human Hero Commander.

## Balance and validation

Eight ordinary Keepers against one ordinary creature is not balanced. An 8v1
map should supply the hero side with an appropriate party, levels, defensive
terrain, objectives, and/or scripted reinforcements. The engine supplies the
role and win/loss rules; the map supplies the asymmetrical balance.

The expanded mode still needs real soak tests with 8-9 machines. Compile-time
and syntax checks can catch capacity, mapping, and bounds errors, but cannot
prove latency, bandwidth, CPU performance, or long-session synchronization.
Replay and saved-game layouts also differ from upstream because the player and
packet tables are larger; incompatible files are rejected rather than loaded.
The cyan Keeper currently falls back to blue artwork where no cyan-specific
resource exists; its map and lobby colours remain distinct.
