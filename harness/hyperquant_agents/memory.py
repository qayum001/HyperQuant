"""Short-term memory for one task: recent steps, notes the brain chose to keep, and feedback for the next turn."""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from .body import Outcome


@dataclass
class Step:
    number: int
    action: str
    args: dict[str, Any]
    status: str
    message: str

    def render(self) -> str:
        args = json.dumps(self.args, separators=(",", ":")) if self.args else ""
        return f"#{self.number} {self.action} {args} -> {self.status}: {self.message}"


class Memory:
    MAX_NOTES = 20

    def __init__(self, history: int = 12):
        self.history = history
        self.steps: list[Step] = []
        self.notes: list[str] = []
        self._feedback: list[str] = []

    def record(self, number: int, action: str, args: dict[str, Any], outcome: Outcome) -> None:
        self.steps.append(Step(number, action, args, outcome.status, outcome.message))
        repeats = self.repeated_failures()
        if repeats >= 2:
            self.feedback(f"'{action}' with the same arguments failed {repeats} times in a row. "
                          "Do something different (move, look around, pick another target or tool).")

    def note(self, text: str) -> None:
        text = text.strip()
        if text and text not in self.notes:
            self.notes.append(text)
            del self.notes[:-self.MAX_NOTES]

    def feedback(self, text: str) -> None:
        self._feedback.append(text)

    def take_feedback(self) -> list[str]:
        out, self._feedback = self._feedback, []
        return out

    def repeated_failures(self) -> int:
        """How many times the latest failing action/args combination failed back to back."""
        if not self.steps or self.steps[-1].status == "success":
            return 0
        last = self.steps[-1]
        count = 0
        for step in reversed(self.steps):
            if step.status == "success" or step.action != last.action or step.args != last.args:
                break
            count += 1
        return count

    def render_history(self) -> str:
        return "\n".join(step.render() for step in self.steps[-self.history:])

    def render_notes(self) -> str:
        return "\n".join(f"- {n}" for n in self.notes)
