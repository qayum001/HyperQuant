import asyncio

from fakes import FakeWorld, ScriptedLLM
from hyperquant_agents.config import CortexSettings, LLMSettings
from hyperquant_agents.cortex import Cortex, render_facts
from hyperquant_agents.llm import LLMClient


def observation():
    world = FakeWorld()
    world.agents["Bob"] = [0.5, 64.0, 0.5]
    world.inventory["minecraft:log"] = 3
    return world.observation("Bob")


def test_facts_keep_exact_coordinates_ids_and_reach():
    facts = render_facts(observation())
    assert "POSITION (0.5, 64.0, 0.5)" in facts
    assert "Oak Wood (minecraft:log) x3 visible; nearest (3, 65, 3)" in facts
    assert "entity_id 7: Steve (minecraft:player)" in facts
    assert "[0] Oak Wood x3 (minecraft:log)" in facts
    assert "health 20.0/20.0, food 20/20" in facts


def test_rules_mode_never_calls_a_model():
    perception = asyncio.run(Cortex(None, CortexSettings(mode="rules")).perceive(observation(), "get wood"))
    assert perception.source == "rules"
    assert perception.text == perception.facts


def test_llm_description_is_cached_while_nothing_changes():
    llm = ScriptedLLM(brain=[], cortex="An oak tree is 4 blocks ahead at (3, 64, 3).")
    cortex = Cortex(LLMClient(LLMSettings(api_key="k"), transport=llm.transport()), CortexSettings(models=["m"]))
    first = asyncio.run(cortex.perceive(observation(), "get wood"))
    second = asyncio.run(cortex.perceive(observation(), "get wood"))
    assert first.source == "llm" and second.source == "cache"
    assert first.text == "An oak tree is 4 blocks ahead at (3, 64, 3)."
    assert len(llm.calls_for("cortex")) == 1


def test_falls_back_to_facts_when_the_model_fails():
    llm = ScriptedLLM(brain=[], fail_first={"m": 9})
    cortex = Cortex(LLMClient(LLMSettings(api_key="k", max_retries=0), transport=llm.transport()),
                    CortexSettings(models=["m"]))
    perception = asyncio.run(cortex.perceive(observation(), "get wood"))
    assert perception.source.startswith("rules (llm failed")
    assert "minecraft:log" in perception.text
