# AgentBridge HTTP API

The mod serves JSON over HTTP on `http://127.0.0.1:8765/v1/` (address and port in `config/agentbridge.cfg`).
Every request needs `Authorization: Bearer <token>`, where the token is the `token` value from that config file
(generated on the first start). Errors come back as `{"error": "..."}` with status 400 (bad arguments), 401,
404 (unknown agent or endpoint), 409 (conflict, e.g. the agent is dead or already spawned) or 503 (server busy).

Everything that touches the world runs on the server thread at the end of a tick, so each call takes at least
one tick (50 ms).

## Endpoints

| Method and path | Purpose |
|---|---|
| `GET /v1/health` | `{"ok": true, "mod_version", "minecraft", "actions": [...]}` |
| `GET /v1/agents` | `{"agents": [summary...]}` |
| `POST /v1/agents` | Spawn: `{"name": "Bob", "near_player": "Steve"}` or `{"name", "x", "y", "z", "dimension"}`. A returning agent appears where it logged out. Returns a summary. |
| `GET /v1/agents/{name}` | Summary: `name, position, dimension, health, food, dead, busy, action` |
| `DELETE /v1/agents/{name}` | Log the agent out (its data is saved) |
| `GET /v1/agents/{name}/observe?panorama=true` | Observation, see below |
| `POST /v1/agents/{name}/act` | Run an action: `{"action": "mine", "args": {...}, "wait": true, "timeout_ms": 120000}` |
| `GET /v1/agents/{name}/action` | Status of the current or last action |
| `POST /v1/agents/{name}/stop` | Cancel the current action (also publishes a `stop` event) |
| `GET /v1/events?after=<id>&wait_ms=25000` | Long poll: `{"events": [...], "last_id": n}` |
| `GET /v1/recipes?item=minecraft:torch&limit=5` | Crafting and smelting recipes that produce the item |

Starting an action cancels the one in progress. With `"wait": true` the call returns when the action ends, or
after `timeout_ms` with `"status": "running"`.

## Action result

```json
{
  "id": 42,
  "action": "mine",
  "status": "success",
  "message": "broke Oak Wood at (3, 65, 3); pick up the drops with collect_items",
  "ticks": 61,
  "data": {"block": "Oak Wood", "tool": "Stone Axe", "drops_expected": true}
}
```

`status` is one of `running`, `success`, `failure`, `timeout`, `cancelled`. Messages are written for the
decision model: they say what went wrong and usually what to do about it.

## Actions

Arguments in brackets are optional. Coordinates are block coordinates (integers).

| Action | Arguments |
|---|---|
| `move_to` | `x, y, z` (where the feet should be) or `entity_id`; `[range]` (blocks, default 0, entities 2); `[sprint]` |
| `collect_items` | `[radius]` (default 8, max 16), `[max]` stacks |
| `look` | `x, y, z` or `entity_id` or `yaw, [pitch]` |
| `wait` | `[ticks]` 1–600 (default 20) |
| `mine` | `x, y, z`, `[auto_tool]` (default true) |
| `place` | `x, y, z` (an empty position), `[item]` |
| `use_block` | `x, y, z`, `[face]` (up, down, north, south, east, west) |
| `use_item` | `[hold_ticks]` (release after this long, e.g. 20 for a bow) |
| `equip` | `item`, `[slot]`: mainhand (default), offhand, head, chest, legs, feet |
| `select_slot` | `slot` 0–8 |
| `drop` | `item` or `slot` (0–35), `[count]` (default all), `[toward_entity]` |
| `attack` | `entity_id`, `[times]` |
| `interact_entity` | `entity_id` |
| `container_click` | `slot` (-999 = outside), `[button]` 0/1, `[mode]` pickup, quick_move, swap, throw, pickup_all |
| `transfer` | `from`, `to` (slots of the open container), `[count]` |
| `quick_move` | `slot` |
| `close_container` | none |
| `craft` | `item`, `[count]` |
| `chat` | `message`, `[to]` (player name: whisper) |

Items can be given as a registry id (`minecraft:log`), an id with metadata (`minecraft:log@2`), a bare path
(`log`) or a display name (`Birch Wood`).

## Observation

Abridged; fields that do not apply are omitted or `null`.

```json
{
  "agent": "Bob",
  "tick": 183022,
  "self": {
    "name": "Bob", "position": [12.5, 64.0, -3.5], "block_position": [12, 64, -4], "facing": "south",
    "yaw": 12, "pitch": 0, "health": 20.0, "max_health": 20.0, "food": 18, "saturation": 3.2, "armor": 0,
    "xp_level": 1, "gamemode": "survival", "on_ground": true, "in_water": false, "effects": []
  },
  "inventory": {
    "selected_slot": 0,
    "main_hand": {"id": "minecraft:stone_axe", "name": "Stone Axe", "count": 1, "durability": "118/132"},
    "off_hand": null,
    "slots": [{"slot": 0, "id": "minecraft:stone_axe", "name": "Stone Axe", "count": 1, "durability": "118/132"},
              {"slot": 1, "id": "minecraft:log", "name": "Oak Wood", "count": 5}],
    "free_slots": 34,
    "armor": {}
  },
  "surroundings": {
    "standing_on": {"id": "minecraft:grass", "name": "Grass Block", "pos": [12, 63, -4]},
    "light": 15, "sky_visible": true, "biome": "minecraft:forest"
  },
  "world": {"dimension": 0, "dimension_name": "overworld", "time_of_day": 6000, "day": 3, "phase": "day", "weather": "clear"},
  "view": {
    "mode": "field of view 90 degrees ahead",
    "range": 32,
    "looking_at": {"id": "minecraft:log", "name": "Oak Wood", "pos": [13, 65, -1], "face": "north", "distance": 3.1, "reachable": true},
    "blocks": [
      {"id": "minecraft:log", "name": "Oak Wood", "visible_count": 9,
       "nearest": {"pos": [13, 64, -1], "distance": 2.8, "direction": "ahead", "compass": "south", "reachable": true},
       "more": [[13, 65, -1], [13, 66, -1]]},
      {"id": "minecraft:leaves", "name": "Oak Leaves", "visible_count": 41,
       "nearest": {"pos": [12, 67, -1], "distance": 4.1, "direction": "ahead", "compass": "south", "reachable": true}}
    ],
    "entities": [
      {"entity_id": 214, "type": "minecraft:player", "name": "Steve", "pos": [10.2, 64.0, -6.0], "distance": 3.3,
       "direction": "behind-left", "compass": "north-east", "reachable": false, "player": true, "health": 20.0},
      {"entity_id": 377, "type": "minecraft:zombie", "name": "Zombie", "pos": [20.5, 64.0, 4.1], "distance": 10.9,
       "direction": "ahead-left", "compass": "south-east", "reachable": false, "hostile": true, "health": 20.0}
    ]
  },
  "container": null,
  "last_action": {"id": 41, "action": "move_to", "status": "success", "message": "arrived at (12, 64, -4)", "ticks": 48, "data": {}},
  "heard": [{"seconds_ago": 12, "text": "<Steve> @Bob bring me 16 oak logs"}],
  "recent_damage": []
}
```

When a screen is open, `container` lists every slot of the container itself (empty ones too) and the agent's own
non-empty slots:

```json
{
  "title": "Furnace", "type": "ContainerFurnace", "agent_inventory_slots": "3-38",
  "slots": [
    {"slot": 0, "owner": "container", "id": "minecraft:iron_ore", "name": "Iron Ore", "count": 4},
    {"slot": 1, "owner": "container", "kind": "SlotFurnaceFuel", "empty": true},
    {"slot": 2, "owner": "container", "kind": "SlotFurnaceOutput", "empty": true},
    {"slot": 30, "owner": "agent", "id": "minecraft:coal", "name": "Coal", "count": 7}
  ],
  "cursor": null
}
```

## Events

`GET /v1/events?after=<last id you saw>` waits up to `wait_ms` for new events and returns them in order.
Ids restart at 1 when the server restarts; `last_id` lower than your `after` means that happened. Every event
has `id`, `type`, `time_ms` and `agent`.

| Type | Extra fields | Published when |
|---|---|---|
| `task` | `from`, `text`, `source` (`chat` or `command`) | A player wrote `@Bob ...` or `/agent task Bob ...` |
| `stop` | `from` | `@Bob stop`, `/agent stop Bob`, or `POST /stop` (`from` is `the harness`) |
| `spawned`, `respawned`, `died`, `despawned` | `message` | Lifecycle changes (death message, kick reason...) |

If no harness has polled for 90 seconds, players asking an agent for something are told that its brain is
offline. The request stays queued in the event list.
