"""The visual cortex: turns the eyes' raw data into text the decision model can reason about.

Two renderings are produced for every observation:
  * facts - a deterministic, exact summary (coordinates, ids, counts). Always correct, never hallucinated.
  * text  - a short natural-language description written by a cheap LLM, prioritised for the current task.
The brain gets both: the description for understanding, the facts for exact numbers.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from . import prompts
from .config import CortexSettings
from .eyes import compact, fingerprint
from .llm import LLMClient, LLMError


@dataclass
class Perception:
    text: str
    facts: str
    raw: dict[str, Any]
    source: str  # "llm", "cache", "rules" or "rules (llm failed: ...)"


class Cortex:
    def __init__(self, llm: LLMClient | None, settings: CortexSettings):
        self._llm = llm
        self._settings = settings
        self._last_key: str | None = None
        self._last_text = ""

    async def perceive(self, raw: dict[str, Any], task: str) -> Perception:
        facts = render_facts(raw)
        if self._settings.mode == "rules" or self._llm is None:
            return Perception(facts, facts, raw, "rules")
        key = fingerprint(raw) + "\n" + task
        if key == self._last_key:
            return Perception(self._last_text, facts, raw, "cache")
        messages = [
            {"role": "system", "content": prompts.CORTEX_SYSTEM},
            {"role": "user", "content": prompts.cortex_user(task, json.dumps(compact(raw), separators=(",", ":")))},
        ]
        try:
            reply = await self._llm.chat(messages, self._settings)
        except LLMError as e:
            return Perception(facts, facts, raw, f"rules (llm failed: {e})")
        self._last_key, self._last_text = key, reply.text.strip()
        return Perception(self._last_text, facts, raw, "llm")


def _pos(p: Any) -> str:
    return "(" + ", ".join(str(v) for v in p) + ")" if isinstance(p, (list, tuple)) else str(p)


def _item(i: dict[str, Any]) -> str:
    meta = f"@{i['meta']}" if "meta" in i else ""
    extra = f", durability {i['durability']}" if "durability" in i else ""
    return f"{i.get('name', '?')} x{i.get('count', 1)} ({i.get('id', '?')}{meta}{extra})"


def render_facts(raw: dict[str, Any]) -> str:
    """Deterministic text rendering of an observation. Also used as the fallback perception."""
    s = raw.get("self", {})
    inv = raw.get("inventory", {})
    sur = raw.get("surroundings", {})
    world = raw.get("world", {})
    view = raw.get("view", {})
    lines: list[str] = []

    standing = sur.get("standing_on", {})
    lines.append(
        f"POSITION {_pos(s.get('position'))}, block {_pos(s.get('block_position'))}, facing {s.get('facing')} "
        f"in {world.get('dimension_name', '?')}; standing on {standing.get('name', '?')}; biome {sur.get('biome', '?')}; "
        f"light {sur.get('light', '?')}, sky {'visible' if sur.get('sky_visible') else 'not visible'}.")
    lines.append(
        f"TIME day {world.get('day')}, {world.get('phase')} (time {world.get('time_of_day')}), weather {world.get('weather')}.")
    status = (f"STATUS health {s.get('health')}/{s.get('max_health')}, food {s.get('food')}/20, armor {s.get('armor')}, "
              f"xp level {s.get('xp_level')}, {s.get('gamemode')}")
    for flag in ("in_water", "in_lava", "burning", "dead"):
        if s.get(flag):
            status += f", {flag.replace('_', ' ')}"
    if "air" in s:
        status += f", air {s['air']}/300"
    lines.append(status + ".")
    effects = s.get("effects") or []
    if effects:
        lines.append("EFFECTS " + ", ".join(f"{e['id']} {e['level']} ({e['seconds']}s)" for e in effects))

    main = inv.get("main_hand")
    off = inv.get("off_hand")
    lines.append(f"HANDS main: {_item(main) if main else 'empty'} (hotbar slot {inv.get('selected_slot')}); "
                 f"off: {_item(off) if off else 'empty'}.")
    slots = inv.get("slots") or []
    lines.append(f"INVENTORY ({inv.get('free_slots', '?')} of 36 slots free): "
                 + ("; ".join(f"[{i['slot']}] {_item(i)}" for i in slots) if slots else "empty") + ".")
    armor = inv.get("armor") or {}
    if armor:
        lines.append("WEARING " + ", ".join(f"{k}: {v.get('name')}" for k, v in armor.items()) + ".")
    if inv.get("cursor"):
        lines.append(f"HOLDING ON CURSOR {_item(inv['cursor'])} (put it somewhere).")

    looking = view.get("looking_at")
    if looking:
        lines.append(f"LOOKING AT {looking['name']} ({looking['id']}) at {_pos(looking['pos'])}, "
                     f"{looking['distance']} blocks, {'within' if looking['reachable'] else 'out of'} reach.")
    lines.append(f"VIEW ({view.get('mode', '?')}, up to {view.get('range')} blocks):")
    for b in view.get("blocks") or []:
        n = b["nearest"]
        meta = f"@{b['meta']}" if "meta" in b else ""
        more = "; also " + " ".join(_pos(p) for p in b["more"]) if b.get("more") else ""
        lines.append(f"- {b['name']} ({b['id']}{meta}) x{b['visible_count']} visible; nearest {_pos(n['pos'])} "
                     f"{n['distance']}m {n['direction']} ({n['compass']}){' REACHABLE' if n.get('reachable') else ''}{more}")
    if view.get("too_dark"):
        lines.append(f"! {view['too_dark']}")
    entities = view.get("entities") or []
    if entities:
        lines.append("ENTITIES:")
    for e in entities:
        what = f"dropped {_item(e['item'])}" if "item" in e else f"{e['name']} ({e['type']})"
        tags = [t for t in ("hostile", "player", "agent") if e.get(t)]
        extra = []
        if "health" in e:
            extra.append(f"health {e['health']}")
        if "holding" in e:
            extra.append(f"holding {e['holding']}")
        lines.append(f"- entity_id {e['entity_id']}: {what} at {_pos(e['pos'])}, {e['distance']}m {e['direction']}"
                     f"{' REACHABLE' if e.get('reachable') else ''}"
                     f"{' [' + ', '.join(tags) + ']' if tags else ''}{'; ' + ', '.join(extra) if extra else ''}")

    container = raw.get("container")
    if container:
        lines.append(f"OPEN CONTAINER: {container.get('title')} ({container.get('type')}); "
                     f"your inventory occupies slots {container.get('agent_inventory_slots', '?')}.")
        own = []
        for slot in container.get("slots", []):
            if slot.get("owner") == "agent":
                own.append(f"[{slot['slot']}] {_item(slot)}")
                continue
            kind = f" <{slot['kind']}>" if slot.get("kind") else ""
            content = "empty" if slot.get("empty") else _item(slot)
            lines.append(f"- slot {slot['slot']}{kind}: {content}")
        if own:
            lines.append("- your items in this screen: " + "; ".join(own))
        if container.get("cursor"):
            lines.append(f"- cursor: {_item(container['cursor'])}")

    for h in raw.get("heard") or []:
        prefix = "ACTION BAR" if h.get("action_bar") else "HEARD"
        lines.append(f"{prefix} ({h['seconds_ago']}s ago): {h['text']}")
    for d in raw.get("recent_damage") or []:
        by = f" by {d['by']} (entity_id {d['by_entity_id']})" if "by" in d else ""
        lines.append(f"DAMAGE {d['seconds_ago']}s ago: {d['type']}{by}, lost {d['health_lost']} health")
    return "\n".join(lines)
