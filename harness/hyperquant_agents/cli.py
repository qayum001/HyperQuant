"""Command line: run the harness, or poke an agent by hand while debugging."""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
from typing import Any

import httpx

from .agent import Task
from .app import App, Supervisor
from .body import Body, InvalidAction, validate
from .config import Config, load_config
from .cortex import render_facts
from .dataset import export


def _config(path: str | None) -> Config:
    if path is None and os.path.exists("config.toml"):
        path = "config.toml"
    return load_config(path)


def _parse_value(text: str) -> Any:
    try:
        return json.loads(text)
    except ValueError:
        return text


async def _run(config: Config) -> None:
    async with App(config) as app:
        await Supervisor(app).run()


async def _task(config: Config, agent: str, text: str, requester: str | None, near: str | None) -> int:
    async with App(config) as app:
        await app.ensure_spawned(agent, near)
        task = Task(agent, text, requester, "cli")
        try:
            result = await app.runner(agent).run(task)
        except (KeyboardInterrupt, asyncio.CancelledError):
            await app.bridge.stop(agent)
            raise
        print(f"{result.status} after {result.steps} steps: {result.summary}")
        return 0 if result.status == "done" else 1


async def _observe(config: Config, agent: str, panorama: bool, describe: bool, raw_only: bool) -> None:
    async with App(config) as app:
        raw = await app.bridge.observe(agent, panorama=panorama)
        if raw_only:
            print(json.dumps(raw, indent=2))
            return
        print(render_facts(raw))
        if describe:
            perception = await app.cortex().perceive(raw, "(no task, just describe the surroundings)")
            print(f"\n--- visual cortex ({perception.source}) ---\n{perception.text}")


async def _act(config: Config, agent: str, action: str, pairs: list[str]) -> int:
    args = {}
    for pair in pairs:
        key, sep, value = pair.partition("=")
        if not sep:
            raise SystemExit(f"arguments look like key=value, got {pair!r}")
        args[key] = _parse_value(value)
    try:
        args = validate(action, args)
    except InvalidAction as e:
        raise SystemExit(str(e))
    async with App(config) as app:
        outcome = await Body(app.bridge, config.agent.action_timeout_s).perform(agent, action, args)
        print(f"{outcome.status}: {outcome.message}")
        if outcome.data:
            print(json.dumps(outcome.data, indent=2))
        return 0 if outcome.ok else 1


async def _agents(config: Config, spawn: str | None, despawn: str | None, near: str | None) -> None:
    async with App(config) as app:
        if spawn:
            print(json.dumps(await app.bridge.spawn(spawn, near), indent=2))
        elif despawn:
            await app.bridge.despawn(despawn)
            print(f"{despawn} left")
        else:
            for a in await app.bridge.agents():
                print(f"{a['name']}: at {a['position']} dim {a['dimension']}, health {a['health']}, "
                      f"food {a['food']}{', busy' if a['busy'] else ''}{', dead' if a['dead'] else ''}")


async def _models(config: Config, free_only: bool) -> None:
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.get(config.llm.base_url.rstrip("/") + "/models")
        response.raise_for_status()
    for m in sorted(response.json().get("data", []), key=lambda m: m["id"]):
        pricing = m.get("pricing") or {}
        free = m["id"].endswith(":free") or (pricing.get("prompt") in ("0", 0) and pricing.get("completion") in ("0", 0))
        if free or not free_only:
            print(f"{m['id']:60} context {m.get('context_length', '?')}")


def _dataset(args: argparse.Namespace) -> None:
    val_out = args.val_out or (str(args.out).removesuffix(".jsonl") + ".val.jsonl" if args.val_fraction else None)
    stats = export(args.traces, args.out, val_out=val_out, val_fraction=args.val_fraction,
                   prompt_style=args.prompt_style, keep_failures=args.keep_failures,
                   include_unfinished=args.include_unfinished)
    print(f"{stats.traces} traces, {stats.tasks_used} usable tasks -> {stats.train_examples} training examples"
          f" in {args.out}" + (f", {stats.val_examples} validation examples in {val_out}" if val_out else "")
          + f" ({stats.skipped_steps} steps left out)")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="hq-agents", description="LLM agents that play Minecraft as players.")
    parser.add_argument("-c", "--config", help="TOML config file (default: ./config.toml if present)")
    parser.add_argument("-v", "--verbose", action="store_true")
    sub = parser.add_subparsers(dest="command")
    sub.add_parser("run", help="spawn the configured agents and handle players' requests (default)")
    p = sub.add_parser("task", help="run one task right now")
    p.add_argument("agent")
    p.add_argument("text")
    p.add_argument("--from", dest="requester", help="player name to address in chat")
    p.add_argument("--near", help="first login next to this player")
    p = sub.add_parser("observe", help="print what an agent perceives")
    p.add_argument("agent")
    p.add_argument("--panorama", action="store_true", help="look all around")
    p.add_argument("--describe", action="store_true", help="also run the visual cortex model")
    p.add_argument("--raw", action="store_true", help="print the raw JSON")
    p = sub.add_parser("act", help="perform one body action, e.g. act Bob mine x=1 y=64 z=2")
    p.add_argument("agent")
    p.add_argument("action")
    p.add_argument("args", nargs="*", help="key=value")
    p = sub.add_parser("agents", help="list, spawn or despawn agents")
    p.add_argument("--spawn")
    p.add_argument("--despawn")
    p.add_argument("--near")
    p = sub.add_parser("dataset", help="turn task traces into chat fine-tuning data (JSONL)")
    p.add_argument("traces", nargs="+", help="trace files or directories")
    p.add_argument("--out", required=True)
    p.add_argument("--val-fraction", type=float, default=0.0, help="share of tasks held out for validation")
    p.add_argument("--val-out", help="validation file (default: <out>.val.jsonl)")
    p.add_argument("--prompt-style", choices=["full", "compact"],
                   help="re-render the system prompt (default: the one recorded in the trace)")
    p.add_argument("--keep-failures", action="store_true", help="also keep steps whose action failed")
    p.add_argument("--include-unfinished", action="store_true", help="also use tasks that did not end as done")
    p = sub.add_parser("models", help="list models available at the LLM endpoint")
    p.add_argument("--free", action="store_true")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s", datefmt="%H:%M:%S")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    command = args.command or "run"
    if command == "dataset":  # works offline, needs no config
        _dataset(args)
        return 0
    config = _config(args.config)
    try:
        if command == "run":
            asyncio.run(_run(config))
        elif command == "task":
            return asyncio.run(_task(config, args.agent, args.text, args.requester, args.near))
        elif command == "observe":
            asyncio.run(_observe(config, args.agent, args.panorama, args.describe, args.raw))
        elif command == "act":
            return asyncio.run(_act(config, args.agent, args.action, args.args))
        elif command == "agents":
            asyncio.run(_agents(config, args.spawn, args.despawn, args.near))
        elif command == "models":
            asyncio.run(_models(config, args.free))
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
