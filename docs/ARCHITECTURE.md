# Architecture

Agents that play a modded Minecraft 1.12.2 world as ordinary players, controlled by LLMs, without keyboard or
mouse emulation and without running inside anyone's game client.

```
 Player's game client ── chat: "@Bob bring me 16 oak logs" ──┐
                                                             ▼
 ┌──────────────────────── Minecraft server (Forge 1.12.2 + your modpack) ───────────────────────┐
 │  AgentBridge mod (mod/)                                                                       │
 │    Avatar   Bob is a real EntityPlayerMP, logged in without a client                          │
 │    Eyes     ray-grid vision + body senses + chat  ──► observation JSON                        │
 │    Body     19 player-sized actions, run through vanilla player code                          │
 │    API      HTTP + JSON on 127.0.0.1:8765, bearer token                                       │
 └──────────────────────────────────────────▲────────────────────────────────────────────────────┘
                                            │ events (task requests), observe, act
 ┌──────────────────────────────────────────┴───── Harness (harness/, separate Python process) ───┐
 │  0 request ─► 1 eyes ─► 2 visual cortex (LLM) ─► 3 brain (LLM) ─► 4 body ─► 5 done? ─┐         │
 │                 ▲                                                                   │ no      │
 │                 └───────────────────────────────────────────────────────────────────┘         │
 └────────────────────────────────── OpenRouter, Ollama or any OpenAI-compatible endpoint ───────┘
```

The game-side part is deliberately thin and dumb: it senses and it acts, with the same limits a player has.
All the thinking happens in the harness, a separate process that can be restarted, swapped for another
language, or moved to another machine without touching the game.

## Question 1: how does the agent gather what it sees?

### Options considered

| Approach | Verdict |
|---|---|
| Screenshots + a vision model | Rejected (also by you). Needs a rendering client per agent, slow and expensive, and vision models are poor at reading Minecraft geometry and exact coordinates. |
| Dump every block around the agent | Rejected. Omniscient: it sees ores through stone and chests behind walls, and it costs a huge number of tokens. |
| Read the world from a client-side mod | Rejected. Ties the agent to a running game client, which is what we want to avoid. |
| **Server-side "semantic vision": ray-cast from the avatar's eyes** | **Chosen.** Cheap, exact, and limited to what a player could actually see. |

### How the eyes work (`mod/.../eyes/Vision.java`, `Observer.java`)

The mod casts a grid of rays from the avatar's eye position across its field of view: by default 64 × 40 rays
over 90° × 70°, up to 32 blocks. Think of a tiny camera whose pixels are block ids instead of colours. Each ray
walks voxel by voxel (Amanatides & Woo) and records every non-air block it reaches:

* opaque blocks stop the ray, so walls hide what is behind them and **ores inside stone stay invisible**;
* glass, plants, fences and similar see-through blocks are recorded but let the ray continue a little;
* water dims sight (a few blocks), unloaded chunks end the ray;
* darkness matters: a surface in pitch black (light 0, not glowing) further than 5 blocks is not seen, so caves
  need torches, unless the agent has Night Vision.

Entities are seen when they are within range, inside the field of view and in line of sight (and not invisible,
not hidden in darkness). Seen blocks are grouped by type with the nearest position, a few more positions, the
distance, the direction relative to where the agent looks ("ahead-left") and the compass direction, and whether
it is within reach.

A **panorama** look casts the same grid through 360°, which stands for the agent turning its head; the harness
uses it on the first step of a task and every few steps after that.

Besides sight the observation contains what a player knows from their HUD and senses:

| Sense | Content |
|---|---|
| body | position, facing, health, food, saturation, armor, air, XP, effects, burning / in water / in lava, game mode |
| hands and inventory | held items, all 36 slots, armor, the item on the mouse cursor |
| surroundings | block underfoot, light level, whether the sky is visible, biome |
| world | dimension, time of day, day number, weather |
| open screen | every slot of an open chest, furnace, crafting table or modded machine, with slot numbers |
| hearing | recent chat and server messages that reached the agent ("You can't break blocks here", other players) |
| pain | recent damage: type, attacker and entity id, health lost |
| last action | the status and message of the body's last action |

Nothing outside those senses is exposed, so the agent has to explore, remember and look around like a player.
The full schema is in [PROTOCOL.md](PROTOCOL.md).

### From raw data to readable text: the visual cortex

In the harness, `cortex.py` turns the raw JSON into text in two ways:

1. **facts**: a deterministic rendering (`render_facts`) of every exact coordinate, id, entity id and count;
2. **description**: a short natural-language summary written by a cheap LLM (your "brain part that handles
   signals from the eyes"), ordered by what matters for the current task and by threats.

The decision model gets both: the description to understand the scene and the facts to copy numbers from.
Small free models summarise well but sometimes garble numbers, and one wrong coordinate wastes a whole step.
If the cortex model fails or is rate-limited the facts are used alone. `[cortex] mode = "rules"` skips the LLM
entirely, which is cheaper and often just as good for strong decision models. The description is cached while
the observation does not change.

Example of the facts text:

```
POSITION (0.5, 64.0, 0.5), block (0, 64, 0), facing south in overworld; standing on Grass Block; biome minecraft:forest; light 15, sky visible.
STATUS health 20.0/20.0, food 20/20, armor 0, xp level 0, survival.
HANDS main: empty (hotbar slot 0); off: empty.
INVENTORY (35 of 36 slots free): [0] Oak Wood x3 (minecraft:log).
VIEW (field of view 90 degrees ahead, up to 32 blocks):
- Oak Wood (minecraft:log) x3 visible; nearest (3, 65, 3) 3.7m ahead (south-east) REACHABLE
- Grass Block (minecraft:grass) x40 visible; nearest (0, 63, 0) 1.7m ahead (south) REACHABLE
ENTITIES:
- entity_id 7: Steve (minecraft:player) at (1.5, 64.0, -1.5), 2.0m behind REACHABLE [player]; health 20.0
```

## Question 2: how does the agent interact with the world?

### Options considered

| Approach | Verdict |
|---|---|
| Emulate keyboard and mouse in a real client | Rejected by requirement; also brittle and needs a client per agent. |
| A headless bot client (e.g. Mineflayer) connecting over the network | Works on vanilla, but on Forge 1.12.2 it has to fake the FML handshake and the bot library does not know modded blocks, items, GUIs or recipes, which is exactly what your modpack is about. |
| Commands (`/setblock`, `/give`, `/tp`) | Rejected. That is cheating, not playing. |
| **A server-side avatar driven through the same code a real player's actions go through** | **Chosen.** |

### The avatar (`mod/.../agent/AgentPlayer.java`, `AgentManager.java`)

Each agent is a genuine `EntityPlayerMP`, logged in through `PlayerList.initializeConnectionToPlayer`, the call
that completes a real player's login. As a result:

* it is in the tab list, other players see it walk, swing and get hurt, and its data is saved in `playerdata/`
  like anyone else's (inventory and position survive restarts);
* other mods see a normal player: `PlayerLoggedInEvent` fires, claim and protection mods apply, permissions are
  those of a non-op player;
* it has an offline-mode UUID derived from its name and the default skin.

There is no game client on the other end. Its network connection is an in-memory Netty `EmbeddedChannel`:
packets meant for a client are dropped, except chat, which is decoded and becomes what the agent heard. A real
client computes its own movement and sends positions; the agent has none, so the avatar runs the vanilla player
physics on the server instead (`onUpdateEntity`), and actions only set movement intents: forward, strafe, jump,
sneak, sprint, exactly what holding keys does. Gravity, collisions, step height, swimming, fall damage
(re-enabled for agents because vanilla expects the client to report falls), hunger and knockback all come from
vanilla code. When an agent dies it drops its items, waits on the "death screen" for two seconds and respawns at
its bed or the world spawn.

### The body: actions (`mod/.../body/`)

One action is one thing a player would do in a few seconds. It runs over several server ticks and ends with a
status (`success`, `failure`, `timeout`, `cancelled`) and a message written for the brain, for example
`"Oak Wood at (3, 66, 3) is 5.2 blocks away but reach is 4.5; move closer first"`.

| Action | What it does | How it stays fair |
|---|---|---|
| `move_to` | Walks to a position or an entity: A* over walk, 1-block jump, drops of up to 3, swimming; diagonals without corner cutting; re-plans when stuck | Real physics and speed; avoids lava, fire, cactus and cobwebs; can't fly or teleport |
| `collect_items` | Walks over visible dropped items so they are picked up | Normal item pickup |
| `look` | Turns to a point, an entity or a yaw/pitch | |
| `mine` | Holds left click on a block until it breaks; picks the best hotbar tool | Must see the block within reach (4.5); break time from `getPlayerRelativeBlockHardness` (tool, enchantments, on ground, in water, Haste and Mining Fatigue); goes through `PlayerInteractionManager`, so `BreakEvent` and protections apply |
| `place` | Right-clicks a visible face of a neighbouring block with a block in hand (sneaking, so chests are not opened) | Reach + line of sight; `processRightClickBlock`; spawn protection and the world border |
| `use_block` | Right-clicks a block: chests, tables, furnaces, modded machines, doors, levers, buckets, seeds... | Same order as the vanilla client: the block, then the held item, for each hand |
| `use_item` | Right-click in the air: eat or drink (waits until done), throw, bow | Item use duration and slowdown are vanilla |
| `equip`, `select_slot`, `drop` | Hotbar selection, number-key swaps, armor, throwing items (also toward a player to hand them over) | Inventory clicks a player could make |
| `attack`, `interact_entity` | Hit an entity (waits for the attack cooldown) or right-click it | Entity reach 3; line of sight |
| `container_click`, `transfer`, `quick_move`, `close_container` | Work inside any open screen by slot number | `Container.slotClick`, the same method a client's click packet calls |
| `craft` | Fills the open crafting grid from the inventory and takes the result, one craft per tick | Needs a crafting table for 3x3 recipes; uses the registered recipes, modded ones included |
| `chat` | Says something or whispers | `ServerChatEvent` fires, rate limited, `/commands` off by default and never with op rights |
| `wait` | Does nothing for a while | |

Every check that the server does for a real player's packets is done too, and some are stricter: the server
trusts that an honest client aimed at a block, whereas the agent must actually have line of sight to it.

Two conveniences are worth naming honestly. `move_to` plans its route with A* over the real terrain near the
agent, including some cells the agent has not looked at. That information is used only for walking, the way a
player's spatial sense is, and is never reported to the brain. `craft` and `lookup_recipe` use the recipe
registry, the same knowledge JEI gives a player.

## The agentic pipeline (`harness/hyperquant_agents/agent.py`)

| Step | Your name | Module | What happens |
|---|---|---|---|
| 0 | request | `app.py` `Supervisor` | A player writes `@Bob <task>` in chat or `/agent task Bob <task>`; the mod publishes an event; the harness queues a task for that agent |
| 1 | eyes | `eyes.py` | `GET /observe` (panorama on the first step and every 6th step) |
| 2 | brain, sensory part | `cortex.py` | Raw JSON → description (cheap LLM) + exact facts (deterministic) |
| 3 | brain, decision part | `brain.py` `Brain` | Task + perception + notes + recent steps → one action as JSON, validated against the catalog |
| 4 | body | `body.py` | `POST /act`, wait for the result, store it in short-term memory |
| 5 | check | `brain.py` `Judge` | The brain declares `done` itself after looking at the latest observation; the claim is then verified by a separate call against fresh data. If the check rejects it, the reason is fed back and the loop continues |

Checking after every action would double the number of model calls. Instead the brain re-evaluates the task
every turn from a fresh observation, and only an explicit `done` claim pays for a second opinion.

Things that keep the loop healthy:

* **Short-term memory**: the last 12 steps with results, plus notes the brain chooses to keep ("chest with iron
  at (10, 64, -3)").
* **Failure feedback**: an action that fails is shown with the reason; after the same failure twice in a row the
  brain is told to change approach.
* **Unusable replies** (prose, broken JSON, unknown actions, missing arguments) are sent back to the model with
  the exact problem, twice at most.
* **Budgets**: 80 actions and 30 minutes per task by default; repeated model outages end the task.
* **Interruptions**: `@Bob stop` or `/agent stop Bob` cancels the running action in the game immediately, and
  the harness ends the task.
* **Death**: noticed in the observation; the agent waits for its respawn and is told what happened.
* **Traces**: every observation, perception, decision and outcome goes into `traces/*.jsonl`, which is the place
  to look when tuning prompts.

## Models and cost

Any OpenAI-compatible endpoint works. OpenRouter is the default and each role takes a list of models, tried in
order, as fallbacks. One step costs one decision call plus at most one cortex call (cached when nothing
changed), and a `done` claim adds a check. A 40-step task is therefore roughly 50–90 calls.

Free OpenRouter models have tight rate limits: per-minute limits plus a small daily cap that grows if the
account has bought credits. Check the current limits on OpenRouter. Free models are fine for trying this out,
but real modpack chores will hit the cap. Options, cheapest first:

* `[cortex] mode = "rules"` to drop the cortex calls;
* a local model through Ollama or LM Studio (`base_url = "http://localhost:11434/v1"`);
* a cheap paid model for the brain and a free one for the cortex.

## Security

* The API listens on `127.0.0.1` and requires a random bearer token generated on first start.
* Agents are non-op players. `/commands` from agents are disabled unless `allowCommands` is set, and even then
  they run with the agent's own permissions.
* Spawning agents needs op level 2 by default; giving tasks is open to everyone (both configurable).

## Known gaps

* Path finding does not plan ladders, vines, boats, parkour or long swims, and there is no pillaring or
  bridging; the brain has to place blocks itself.
* Portals and the End exit: an agent that changes dimension keeps working, but the End credits screen, which
  needs a client, is not handled.
* Smelting and machines are driven step by step (`transfer`, `wait`); there is no single "smelt" action yet.
* Modded GUIs that do not use standard container slots (pure button GUIs) can't be operated.
* Agents keep chunks around them loaded, like players do.
* The mod was compiled here against stubs generated from the official MCP `stable_39` mappings and the real
  Netty, Gson and Guava jars, because the Forge Maven was unreachable. Names, signatures, checked exceptions and
  client-only methods were all checked, but it has not been run in a game yet. See the README for the first-run
  checklist.
