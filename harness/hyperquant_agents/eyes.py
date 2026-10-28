"""The eyes: fetch what the avatar perceives right now (structured data, never a screenshot)."""
from __future__ import annotations

import json
from typing import Any

from .bridge import Bridge


class Eyes:
    def __init__(self, bridge: Bridge):
        self._bridge = bridge

    async def look(self, agent: str, panorama: bool = False) -> dict[str, Any]:
        """Raw observation from the mod. panorama=True means the agent glances all around instead of only ahead."""
        return await self._bridge.observe(agent, panorama=panorama)


def compact(value: Any) -> Any:
    """Drops empty and null fields so the raw data costs fewer tokens when sent to a model."""
    if isinstance(value, dict):
        out = {}
        for k, v in value.items():
            v = compact(v)
            if v is None or v == [] or v == {} or v == "":
                continue
            out[k] = v
        return out
    if isinstance(value, list):
        return [compact(v) for v in value]
    return value


def fingerprint(raw: dict[str, Any]) -> str:
    """Identity of an observation, ignoring fields that change every tick, to detect 'nothing changed'."""
    stable = {k: v for k, v in raw.items() if k not in ("tick", "world", "heard", "last_action")}
    return json.dumps(stable, sort_keys=True)
