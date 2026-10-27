import re
from pathlib import Path

import pytest

from hyperquant_agents.body import CATALOG, SPECS, InvalidAction, catalog_text, validate

ACTIONS_JAVA = Path(__file__).resolve().parents[2] / "mod/src/main/java/com/hyperquant/agentbridge/body/Actions.java"


def test_catalog_matches_the_mod():
    mod_actions = set(re.findall(r'FACTORIES\.put\("(\w+)"', ACTIONS_JAVA.read_text()))
    harness_actions = {s.name for s in CATALOG if not s.harness_only}
    assert harness_actions == mod_actions


def test_coerces_types_and_drops_unknown_arguments():
    assert validate("mine", {"x": "12.7", "y": 64.0, "z": -3, "colour": "red"}) == {"x": 12, "y": 64, "z": -3}
    assert validate("move_to", {"entity_id": 5, "sprint": "yes"}) == {"entity_id": 5, "sprint": True}


@pytest.mark.parametrize("action,args,reason", [
    ("fly", {}, "unknown action"),
    ("mine", {"x": 1, "y": 2}, "needs x+y+z"),
    ("craft", {}, "needs item"),
    ("move_to", {"range": 2}, "x+y+z or entity_id"),
    ("mine", {"x": "here", "y": 1, "z": 1}, "must be a int"),
])
def test_rejects_bad_actions_with_a_reason(action, args, reason):
    with pytest.raises(InvalidAction, match=re.escape(reason)):
        validate(action, args)


def test_catalog_text_lists_every_action():
    text = catalog_text()
    for name in SPECS:
        assert f"- {name}(" in text
