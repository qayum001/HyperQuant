"""The body: the action catalog the brain chooses from, argument checking, and execution through the bridge.

The descriptions here are what the decision model reads, so they double as the documentation of each move.
The game-side implementations live in the mod (mod/.../body/*Actions.java).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .bridge import Bridge, BridgeError


@dataclass(frozen=True)
class Param:
    name: str
    type: str  # "int", "number", "bool", "string"
    description: str
    required: bool = False


@dataclass(frozen=True)
class ActionSpec:
    name: str
    description: str
    params: tuple[Param, ...] = ()
    # At least one of these groups must be fully present (e.g. coordinates OR an entity id).
    one_of: tuple[tuple[str, ...], ...] = ()
    harness_only: bool = False


def _xyz(what: str) -> tuple[Param, ...]:
    return (Param("x", "int", f"{what} x"), Param("y", "int", f"{what} y"), Param("z", "int", f"{what} z"))


CATALOG: tuple[ActionSpec, ...] = (
    ActionSpec("move_to", "Walk (path finding, jumps up 1 block, drops up to 3) to a block position where your feet "
               "should end up, or to an entity. Up to ~90 blocks per call. A solid target such as a log or a chest "
               "makes you stop next to it.",
               _xyz("target") + (Param("entity_id", "int", "walk to this entity instead"),
                                 Param("range", "number", "stop within this many blocks (default 0, entities 2)"),
                                 Param("sprint", "bool", "sprint (uses more food)")),
               one_of=(("x", "y", "z"), ("entity_id",))),
    ActionSpec("collect_items", "Walk over dropped items you can see nearby so they are picked up. Use it after "
               "mining or killing something.",
               (Param("radius", "number", "search radius, default 8, max 16"), Param("max", "int", "stop after this many stacks"))),
    ActionSpec("mine", "Break one block you can see within reach (about 4.5 blocks from your eyes). Takes as long as "
               "it would for a player; the best tool on the hotbar is selected automatically. Drops must then be "
               "picked up with collect_items.",
               _xyz("block") + (Param("auto_tool", "bool", "pick the best hotbar tool (default true)"),),
               one_of=(("x", "y", "z"),)),
    ActionSpec("place", "Place a block into an empty (air, water, grass) position that touches a solid block you can "
               "see within reach.",
               _xyz("empty position") + (Param("item", "string", "block item to place, e.g. minecraft:cobblestone; "
                                                                 "default: what is in your hand"),),
               one_of=(("x", "y", "z"),)),
    ActionSpec("use_block", "Right-click a block within reach: open a chest, crafting table, furnace or modded "
               "machine; open a door; press a button; use the held item on it (bucket, seeds, bone meal, hoe...).",
               _xyz("block") + (Param("face", "string", "side to click: up, down, north, south, east, west"),),
               one_of=(("x", "y", "z"),)),
    ActionSpec("use_item", "Right-click with the held item without aiming at a block: eat or drink (waits until "
               "finished), throw, draw and release a bow.",
               (Param("hold_ticks", "int", "release after this many ticks (bow: 20); default: until done"),)),
    ActionSpec("equip", "Hold an item in a hand or put on armor. Moves it to the hotbar if needed.",
               (Param("item", "string", "item id like minecraft:iron_pickaxe, or its display name", True),
                Param("slot", "string", "mainhand (default), offhand, head, chest, legs or feet"))),
    ActionSpec("select_slot", "Select a hotbar slot.", (Param("slot", "int", "0-8", True),)),
    ActionSpec("drop", "Throw items out of the inventory. To give items to a player, aim at them with toward_entity.",
               (Param("item", "string", "which item"), Param("slot", "int", "or which inventory slot (0-35)"),
                Param("count", "int", "how many (default: all of them)"),
                Param("toward_entity", "int", "entity id of the player to throw them to")),
               one_of=(("item",), ("slot",))),
    ActionSpec("attack", "Hit an entity within reach (3 blocks), waiting for the attack cooldown between hits.",
               (Param("entity_id", "int", "target", True), Param("times", "int", "number of hits (default 1)"))),
    ActionSpec("interact_entity", "Right-click an entity within reach: trade with a villager, feed or breed an "
               "animal, shear a sheep, ride a horse...",
               (Param("entity_id", "int", "target", True),)),
    ActionSpec("look", "Turn to face a position, an entity, or a direction (yaw 0 = south, 90 = west, 180 = north, "
               "-90 = east; pitch -90 = up, 90 = down). What you see next depends on where you look.",
               _xyz("position") + (Param("entity_id", "int", "entity to face"), Param("yaw", "number", "yaw"),
                                   Param("pitch", "number", "pitch")),
               one_of=(("x", "y", "z"), ("entity_id",), ("yaw",))),
    ActionSpec("craft", "Craft an item by filling the open crafting grid from your inventory: the 2x2 grid of your "
               "inventory when nothing is open, or a crafting table's 3x3 grid (use_block the table first).",
               (Param("item", "string", "what to craft, e.g. minecraft:planks or minecraft:crafting_table", True),
                Param("count", "int", "how many items you want (default 1)"))),
    ActionSpec("transfer", "Move items between two slots of the open container (chest, furnace, machine). Slot "
               "numbers are the ones listed for the open container.",
               (Param("from", "int", "source slot", True), Param("to", "int", "target slot", True),
                Param("count", "int", "how many (default: the whole stack)"))),
    ActionSpec("quick_move", "Shift-click a slot of the open container (moves the stack between the container and "
               "your inventory).", (Param("slot", "int", "slot number", True),)),
    ActionSpec("container_click", "Raw click on a container slot, for unusual GUIs. mode: pickup (default), "
               "quick_move, swap (button = hotbar slot), throw, pickup_all.",
               (Param("slot", "int", "slot number, -999 = outside the window", True),
                Param("button", "int", "0 = left, 1 = right (default 0)"), Param("mode", "string", "click type"))),
    ActionSpec("close_container", "Close the open container screen."),
    ActionSpec("chat", "Say something in chat, or whisper to one player.",
               (Param("message", "string", "text", True), Param("to", "string", "player name to whisper to"))),
    ActionSpec("wait", "Do nothing for a while (20 ticks = 1 second), e.g. while a furnace smelts.",
               (Param("ticks", "int", "1-600 (default 20)"),)),
    ActionSpec("lookup_recipe", "Look up how an item is crafted or smelted (like checking JEI). Costs no game time.",
               (Param("item", "string", "item id or display name", True),), harness_only=True),
    ActionSpec("done", "The task is complete. A checker will verify it against what you see.",
               (Param("summary", "string", "what you did, for the player who asked", True),), harness_only=True),
    ActionSpec("give_up", "The task cannot be done (explain why).",
               (Param("reason", "string", "why", True),), harness_only=True),
)

SPECS: dict[str, ActionSpec] = {spec.name: spec for spec in CATALOG}


class InvalidAction(ValueError):
    pass


def validate(action: str, args: Any) -> dict[str, Any]:
    """Checks a decision against the catalog and coerces argument types. Raises InvalidAction with a reason."""
    spec = SPECS.get(action)
    if spec is None:
        raise InvalidAction(f"unknown action '{action}'; choose one of: {', '.join(SPECS)}")
    if args is None:
        args = {}
    if not isinstance(args, dict):
        raise InvalidAction("args must be a JSON object")
    known = {p.name: p for p in spec.params}
    clean: dict[str, Any] = {}
    for name, value in args.items():
        param = known.get(name)
        if param is None or value is None:
            continue  # ignore extras rather than failing the whole step
        clean[name] = _coerce(action, param, value)
    missing = [p.name for p in spec.params if p.required and p.name not in clean]
    if missing:
        raise InvalidAction(f"{action} needs {', '.join(missing)}")
    if spec.one_of and not any(all(k in clean for k in group) for group in spec.one_of):
        options = " or ".join("+".join(group) for group in spec.one_of)
        raise InvalidAction(f"{action} needs {options}")
    return clean


def _coerce(action: str, param: Param, value: Any) -> Any:
    try:
        if param.type == "int":
            if isinstance(value, bool):
                raise ValueError
            return int(float(value)) if not isinstance(value, int) else value
        if param.type == "number":
            return float(value)
        if param.type == "bool":
            if isinstance(value, str):
                return value.strip().lower() in ("true", "yes", "1")
            return bool(value)
        return str(value)
    except (TypeError, ValueError):
        raise InvalidAction(f"{action}: '{param.name}' must be a {param.type}, got {value!r}") from None


def catalog_text() -> str:
    """The action list as shown to the decision model."""
    lines = []
    for spec in CATALOG:
        params = ", ".join(f"{p.name}{'' if p.required else '?'}: {p.type}" for p in spec.params)
        lines.append(f"- {spec.name}({params}): {spec.description}")
        for p in spec.params:
            if p.description and p.name not in ("x", "y", "z"):
                lines.append(f"    {p.name}: {p.description}")
    return "\n".join(lines)


def compact_catalog_text() -> str:
    """One signature per action, e.g. "mine(x, y, z, auto_tool?)"; alternatives are joined with "|"."""
    lines = []
    for spec in CATALOG:
        grouped = {name for group in spec.one_of for name in group}
        parts = []
        if spec.one_of:
            parts.append(" | ".join(", ".join(group) for group in spec.one_of))
        parts += [p.name + ("" if p.required else "?") for p in spec.params if p.name not in grouped]
        lines.append(f"{spec.name}({', '.join(parts)})")
    return "\n".join(lines)


@dataclass
class Outcome:
    status: str  # success | failure | timeout | cancelled | running | invalid
    message: str
    data: dict[str, Any] = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return self.status == "success"


class Body:
    def __init__(self, bridge: Bridge, action_timeout_s: float = 120.0):
        self._bridge = bridge
        self._timeout = action_timeout_s

    async def perform(self, agent: str, action: str, args: dict[str, Any]) -> Outcome:
        if action == "lookup_recipe":
            return await self._lookup_recipe(args["item"])
        try:
            result = await self._bridge.act(agent, action, args, self._timeout)
        except BridgeError as e:
            if e.status in (400, 404, 409):
                return Outcome("invalid", e.message)
            raise
        status = result.get("status", "failure")
        message = result.get("message", "")
        if status == "running":
            await self._bridge.stop(agent)
            message = f"still running after {self._timeout:.0f}s, stopped it"
            status = "timeout"
        return Outcome(status, message, result.get("data") or {})

    async def _lookup_recipe(self, item: str) -> Outcome:
        try:
            found = await self._bridge.recipes(item)
        except BridgeError as e:
            return Outcome("failure", e.message)
        lines = []
        for r in found.get("crafting", []):
            totals = ", ".join(f"{n} x{c}" for n, c in r.get("totals", {}).items())
            out = r["output"]
            where = "crafting table (3x3)" if r.get("needs_crafting_table") else "inventory grid (2x2) or table"
            lines.append(f"craft {out['count']} x {out['name']} ({out['id']}) from {totals} using the {where}")
        for s in found.get("smelting", []):
            lines.append(f"smelt {s['input']['name']} ({s['input']['id']}) in a furnace -> {s['output']['name']}")
        if not lines:
            return Outcome("failure", f"no crafting or smelting recipe produces '{item}' (check the id or name)")
        return Outcome("success", "; ".join(lines), found)
