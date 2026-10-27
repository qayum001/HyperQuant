import asyncio
import json
from pathlib import Path

from conftest import make_app
from fakes import FakeWorld, ScriptedLLM, decision
from hyperquant_agents.agent import Task
from hyperquant_agents.app import Supervisor

GATHER_SCRIPT = [
    decision("move_to", "The tree is a few blocks away.", x=2, y=64, z=2),
    decision("mine", "The bottom log is in reach.", x=3, y=64, z=3),
    decision("collect_items", "Pick up the log."),
    decision("done", "I have a log.", summary="Got 1 oak log."),
]


def run_task(config, world, llm, text="get one oak log"):
    async def go():
        async with make_app(config, world, llm) as app:
            await app.ensure_spawned("Bob")
            return await app.runner("Bob").run(Task("Bob", text, requester="Steve"))
    return asyncio.run(go())


def test_full_loop_gathers_wood_and_reports_back(config):
    world, llm = FakeWorld(), ScriptedLLM(brain=list(GATHER_SCRIPT))
    result = run_task(config, world, llm)

    assert result.status == "done" and result.summary == "Got 1 oak log."
    assert world.inventory == {"minecraft:log": 1}
    assert [a for a, _ in world.actions if a != "chat"] == ["move_to", "mine", "collect_items"]
    assert world.chat == ["@Steve On it: get one oak log", "@Steve Done: Got 1 oak log."]
    # The brain saw both the cortex description and the exact data, and the previous step's result.
    last_brain_turn = llm.calls_for("brain")[-1][-1]["content"]
    assert "A small oak tree is ahead." in last_brain_turn
    assert "EXACT DATA" in last_brain_turn and "minecraft:log" in last_brain_turn
    assert "collect_items  -> success: picked up 1 item stack(s)" in last_brain_turn
    # Everything is traced.
    trace = next(Path(config.trace_dir).glob("*.jsonl")).read_text().splitlines()
    kinds = [json.loads(line)["kind"] for line in trace]
    assert kinds[0] == "task" and kinds[-1] == "result" and kinds.count("outcome") == 3


def test_failed_action_is_fed_back_to_the_brain(config):
    script = [decision("mine", "Mine that log.", x=5, y=64, z=5)] + GATHER_SCRIPT
    world, llm = FakeWorld(), ScriptedLLM(brain=script)
    result = run_task(config, world, llm)
    assert result.status == "done"
    second_turn = llm.calls_for("brain")[1][-1]["content"]
    assert '#1 mine {"x":5,"y":64,"z":5} -> failure: there is no block at (5, 64, 5)' in second_turn


def test_completion_check_can_send_the_agent_back_to_work(config):
    script = [decision("done", "Probably fine.", summary="Got wood.")] + GATHER_SCRIPT
    judge = ['{"done": false, "reason": "there is no log in the inventory"}', '{"done": true, "reason": "has a log"}']
    world, llm = FakeWorld(), ScriptedLLM(brain=script, judge=judge)
    result = run_task(config, world, llm)
    assert result.status == "done" and world.inventory == {"minecraft:log": 1}
    second_turn = llm.calls_for("brain")[1][-1]["content"]
    assert "NOT done yet: there is no log in the inventory" in second_turn


def test_unusable_replies_are_retried_then_corrected(config):
    script = ["I will walk to the tree now.", decision("fly", "up"), *GATHER_SCRIPT]
    world, llm = FakeWorld(), ScriptedLLM(brain=script)
    result = run_task(config, world, llm)
    assert result.status == "done"
    retry_prompt = llm.calls_for("brain")[2][-1]["content"]
    assert "unknown action 'fly'" in retry_prompt


def test_step_budget_ends_a_stuck_task(config):
    config.agent.max_steps = 3
    world, llm = FakeWorld(), ScriptedLLM(brain=[decision("wait", ticks=20)] * 5)
    result = run_task(config, world, llm)
    assert result.status == "failed" and "3 steps" in result.summary


def test_supervisor_turns_chat_requests_into_tasks_and_stops_them(config):
    world = FakeWorld()
    world.agents["Bob"] = [0.5, 64.0, 0.5]
    llm = ScriptedLLM(brain=list(GATHER_SCRIPT))

    async def go():
        async with make_app(config, world, llm) as app:
            supervisor = Supervisor(app)
            runner = asyncio.create_task(supervisor.run())
            world.publish("task", agent="Bob", text="get one oak log", **{"from": "Steve"}, source="chat")
            for _ in range(200):
                await asyncio.sleep(0.02)
                worker = supervisor.workers.get("bob")
                if worker and worker.results:
                    break
            runner.cancel()
            try:
                await runner
            except asyncio.CancelledError:
                pass
            return supervisor.workers["bob"].results

    results = asyncio.run(go())
    assert [r.status for r in results] == ["done"]


def test_player_stop_cancels_the_running_task(config):
    config.agent.max_steps = 200
    world = FakeWorld()
    world.action_delay = 0.02
    world.agents["Bob"] = [0.5, 64.0, 0.5]
    llm = ScriptedLLM(brain=[decision("wait", ticks=20)] * 50)

    async def go():
        async with make_app(config, world, llm) as app:
            supervisor = Supervisor(app)
            runner = asyncio.create_task(supervisor.run())
            world.publish("task", agent="Bob", text="wait around", **{"from": "Steve"})
            await asyncio.sleep(0.1)
            world.publish("stop", agent="Bob", **{"from": "the harness"})  # ignored: not a player
            await asyncio.sleep(0.1)
            assert supervisor.workers["bob"].current is not None
            world.publish("stop", agent="Bob", **{"from": "Steve"})
            for _ in range(200):
                await asyncio.sleep(0.02)
                if supervisor.workers["bob"].results:
                    break
            runner.cancel()
            try:
                await runner
            except asyncio.CancelledError:
                pass
            return supervisor.workers["bob"].results

    results = asyncio.run(go())
    assert [r.status for r in results] == ["cancelled"]
