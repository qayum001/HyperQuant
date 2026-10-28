import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fakes import TOKEN, FakeWorld, ScriptedLLM  # noqa: E402
from hyperquant_agents.app import App  # noqa: E402
from hyperquant_agents.config import Config  # noqa: E402


@pytest.fixture
def config(tmp_path):
    c = Config()
    c.bridge.token = TOKEN
    c.llm.api_key = "test-key"
    c.llm.max_retries = 0
    c.cortex.models = ["cortex-model"]
    c.brain.models = ["brain-model"]
    c.agent.announce = True
    c.agent.max_steps = 12
    c.trace_dir = str(tmp_path / "traces")
    return c


def make_app(config: Config, world: FakeWorld, llm: ScriptedLLM) -> App:
    return App(config, bridge_transport=world.transport(), llm_transport=llm.transport())
