"""The brain: picks the next body action from what the cortex reports, and judges whether the task is done."""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import Any

from . import prompts
from .body import catalog_text, compact_catalog_text, validate
from .config import ModelRole
from .cortex import Perception
from .llm import LLMClient, LLMError, extract_json
from .memory import Memory

log = logging.getLogger(__name__)

_META_KEYS = {"thought", "action", "args", "note", "reasoning"}


class BrainError(Exception):
    pass


@dataclass
class Decision:
    action: str
    args: dict[str, Any] = field(default_factory=dict)
    thought: str = ""
    note: str = ""
    model: str = ""
    # What the model was shown and what it answered, kept for traces and training data.
    prompt: str = ""
    reply: str = ""
    attempts: int = 1

    def as_reply(self) -> str:
        """The decision as a clean model reply: the target text when training on recorded decisions."""
        out: dict[str, Any] = {"thought": self.thought, "action": self.action, "args": self.args}
        if self.note:
            out["note"] = self.note
        return json.dumps(out, ensure_ascii=False)


@dataclass
class TaskContext:
    text: str
    requester: str | None
    step: int
    max_steps: int


def system_prompt(agent_name: str, style: str = "full") -> str:
    """The brain's instructions. "full" explains every action; "compact" lists only the signatures, which saves
    about two thousand tokens of context for small or fine-tuned local models."""
    if style == "compact":
        return prompts.brain_system_compact(agent_name, compact_catalog_text())
    if style == "full":
        return prompts.brain_system(agent_name, catalog_text())
    raise ValueError(f'unknown prompt style {style!r} (use "full" or "compact")')


class Brain:
    def __init__(self, llm: LLMClient, role: ModelRole, agent_name: str, prompt_style: str = "full"):
        self._llm = llm
        self._role = role
        self.system_prompt = system_prompt(agent_name, prompt_style)

    async def decide(self, task: TaskContext, perception: Perception, memory: Memory) -> Decision:
        user = prompts.brain_user(
            requester=task.requester, task=task.text, step=task.step, max_steps=task.max_steps,
            seen=perception.text, facts=None if perception.text == perception.facts else perception.facts,
            notes=memory.render_notes(), history=memory.render_history(), feedback=memory.take_feedback())
        messages = [{"role": "system", "content": self.system_prompt}, {"role": "user", "content": user}]
        for attempt in range(3):
            try:
                reply = await self._llm.chat(messages, self._role)
            except LLMError as e:
                raise BrainError(str(e)) from e
            try:
                decision = parse_decision(reply.text, reply.model)
            except ValueError as e:
                log.info("unusable decision (%s): %s", e, reply.text[:300])
                messages += [
                    {"role": "assistant", "content": reply.text},
                    {"role": "user", "content": f"That reply was not usable: {e}. Reply again with exactly one JSON "
                                                "object that uses an action from the list."},
                ]
                continue
            decision.prompt, decision.reply, decision.attempts = user, reply.text, attempt + 1
            return decision
        raise BrainError("the decision model did not produce a usable action")


def parse_decision(text: str, model: str = "") -> Decision:
    data = extract_json(text)
    action = str(data.get("action", "")).strip()
    if isinstance(data.get("args"), dict):
        args = data["args"]
    else:  # some models flatten the arguments into the top level
        args = {k: v for k, v in data.items() if k not in _META_KEYS}
    clean = validate(action, args)  # raises InvalidAction (a ValueError) with a reason the model can fix
    return Decision(action=action, args=clean, thought=str(data.get("thought", "")).strip(),
                    note=str(data.get("note") or "").strip(), model=model)


class Judge:
    """Second opinion on "done": checks the claim against fresh observations before the task is closed."""

    def __init__(self, llm: LLMClient, role: ModelRole):
        self._llm = llm
        self._role = role

    async def verify(self, task: str, claim: str, memory: Memory, perception: Perception) -> tuple[bool, str]:
        messages = [
            {"role": "system", "content": prompts.JUDGE_SYSTEM},
            {"role": "user", "content": prompts.judge_user(task=task, claim=claim, history=memory.render_history(),
                                                            facts=perception.facts)},
        ]
        try:
            reply = await self._llm.chat(messages, self._role)
            verdict = extract_json(reply.text)
        except (LLMError, ValueError) as e:
            log.warning("completion check unavailable (%s); accepting the agent's claim", e)
            return True, "not verified (checker unavailable)"
        done = verdict.get("done")
        if isinstance(done, str):
            done = done.strip().lower() in ("true", "yes")
        return bool(done), str(verdict.get("reason", "")).strip()
