"""Test doubles: a tiny in-memory world behind the bridge's HTTP API, and a scripted LLM endpoint."""
from __future__ import annotations

import asyncio
import json
import math
import time
from typing import Any

import httpx

TOKEN = "test-token"


class FakeWorld:
    """One agent standing near a three-log tree; a player named Steve nearby. Follows the mod's JSON shapes."""

    def __init__(self) -> None:
        self.agents: dict[str, list[float]] = {}
        self.logs = {(3, 64, 3), (3, 65, 3), (3, 66, 3)}
        self.drops: dict[int, tuple[int, int, int]] = {}
        self.inventory: dict[str, int] = {}
        self.chat: list[str] = []
        self.actions: list[tuple[str, dict[str, Any]]] = []
        self.events: list[dict[str, Any]] = []
        self.stops = 0
        self.action_delay = 0.0
        self._next_id = 100

    # ---- HTTP plumbing ----

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self._handle)

    async def _handle(self, request: httpx.Request) -> httpx.Response:
        if request.headers.get("authorization") != f"Bearer {TOKEN}":
            return httpx.Response(401, json={"error": "missing or wrong bearer token"})
        parts = request.url.path.strip("/").split("/")[1:]  # drop "v1"
        body = json.loads(request.content) if request.content else {}
        method = request.method
        if parts == ["health"]:
            return self._ok({"ok": True, "mod_version": "test"})
        if parts == ["events"]:
            after = int(request.url.params.get("after", 0))
            if not any(e["id"] > after for e in self.events):
                await asyncio.sleep(0.02)  # a (very) short long-poll
            new = [e for e in self.events if e["id"] > after]
            return self._ok({"events": new, "last_id": self.events[-1]["id"] if self.events else 0})
        if parts == ["recipes"]:
            return self._ok({"item": request.url.params["item"], "crafting": [{
                "output": {"id": "minecraft:planks", "name": "Oak Wood Planks", "count": 4},
                "totals": {"Oak Wood": 1}, "needs_crafting_table": False}], "smelting": []})
        if parts == ["agents"] and method == "GET":
            return self._ok({"agents": [{"name": n, "position": [int(v) for v in p], "dimension": 0, "health": 20,
                                         "food": 20, "dead": False, "busy": False} for n, p in self.agents.items()]})
        if parts == ["agents"] and method == "POST":
            self.agents[body["name"]] = [0.5, 64.0, 0.5]
            return self._ok({"name": body["name"]})
        if len(parts) >= 2 and parts[0] == "agents":
            name = parts[1]
            if name not in self.agents:
                return httpx.Response(404, json={"error": f"No agent named {name} is spawned"})
            sub = parts[2] if len(parts) > 2 else ""
            if sub == "observe":
                return self._ok(self.observation(name))
            if sub == "act":
                await asyncio.sleep(self.action_delay)
                return self._act(name, body["action"], body.get("args", {}))
            if sub == "stop":
                self.stops += 1
                return self._ok({"ok": True})
        return httpx.Response(404, json={"error": "unknown endpoint"})

    @staticmethod
    def _ok(data: Any) -> httpx.Response:
        return httpx.Response(200, json=data)

    def publish(self, type_: str, **data: Any) -> None:
        self.events.append({"id": len(self.events) + 1, "type": type_, "time_ms": int(time.time() * 1000), **data})

    # ---- the world ----

    def _eye_distance(self, name: str, block: tuple[int, int, int]) -> float:
        x, y, z = self.agents[name]
        return math.dist((x, y + 1.62, z), (block[0] + 0.5, block[1] + 0.5, block[2] + 0.5)) - 0.5

    def observation(self, name: str) -> dict[str, Any]:
        x, y, z = self.agents[name]
        blocks = []
        if self.logs:
            nearest = min(self.logs, key=lambda b: self._eye_distance(name, b))
            blocks.append({"id": "minecraft:log", "name": "Oak Wood", "visible_count": len(self.logs),
                           "nearest": {"pos": list(nearest), "distance": round(self._eye_distance(name, nearest), 1),
                                       "direction": "ahead", "compass": "south-east",
                                       "reachable": self._eye_distance(name, nearest) <= 4.5}})
        blocks.append({"id": "minecraft:grass", "name": "Grass Block", "visible_count": 40,
                       "nearest": {"pos": [int(x), 63, int(z)], "distance": 1.7, "direction": "ahead",
                                   "compass": "south", "reachable": True}})
        entities = [{"entity_id": 7, "type": "minecraft:player", "name": "Steve", "pos": [1.5, 64.0, -1.5],
                     "distance": 2.0, "direction": "behind", "compass": "north", "reachable": True, "player": True,
                     "health": 20.0}]
        for eid, pos in self.drops.items():
            entities.append({"entity_id": eid, "type": "minecraft:item", "name": "item.tile.log.oak",
                             "pos": [p + 0.5 for p in pos], "distance": 2.5, "direction": "ahead",
                             "compass": "south-east", "reachable": True,
                             "item": {"id": "minecraft:log", "name": "Oak Wood", "count": 1}})
        slots = [{"slot": i, "id": item, "name": "Oak Wood", "count": count}
                 for i, (item, count) in enumerate(self.inventory.items())]
        return {
            "agent": name, "tick": 1000,
            "self": {"name": name, "position": [x, y, z], "block_position": [int(x), int(y), int(z)],
                     "facing": "south", "health": 20.0, "max_health": 20.0, "food": 20, "saturation": 5.0,
                     "armor": 0, "xp_level": 0, "gamemode": "survival", "on_ground": True, "in_water": False,
                     "effects": []},
            "inventory": {"selected_slot": 0, "main_hand": None, "off_hand": None, "slots": slots,
                          "free_slots": 36 - len(slots), "armor": {}},
            "surroundings": {"standing_on": {"id": "minecraft:grass", "name": "Grass Block", "pos": [int(x), 63, int(z)]},
                             "light": 15, "sky_visible": True, "biome": "minecraft:forest"},
            "world": {"dimension": 0, "dimension_name": "overworld", "time_of_day": 1000, "day": 1, "phase": "day",
                      "weather": "clear"},
            "view": {"mode": "field of view 90 degrees ahead", "range": 32, "looking_at": None,
                     "blocks": blocks, "entities": entities},
            "container": None, "last_action": None, "heard": [], "recent_damage": [],
        }

    def _act(self, name: str, action: str, args: dict[str, Any]) -> httpx.Response:
        self.actions.append((action, args))
        status, message = "success", "ok"
        if action == "move_to":
            target = (args["x"], args["y"], args["z"])
            self.agents[name] = [target[0] + 0.5, float(target[1]), target[2] + 0.5]
            message = f"arrived at {target}"
        elif action == "mine":
            block = (args["x"], args["y"], args["z"])
            if block not in self.logs:
                status, message = "failure", f"there is no block at {block}"
            elif self._eye_distance(name, block) > 4.5:
                status, message = "failure", f"Oak Wood at {block} is too far; move closer first"
            else:
                self.logs.remove(block)
                self._next_id += 1
                self.drops[self._next_id] = block
                message = f"broke Oak Wood at {block}; pick up the drops with collect_items"
        elif action == "collect_items":
            for _ in self.drops:
                self.inventory["minecraft:log"] = self.inventory.get("minecraft:log", 0) + 1
            message = f"picked up {len(self.drops)} item stack(s)"
            if not self.drops:
                status, message = "failure", "no dropped items visible within 8 blocks"
            self.drops.clear()
        elif action == "chat":
            self.chat.append(args["message"])
            message = "said: " + args["message"]
        elif action == "drop":
            count = args.get("count") or self.inventory.get("minecraft:log", 0)
            self.inventory["minecraft:log"] = self.inventory.get("minecraft:log", 0) - count
            message = f"dropped {count} x Oak Wood"
        return self._ok({"id": len(self.actions), "action": action, "status": status, "message": message,
                         "ticks": 5, "data": {}})


class ScriptedLLM:
    """OpenAI-style chat endpoint. Routes by system prompt to the cortex, brain or judge script."""

    def __init__(self, brain: list[str], judge: list[str] | None = None, cortex: str = "A small oak tree is ahead.",
                 fail_first: dict[str, int] | None = None):
        self.brain = list(brain)
        self.judge = list(judge or [])
        self.cortex = cortex
        self.fail_first = dict(fail_first or {})  # model -> number of 429s to return first
        self.calls: list[tuple[str, str, list[dict[str, str]]]] = []

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self._handle)

    def _handle(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        model = body["model"]
        if self.fail_first.get(model, 0) > 0:
            self.fail_first[model] -= 1
            return httpx.Response(429, json={"error": {"message": "rate limited"}})
        system = body["messages"][0]["content"]
        role = "cortex" if system.startswith("You are the visual cortex") else \
            "judge" if system.startswith("You check whether") else "brain"
        self.calls.append((role, model, body["messages"]))
        if role == "cortex":
            text = self.cortex
        elif role == "judge":
            text = self.judge.pop(0) if self.judge else '{"done": true, "reason": "looks done"}'
        else:
            text = self.brain.pop(0) if self.brain else '{"action": "give_up", "args": {"reason": "script ended"}}'
        return httpx.Response(200, json={"choices": [{"message": {"content": text}}]})

    def calls_for(self, role: str) -> list[list[dict[str, str]]]:
        return [messages for r, _, messages in self.calls if r == role]


def decision(action: str, thought: str = "", **args: Any) -> str:
    return json.dumps({"thought": thought, "action": action, "args": args})

