"""Per-task JSONL traces: every observation, perception, decision and outcome, for debugging prompts later."""
from __future__ import annotations

import json
import re
import time
from pathlib import Path
from typing import Any, TextIO


class TaskTrace:
    def __init__(self, handle: TextIO | None):
        self._handle = handle
        self.path: str | None = handle.name if handle else None

    def write(self, kind: str, **data: Any) -> None:
        if self._handle is None:
            return
        record = {"time": round(time.time(), 3), "kind": kind, **data}
        self._handle.write(json.dumps(record, ensure_ascii=False, default=str) + "\n")
        self._handle.flush()

    def close(self) -> None:
        if self._handle is not None:
            self._handle.close()


class Tracer:
    def __init__(self, directory: str | None):
        self._dir = Path(directory) if directory else None

    def start(self, agent: str, task: str) -> TaskTrace:
        if self._dir is None:
            return TaskTrace(None)
        self._dir.mkdir(parents=True, exist_ok=True)
        slug = re.sub(r"[^a-z0-9]+", "-", task.lower()).strip("-")[:40] or "task"
        base = f"{time.strftime('%Y%m%d-%H%M%S')}-{agent}-{slug}"
        for n in range(1, 1000):
            path = self._dir / (f"{base}.jsonl" if n == 1 else f"{base}-{n}.jsonl")
            try:
                return TaskTrace(path.open("x", encoding="utf-8"))  # never overwrite another task's trace
            except FileExistsError:
                continue
        raise RuntimeError(f"too many traces named {base}")
