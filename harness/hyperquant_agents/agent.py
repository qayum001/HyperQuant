"""The agentic loop for one task:

    0. a player asks for something
    1. eyes    - observe what the avatar perceives
    2. cortex  - turn the raw data into readable text
    3. brain   - choose one body action (or declare the task done / impossible)
    4. body    - perform it in the game, as a player would
    5. check   - a "done" claim is verified against fresh observations; otherwise repeat from 1
"""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field

from .body import Body, Outcome
from .brain import Brain, BrainError, Judge, TaskContext
from .bridge import BridgeError
from .config import AgentSettings
from .cortex import Cortex
from .eyes import Eyes
from .memory import Memory
from .trace import Tracer

log = logging.getLogger(__name__)


@dataclass
class Task:
    agent: str
    text: str
    requester: str | None = None
    source: str = "harness"
    cancel: asyncio.Event = field(default_factory=asyncio.Event)


@dataclass
class TaskResult:
    status: str  # done | gave_up | failed | timeout | cancelled
    summary: str
    steps: int


class AgentRunner:
    MAX_BRAIN_ERRORS = 3

    def __init__(self, name: str, *, eyes: Eyes, cortex: Cortex, brain: Brain, judge: Judge | None, body: Body,
                 settings: AgentSettings, tracer: Tracer):
        self.name = name
        self._eyes = eyes
        self._cortex = cortex
        self._brain = brain
        self._judge = judge
        self._body = body
        self._settings = settings
        self._tracer = tracer

    async def run(self, task: Task) -> TaskResult:
        trace = self._tracer.start(self.name, task.text)
        trace.write("task", agent=self.name, text=task.text, requester=task.requester, source=task.source,
                    system_prompt=self._brain.system_prompt)
        if self._settings.announce:
            await self._say(task, f"On it: {task.text}")
        result = TaskResult("cancelled", "the harness shut down", 0)
        try:
            result = await self._loop(task, trace)
        except (BridgeError, BrainError) as e:
            log.error("[%s] task failed: %s", self.name, e)
            result = TaskResult("failed", str(e), 0)
        finally:
            trace.write("result", status=result.status, summary=result.summary, steps=result.steps)
            trace.close()
        log.info("[%s] %s after %d steps: %s", self.name, result.status, result.steps, result.summary)
        if self._settings.announce:
            await self._say(task, _closing_line(result))
        return result

    async def _loop(self, task: Task, trace) -> TaskResult:
        s = self._settings
        memory = Memory(s.history)
        loop = asyncio.get_running_loop()
        deadline = loop.time() + s.max_minutes * 60
        step = 0
        brain_errors = 0
        while step < s.max_steps:
            if task.cancel.is_set():
                return TaskResult("cancelled", "stopped on request", step)
            if loop.time() > deadline:
                return TaskResult("timeout", f"ran out of time after {step} steps", step)
            step += 1

            # 1. eyes
            panorama = step == 1 or (s.panorama_every > 0 and step % s.panorama_every == 0)
            raw = await self._eyes.look(self.name, panorama=panorama)
            if raw.get("self", {}).get("dead"):
                memory.feedback("You died and respawned at your spawn point. Your items may still lie where you died.")
                await self._wait_while_dead(task)
                continue

            # 2. cortex
            perception = await self._cortex.perceive(raw, task.text)

            # 3. brain
            try:
                decision = await self._brain.decide(TaskContext(task.text, task.requester, step, s.max_steps),
                                                    perception, memory)
                brain_errors = 0
            except BrainError as e:
                brain_errors += 1
                trace.write("brain_error", step=step, error=str(e))
                if brain_errors >= self.MAX_BRAIN_ERRORS:
                    return TaskResult("failed", f"the decision model is unavailable: {e}", step)
                step -= 1
                await asyncio.sleep(10 * brain_errors)  # usually rate limits on free models
                continue
            if decision.note:
                memory.note(decision.note)
            trace.write("step", step=step, panorama=panorama, raw=raw, perception=perception.text,
                        perception_source=perception.source, decision=decision.__dict__)
            log.info("[%s] #%d %s %s  (%s)", self.name, step, decision.action, decision.args, decision.thought)

            # 5. check a completion claim before accepting it
            if decision.action == "done":
                summary = decision.args.get("summary", "")
                if self._judge is not None:
                    ok, reason = await self._judge.verify(task.text, summary, memory, perception)
                    trace.write("verification", step=step, done=ok, reason=reason)
                    if not ok:
                        memory.record(step, "done", decision.args, Outcome("failure", f"rejected by the check: {reason}"))
                        memory.feedback(f"The completion check says the task is NOT done yet: {reason}")
                        continue
                return TaskResult("done", summary, step)
            if decision.action == "give_up":
                return TaskResult("gave_up", decision.args.get("reason", ""), step)

            # 4. body
            if task.cancel.is_set():
                return TaskResult("cancelled", "stopped on request", step)
            outcome = await self._body.perform(self.name, decision.action, decision.args)
            memory.record(step, decision.action, decision.args, outcome)
            trace.write("outcome", step=step, status=outcome.status, message=outcome.message, data=outcome.data)
            log.info("[%s] #%d -> %s: %s", self.name, step, outcome.status, outcome.message)
        return TaskResult("failed", f"used all {s.max_steps} steps without finishing", step)

    async def _wait_while_dead(self, task: Task) -> None:
        for _ in range(20):
            if task.cancel.is_set():
                return
            await asyncio.sleep(1.0)
            raw = await self._eyes.look(self.name)
            if not raw.get("self", {}).get("dead"):
                return

    async def _say(self, task: Task, text: str) -> None:
        message = (f"@{task.requester} " if task.requester else "") + text
        try:
            await self._body.perform(self.name, "chat", {"message": message[:240]})
        except BridgeError as e:
            log.debug("could not chat: %s", e)


def _closing_line(result: TaskResult) -> str:
    if result.status == "done":
        return f"Done: {result.summary}" if result.summary else "Done."
    if result.status == "gave_up":
        return f"I can't do that: {result.summary}"
    if result.status == "cancelled":
        return "Stopped."
    return f"I couldn't finish: {result.summary}"
