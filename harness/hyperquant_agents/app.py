"""Wiring: builds the parts for each agent, and the supervisor that turns players' requests into tasks."""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

import httpx

from .agent import AgentRunner, Task, TaskResult
from .body import Body
from .brain import Brain, Judge
from .bridge import Bridge, BridgeError
from .config import Config
from .cortex import Cortex
from .eyes import Eyes
from .llm import LLMClient
from .trace import Tracer

log = logging.getLogger(__name__)


class App:
    def __init__(self, config: Config, *, bridge_transport: httpx.AsyncBaseTransport | None = None,
                 llm_transport: httpx.AsyncBaseTransport | None = None):
        self.config = config
        self.bridge = Bridge(config.bridge, transport=bridge_transport)
        self.llm = LLMClient(config.llm, transport=llm_transport)
        self.tracer = Tracer(config.trace_dir)

    async def aclose(self) -> None:
        await self.bridge.aclose()
        await self.llm.aclose()

    async def __aenter__(self) -> App:
        return self

    async def __aexit__(self, *exc: Any) -> None:
        await self.aclose()

    def cortex(self) -> Cortex:
        return Cortex(self.llm, self.config.cortex)

    def runner(self, name: str) -> AgentRunner:
        c = self.config
        judge = Judge(self.llm, c.judge_role) if c.judge.enabled else None
        return AgentRunner(name, eyes=Eyes(self.bridge), cortex=self.cortex(), brain=Brain(self.llm, c.brain, name),
                           judge=judge, body=Body(self.bridge, c.agent.action_timeout_s), settings=c.agent,
                           tracer=self.tracer)

    async def ensure_spawned(self, name: str, near_player: str | None = None) -> None:
        if any(a["name"].lower() == name.lower() for a in await self.bridge.agents()):
            return
        await self.bridge.spawn(name, near_player)
        log.info("spawned agent %s", name)


class AgentWorker:
    """Runs one agent's tasks one after another."""

    def __init__(self, runner: AgentRunner):
        self.runner = runner
        self.queue: asyncio.Queue[Task] = asyncio.Queue()
        self.current: Task | None = None
        self.results: list[TaskResult] = []
        self._task = asyncio.create_task(self._loop(), name=f"agent-{runner.name}")

    def submit(self, task: Task) -> None:
        self.queue.put_nowait(task)

    def stop_current(self) -> None:
        if self.current is not None:
            self.current.cancel.set()

    def stop_all(self) -> None:
        while not self.queue.empty():
            self.queue.get_nowait()
        self.stop_current()

    async def close(self) -> None:
        self._task.cancel()
        try:
            await self._task
        except asyncio.CancelledError:
            pass

    async def _loop(self) -> None:
        while True:
            task = await self.queue.get()
            self.current = task
            try:
                self.results.append(await self.runner.run(task))
            except Exception:  # keep the agent alive for the next request
                log.exception("[%s] task crashed", self.runner.name)
            finally:
                self.current = None
                self.queue.task_done()


class Supervisor:
    """Listens to the mod's event stream and dispatches players' requests to the right agent."""

    STALE_AFTER_S = 600  # ignore requests that waited longer than this while the harness was offline

    def __init__(self, app: App):
        self.app = app
        self.workers: dict[str, AgentWorker] = {}

    def worker(self, agent: str) -> AgentWorker:
        key = agent.lower()
        if key not in self.workers:
            self.workers[key] = AgentWorker(self.app.runner(agent))
        return self.workers[key]

    async def run(self) -> None:
        await self._wait_for_bridge()
        settings = self.app.config.agent
        for name in settings.spawn:
            try:
                await self.app.ensure_spawned(name, settings.spawn_near)
            except BridgeError as e:
                log.error("could not spawn %s: %s", name, e)
        after = 0
        log.info("listening for requests (say '@<agent> <task>' in chat or use /agent task)")
        try:
            while True:
                try:
                    events, last_id = await self.app.bridge.events(after, wait_ms=25_000)
                except BridgeError as e:
                    log.warning("event stream interrupted: %s", e)
                    await asyncio.sleep(5)
                    continue
                if last_id < after:
                    log.info("the Minecraft server restarted; reading its events from the start")
                    after = 0
                    continue
                for event in events:
                    self.dispatch(event)
                    after = max(after, event["id"])
        finally:
            for w in self.workers.values():
                await w.close()

    def dispatch(self, event: dict[str, Any]) -> None:
        kind, agent = event.get("type"), event.get("agent")
        if not agent:
            return
        if kind == "task":
            if time.time() - event.get("time_ms", 0) / 1000 > self.STALE_AFTER_S:
                log.info("ignoring stale request to %s: %s", agent, event.get("text"))
                return
            log.info("%s asked %s: %s", event.get("from"), agent, event.get("text"))
            self.worker(agent).submit(Task(agent, event["text"], event.get("from"), event.get("source", "chat")))
        elif kind == "stop":
            # The harness itself stops actions that overrun; only a player's "stop" ends the task.
            if event.get("from") != "the harness" and agent.lower() in self.workers:
                self.workers[agent.lower()].stop_all()
        elif kind == "despawned":
            if agent.lower() in self.workers:
                self.workers[agent.lower()].stop_all()
            log.info("%s left the game: %s", agent, event.get("message"))
        elif kind in ("died", "respawned", "spawned"):
            log.info("%s %s: %s", agent, kind, event.get("message"))

    async def _wait_for_bridge(self) -> None:
        while True:
            try:
                health = await self.app.bridge.health()
                log.info("connected to AgentBridge %s", health.get("mod_version"))
                return
            except BridgeError as e:
                log.warning("waiting for the Minecraft server: %s", e)
                await asyncio.sleep(5)
