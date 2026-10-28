import asyncio

import pytest

from fakes import ScriptedLLM
from hyperquant_agents.config import LLMSettings, ModelRole
from hyperquant_agents.llm import LLMClient, LLMError, extract_json


@pytest.mark.parametrize("text", [
    '{"action": "wait", "args": {"ticks": 20}}',
    'Sure! Here you go:\n```json\n{"action": "wait", "args": {"ticks": 20}}\n```',
    '<think>The {player} wants wood...</think>{"action": "wait", "args": {"ticks": 20},}',
    'I think {maybe} this: {"action": "wait", "args": {"ticks": 20}} and nothing else',
])
def test_extract_json_tolerates_chatter(text):
    assert extract_json(text) == {"action": "wait", "args": {"ticks": 20}}


def test_extract_json_keeps_braces_inside_strings():
    assert extract_json('{"thought": "a } b {", "action": "wait"}')["thought"] == "a } b {"


def test_extract_json_rejects_prose():
    with pytest.raises(ValueError):
        extract_json("I will now walk to the tree.")


def test_falls_back_to_next_model_when_rate_limited():
    llm = ScriptedLLM(brain=['{"action": "wait"}'], fail_first={"first": 5})
    client = LLMClient(LLMSettings(api_key="k", max_retries=0), transport=llm.transport())
    reply = asyncio.run(client.chat([{"role": "system", "content": "x"}], ModelRole(models=["first", "second"])))
    assert reply.model == "second"


def test_raises_when_every_model_fails():
    llm = ScriptedLLM(brain=[], fail_first={"only": 5})
    client = LLMClient(LLMSettings(api_key="k", max_retries=0), transport=llm.transport())
    with pytest.raises(LLMError):
        asyncio.run(client.chat([{"role": "system", "content": "x"}], ModelRole(models=["only"])))
