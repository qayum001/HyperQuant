# HyperQuant Agents

LLM agents that play **modded Minecraft 1.12.2** as ordinary survival players. Ask one in chat,
`@Bob bring me 16 oak logs`, and it looks around, walks, mines, crafts and reports back, with the same limits
you have: reach, line of sight, mining time, hunger, inventory space, permissions.

* No keyboard or mouse emulation and no screenshots: the agent's **eyes** report what it can see as structured
  data, and its **body** performs player-sized actions through the server's normal player code.
* The agent's mind runs as a **separate process** (the harness), not inside your game client.
* Works with any modpack: modded blocks, items, recipes and GUIs come from the server itself.

How it works, and why it is built this way: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
The mod's HTTP API: [docs/PROTOCOL.md](docs/PROTOCOL.md).

```
mod/       Forge 1.12.2 server mod "AgentBridge": avatars, eyes, body, local HTTP API   (Java 8)
harness/   The agent loop: eyes -> visual cortex (LLM) -> brain (LLM) -> body -> check  (Python 3.11+)
docs/      Architecture and protocol
```

## Setup

### 1. Server: the mod

The agents live on a server, so run your modpack as a **dedicated Forge 1.12.2 server** (a LAN world also works,
but then the mod runs inside your own game).

```bash
cd mod
./gradlew build          # needs a Java 8 JDK; ForgeGradle 2.3 does not run on newer Java
```

Copy `mod/build/libs/agentbridge-0.1.0.jar` into the server's `mods/` folder. Players do not need it. Start the
server once; it writes `config/agentbridge.cfg` with a generated API token:

```
general {
    api {
        S:bindAddress=127.0.0.1
        I:port=8765
        S:token=...        <- the harness needs this
    }
    ...
}
```

### 2. Harness: the brain

```bash
cd harness
python -m venv .venv && . .venv/bin/activate
pip install -e .
cp config.example.toml config.toml        # pick models, agent names, limits
export OPENROUTER_API_KEY=sk-or-...       # https://openrouter.ai/keys
export AGENTBRIDGE_TOKEN=...              # from config/agentbridge.cfg
hq-agents run
```

`hq-agents models --free` lists the models that are currently free on OpenRouter; put the ones you like in
`config.toml`. Any OpenAI-compatible endpoint works, for example a local Ollama with
`base_url = "http://localhost:11434/v1"`.

### 3. Play

```
/agent spawn Bob                    (op) Bob logs in next to you
@Bob bring me 16 oak logs           give a task in chat
/agent task Bob craft a stone pickaxe
@Bob stop                           cancel the current task
/agent list
/agent despawn Bob                  (op)
```

The agent answers in chat when it starts and when it finishes, gives up or fails. Each task leaves a trace in
`harness/traces/` with every observation, description, decision and result.

## Debugging an agent by hand

```bash
hq-agents agents --spawn Bob --near YourName
hq-agents observe Bob --panorama            # what Bob sees, as the brain gets it
hq-agents observe Bob --describe            # ... plus the visual cortex's description
hq-agents act Bob move_to x=100 y=64 z=-20
hq-agents act Bob mine x=101 y=64 z=-20
hq-agents act Bob craft item=minecraft:planks count=8
hq-agents task Bob "get some cobblestone" --from YourName
```

## Development

```bash
cd harness && pip install -e '.[dev]' && pytest
```

The tests run the full loop against an in-memory fake of the mod's API and a scripted LLM, and check that the
harness's action catalog matches the mod's.

## Status and first-run checklist

This is a first version. The harness is covered by tests. The mod was compiled against stubs generated from the
official MCP `stable_39` mappings, because the Forge Maven was not reachable from the build machine; it has not
been run in a game yet. On the first run, check:

1. the server log shows `Agent bridge API listening on http://127.0.0.1:8765/v1/`;
2. `/agent spawn Bob` makes Bob appear and show in the tab list;
3. `hq-agents act Bob move_to ...` makes him walk (not slide or teleport), and he takes fall damage;
4. `hq-agents act Bob mine ...` takes about as long as it would for you, and drops items;
5. `hq-agents observe Bob` lists the blocks in front of him and nothing behind walls.

Known gaps are listed at the end of [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#known-gaps).
