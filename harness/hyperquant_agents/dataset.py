"""Turns task traces into chat fine-tuning data.

Each brain turn of a task that ended verified as done becomes one example: the system prompt, the exact user
prompt the brain saw, and its decision as a clean JSON reply. Steps whose action failed are left out by default,
so a small model learns from what worked, including how the teacher recovered from earlier failures.
"""
from __future__ import annotations

import json
import random
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Iterator

from .brain import Decision, system_prompt


@dataclass
class ExportStats:
    traces: int = 0
    tasks_used: int = 0
    train_examples: int = 0
    val_examples: int = 0
    skipped_steps: int = 0


def trace_files(paths: Iterable[str | Path]) -> list[Path]:
    files: list[Path] = []
    for p in map(Path, paths):
        files += sorted(p.glob("*.jsonl")) if p.is_dir() else [p]
    return files


def read_trace(path: Path) -> list[dict[str, Any]]:
    records = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


def examples(records: list[dict[str, Any]], *, prompt_style: str | None = None, keep_failures: bool = False,
             include_unfinished: bool = False, stats: ExportStats | None = None) -> Iterator[dict[str, Any]]:
    """Training examples from one task trace. prompt_style re-renders the system prompt ("full" or "compact");
    by default the prompt recorded in the trace is used."""
    stats = stats if stats is not None else ExportStats()
    task = next((r for r in records if r["kind"] == "task"), None)
    result = next((r for r in reversed(records) if r["kind"] == "result"), None)
    if task is None or (not include_unfinished and (result is None or result["status"] != "done")):
        return
    system = system_prompt(task["agent"], prompt_style) if prompt_style else task.get("system_prompt")
    if not system:
        return  # a trace from before prompts were recorded
    outcomes = {r["step"]: r for r in records if r["kind"] == "outcome"}
    verdicts = {r["step"]: r for r in records if r["kind"] == "verification"}
    for record in (r for r in records if r["kind"] == "step"):
        d = record["decision"]
        step = record["step"]
        if not d.get("prompt"):
            stats.skipped_steps += 1
            continue
        if d["action"] == "done":
            good = verdicts.get(step, {}).get("done", True)
        elif d["action"] == "give_up":
            good = False
        else:
            good = outcomes.get(step, {}).get("status") == "success"
        if not good and not keep_failures:
            stats.skipped_steps += 1
            continue
        reply = Decision(action=d["action"], args=d.get("args") or {}, thought=d.get("thought", ""),
                         note=d.get("note", "")).as_reply()
        yield {"messages": [{"role": "system", "content": system},
                            {"role": "user", "content": d["prompt"]},
                            {"role": "assistant", "content": reply}]}


def export(paths: Iterable[str | Path], out: str | Path, *, val_out: str | Path | None = None,
           val_fraction: float = 0.0, seed: int = 0, **options: Any) -> ExportStats:
    """Writes train (and optionally validation) JSONL. The split is by task, so no task leaks into both."""
    stats = ExportStats()
    files = trace_files(paths)
    stats.traces = len(files)
    tasks = [rows for rows in (list(examples(read_trace(f), stats=stats, **options)) for f in files) if rows]
    stats.tasks_used = len(tasks)
    random.Random(seed).shuffle(tasks)
    n_val = round(len(tasks) * val_fraction) if val_out else 0
    _write(Path(out), tasks[n_val:])
    stats.train_examples = sum(map(len, tasks[n_val:]))
    if val_out:
        _write(Path(val_out), tasks[:n_val])
        stats.val_examples = sum(map(len, tasks[:n_val]))
    return stats


def _write(path: Path, tasks: list[list[dict[str, Any]]]) -> None:
    with path.open("w", encoding="utf-8") as f:
        for rows in tasks:
            for row in rows:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
