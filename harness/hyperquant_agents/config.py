"""Harness settings, loaded from a TOML file with secrets taken from the environment."""
from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass
class BridgeSettings:
    url: str = "http://127.0.0.1:8765"
    token: str = ""
    timeout_s: float = 35.0


@dataclass
class LLMSettings:
    base_url: str = "https://openrouter.ai/api/v1"
    api_key: str = ""
    timeout_s: float = 90.0
    max_retries: int = 3
    app_name: str = "HyperQuant Agents"
    app_url: str = "https://github.com/qayum001/HyperQuant"


@dataclass
class ModelRole:
    """Which models one part of the brain uses. Models are tried in order; later ones are fallbacks."""

    models: list[str] = field(default_factory=list)
    temperature: float = 0.3
    max_tokens: int = 800


@dataclass
class CortexSettings(ModelRole):
    mode: str = "llm"  # "llm" or "rules"


@dataclass
class JudgeSettings(ModelRole):
    enabled: bool = True


@dataclass
class AgentSettings:
    spawn: list[str] = field(default_factory=list)
    spawn_near: str | None = None
    max_steps: int = 80
    max_minutes: float = 30.0
    history: int = 12
    panorama_every: int = 6
    action_timeout_s: float = 120.0
    announce: bool = True


@dataclass
class Config:
    bridge: BridgeSettings = field(default_factory=BridgeSettings)
    llm: LLMSettings = field(default_factory=LLMSettings)
    cortex: CortexSettings = field(default_factory=lambda: CortexSettings(
        models=["meta-llama/llama-3.3-70b-instruct:free"], temperature=0.2, max_tokens=600))
    brain: ModelRole = field(default_factory=lambda: ModelRole(
        models=["deepseek/deepseek-chat-v3-0324:free"], temperature=0.3, max_tokens=900))
    judge: JudgeSettings = field(default_factory=JudgeSettings)
    agent: AgentSettings = field(default_factory=AgentSettings)
    trace_dir: str | None = "traces"

    @property
    def judge_role(self) -> ModelRole:
        return self.judge if self.judge.models else self.brain


def _fill(target: Any, values: dict[str, Any], section: str) -> None:
    for key, value in values.items():
        if not hasattr(target, key):
            raise ValueError(f"unknown setting [{section}] {key}")
        setattr(target, key, value)


def load_config(path: str | os.PathLike[str] | None = None) -> Config:
    """Reads the TOML file (if any), then applies OPENROUTER_API_KEY / AGENTBRIDGE_TOKEN from the environment."""
    config = Config()
    data: dict[str, Any] = {}
    if path is not None:
        data = tomllib.loads(Path(path).read_text(encoding="utf-8"))
    sections = {
        "bridge": config.bridge,
        "llm": config.llm,
        "cortex": config.cortex,
        "brain": config.brain,
        "judge": config.judge,
        "agent": config.agent,
    }
    for name, values in data.items():
        if name == "trace":
            config.trace_dir = values.get("dir") or None
        elif name in sections:
            _fill(sections[name], values, name)
        else:
            raise ValueError(f"unknown config section [{name}]")

    config.llm.api_key = config.llm.api_key or os.environ.get("OPENROUTER_API_KEY", "")
    config.bridge.token = config.bridge.token or os.environ.get("AGENTBRIDGE_TOKEN", "")
    if config.cortex.mode not in ("llm", "rules"):
        raise ValueError('[cortex] mode must be "llm" or "rules"')
    return config
