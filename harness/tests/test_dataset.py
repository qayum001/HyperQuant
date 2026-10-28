import json
from pathlib import Path

from fakes import FakeWorld, ScriptedLLM, decision
from hyperquant_agents.brain import parse_decision, system_prompt
from hyperquant_agents.cli import main
from hyperquant_agents.dataset import examples, export, read_trace
from test_agent import GATHER_SCRIPT, run_task


def traces(config):
    return sorted(Path(config.trace_dir).glob("*.jsonl"))


def test_successful_task_becomes_one_example_per_good_step(config):
    script = [decision("mine", "Mine that log.", x=5, y=64, z=5),             # fails: no block there
              decision("done", "Probably fine.", summary="Got wood.")] + GATHER_SCRIPT  # rejected by the judge
    judge = ['{"done": false, "reason": "no log yet"}', '{"done": true, "reason": "has a log"}']
    run_task(config, FakeWorld(), ScriptedLLM(brain=script, judge=judge))

    rows = list(examples(read_trace(traces(config)[0])))
    actions = [json.loads(r["messages"][2]["content"])["action"] for r in rows]
    assert actions == ["move_to", "mine", "collect_items", "done"]
    first = rows[0]["messages"]
    assert first[0]["content"] == system_prompt("Bob", "full")
    assert first[1]["content"].startswith("TASK (from Steve): get one oak log")
    # The step after the failures still shows them in its history, so the model learns to recover.
    assert "failure: there is no block at (5, 64, 5)" in first[1]["content"]
    for r in rows:
        parse_decision(r["messages"][2]["content"])  # every target is a valid action


def test_failures_and_unfinished_tasks_are_opt_in(config):
    script = [decision("mine", "Mine that log.", x=5, y=64, z=5)] + GATHER_SCRIPT
    run_task(config, FakeWorld(), ScriptedLLM(brain=script))
    records = read_trace(traces(config)[0])
    assert len(list(examples(records))) == 4
    assert len(list(examples(records, keep_failures=True))) == 5

    records[-1]["status"] = "failed"
    assert list(examples(records)) == []
    assert len(list(examples(records, include_unfinished=True))) == 4


def test_compact_prompt_style_rewrites_the_system_prompt(config):
    run_task(config, FakeWorld(), ScriptedLLM(brain=list(GATHER_SCRIPT)))
    rows = list(examples(read_trace(traces(config)[0]), prompt_style="compact"))
    assert rows[0]["messages"][0]["content"] == system_prompt("Bob", "compact")
    assert "mine(x, y, z, auto_tool?)" in rows[0]["messages"][0]["content"]


def test_export_splits_by_task(config, tmp_path):
    for _ in range(4):
        run_task(config, FakeWorld(), ScriptedLLM(brain=list(GATHER_SCRIPT)))
    stats = export([config.trace_dir], tmp_path / "train.jsonl", val_out=tmp_path / "val.jsonl", val_fraction=0.25)
    assert (stats.tasks_used, stats.train_examples, stats.val_examples) == (4, 12, 4)
    assert len((tmp_path / "val.jsonl").read_text().splitlines()) == 4


def test_cli_dataset_command(config, tmp_path, capsys):
    run_task(config, FakeWorld(), ScriptedLLM(brain=list(GATHER_SCRIPT)))
    out = tmp_path / "data.jsonl"
    assert main(["dataset", config.trace_dir, "--out", str(out), "--prompt-style", "compact"]) == 0
    assert "4 training examples" in capsys.readouterr().out
    assert len(out.read_text().splitlines()) == 4
