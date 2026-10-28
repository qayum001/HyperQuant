# Models for a 6 GB GPU

What to run locally, what exists on Hugging Face for Minecraft, and how to train a small model that is good at
*this* agent's job.

**Short version**

1. **Today, without training:** run a 4B general model (Qwen3.5-4B, Q4) as the brain through Ollama, with the
   visual cortex in `rules` mode, so only one model sits in VRAM.
2. **Minecraft models on Hugging Face** (Andy-4, Andy-4-micro, MineMA) exist, but they were trained for other
   interfaces and for modern Minecraft. Neither the interface nor the version matches ours, so they are not
   worth using as they are.
3. **Best path:** distillation. Let a strong model play real tasks, keep its decisions from tasks that were
   verified as done, and LoRA-train a 4B model on them. Train in a free Colab or Kaggle notebook, then run the
   result on your 6 GB card. The harness now records everything this needs.

Research notes: huggingface.co, ollama.com and unsloth.ai could not be opened from the session that wrote this,
so the figures below come from search results quoting those pages. Check the model cards before relying on a
number.

## What exists on Hugging Face

| Model | Base, size | Trained on | On 6 GB | Use for us |
|---|---|---|---|---|
| [Andy-4](https://ollama.com/sweaterdog/andy-4:latest) (Sweaterdog) | Llama 3.1 8B | ~62k Mindcraft conversations (~179k messages); trained 3 weeks on one RTX 3090 | Q4 about 5 GB: tight with an 8k context | Plays Mindcraft, a Mineflayer bot with `!command` actions on modern Minecraft |
| [Andy-4-micro](https://huggingface.co/Sweaterdog/Andy-4-micro) | Qwen2.5-1.5B (~1.8B), 8k context | same data family | easily | The local model [Mindcraft](https://github.com/kolbytn/mindcraft) recommends (`andy-4:micro-q8_0`); same interface mismatch |
| [MineMA-8B / 70B](https://huggingface.co/Aiwensile2/MineMA-8B/commit/2c64114c43c092df89b0fe01492e0c92d92fbe55) (ODYSSEY, IJCAI-25) | Llama 3 8B / 70B Instruct, LoRA | 390,317 Minecraft Wiki Q&A pairs generated with GPT-3.5 | 8B Q4 tight | Answers questions about the game; not trained to act. CC BY 4.0 |
| `hlillemark/combined_sft_mc_filtered` | – | [73 Mindcraft trajectories](https://huggingface.co/datasets/hlillemark/mc_combined_sa_ma_dataset/commit/828c4b9cf9d7eb55ae53b3abad24dcf0ee5ffc6c) | – | Too little data to matter |
| CraftJarvis OpenHA ([paper](https://arxiv.org/pdf/2509.13347)) | Qwen2-VL 7B | Screen pixels in, keyboard and mouse out | no | The opposite of this project's design |

Datasets worth knowing about: the Minecraft QA-pairs instruction dataset behind MineMA (390k entries, CC BY 4.0),
and Andy-4-base-1, Andy-4-base-2 and Andy-4-ft (Mindcraft conversations). Andy models and data are under the
"Andy" license. Its 1.0 text requires a derivative to have "Andy" in its name or to credit
https://huggingface.co/Sweaterdog, plus the line "This work uses data and models created by @Sweaterdog".
Read the current 1.1 text before using it.

### Why not plug one of them in as it is

* **Interface.** Andy expects Mindcraft's prompts and replies like `!collectBlocks("oak_log", 10)`; MineMA
  answers questions. Our brain must read our observation format and answer with one JSON action from our
  catalog. Small fine-tunes are especially bad at switching to a format they were not trained on.
* **Game version.** All of them learned Minecraft 1.13+ (mostly 1.20/1.21). Version 1.12.2 predates "the
  flattening": `oak_log` and `birch_planks` do not exist there; it is `minecraft:log` and `minecraft:planks`
  with metadata. Knowledge of the new names actively misleads an agent on 1.12.2.
* **Mods.** No model knows your modpack. Our agent learns it from what it sees and from `lookup_recipe`, the
  same way it handles vanilla.

They can still be a starting point for training (Andy-4-micro is already a Minecraft-flavoured 1.5B model), but
the gain is small compared to training on our own format, and Andy's license follows any derivative.

## Running on 6 GB today

VRAM is the model weights plus the KV cache for the context plus about 0.5 GB overhead. Reported Q4 sizes:

| Model | Q4 size | Notes |
|---|---|---|
| Qwen3.5-4B | 2.7–3.3 GB | Apache 2.0, native tool calling, the usual recommendation for 6 GB cards |
| Qwen3.5-2B / 0.8B | smaller (0.8B ≈ 1 GB) | weaker decisions; fine as a separate cortex if you insist on an LLM cortex |
| Qwen3-4B-Instruct-2507 | ~2.5 GB | older, never "thinks", so no thinking switch needed |
| Phi-4-mini | ~2.5 GB | reported as reliable at structured JSON output |
| Qwen3.5-9B, Gemma 4 E4B | 6.6 GB and up | too big for 6 GB with any useful context |

Prompt sizes in this harness: the full brain system prompt is about 7,400 characters (~1,900 tokens), and the
compact one about 1,100 (~300 tokens). A turn's observation, history and notes are typically 1,000–2,500
tokens. An 8k context covers it.

```toml
[llm]
base_url = "http://localhost:11434/v1"
# Thinking models may answer only in a "reasoning" field on Ollama's OpenAI endpoint; this turns thinking off
# on recent Ollama builds. Other servers: vLLM / llama.cpp use chat_template_kwargs = { enable_thinking = false }.
extra_body = { reasoning_effort = "none" }

[cortex]
mode = "rules"              # deterministic text: no second model in VRAM, no extra calls

[brain]
models = ["qwen3.5:4b"]     # check the exact tag with `ollama list` / the Ollama library
temperature = 0.3
prompt_style = "full"       # switch to "compact" for a model fine-tuned on compact prompts

[judge]
enabled = true              # reuses the brain model
```

Ollama's default context window is smaller than these prompts, and it truncates them **silently**. Start Ollama
with `OLLAMA_CONTEXT_LENGTH=8192`, or create a model with `PARAMETER num_ctx 8192`
(see `training/Modelfile.example`).

## Training a Minecraft brain on your own data

The harness now writes, for every brain turn, the exact system prompt, user prompt and reply into the task trace,
plus the outcome of the action and whether a "done" claim passed the check. That is a ready-made distillation
dataset: a strong teacher plays, a small student learns to make the same decisions.

### 1. Collect teacher data

Run real tasks on your server with a strong model as the brain: a paid OpenRouter model, or a large free one.
Keep the default `full` prompt for the teacher, mix task types (gathering, crafting chains, smelting, using your
mods' machines, fighting, building), and let the judge stay on, because only verified tasks are used. As a rule
of thumb, start with a couple of hundred finished tasks (a few thousand decisions) before the first training
run. Andy-4 used about 62k conversations, so more is better.

### 2. Export

```bash
hq-agents dataset traces/ --out train.jsonl --val-fraction 0.1 --prompt-style compact
```

* One example per brain turn of a task that ended **done** and passed the check.
* Turns whose action failed are left out (`--keep-failures` keeps them). Later turns still show those failures
  in their history, so the student learns how the teacher recovered.
* `--prompt-style compact` swaps in the short system prompt. The student learns the full catalog's behaviour
  from the teacher's choices, but at run time only needs the 300-token prompt, which leaves more of a small
  context for the scene.
* The validation split is by task, so no task appears in both files.

### 3. Train

| Option | Where | Memory (Unsloth figures) |
|---|---|---|
| **Qwen3.5-4B, 16-bit LoRA** (recommended) | free Colab T4 or Kaggle (about 30 GPU hours a week) | ~10 GB; Unsloth advises against 4-bit QLoRA for Qwen3.5 |
| Qwen3-4B-Instruct-2507, 4-bit QLoRA | your 6 GB card | QLoRA minimum ~3.5 GB at 3B and ~5 GB at 7B, so about 4–5 GB at short sequences: tight with 4k-token examples |
| Qwen3.5-2B, 16-bit LoRA | your 6 GB card | ~5 GB at short sequences |

For Qwen3.5, open Unsloth's official Qwen3.5 notebook and load `train.jsonl` as the dataset (it is in the
standard `messages` chat format). For the local QLoRA route, `training/train_lora.py` is a ready script:

```bash
pip install unsloth
python training/train_lora.py --train train.jsonl --val train.val.jsonl --out minecraft-brain
# out of memory? --max-seq-length 3072, or --model unsloth/Qwen3-1.7B
```

It trains only on the replies (the prompts are masked), drops examples that do not fit instead of truncating
them, and exports a Q4_K_M GGUF. It is a template written without a GPU at hand and has not been run yet.

### 4. Use it

```bash
ollama create minecraft-brain -f training/Modelfile.example   # after fixing the GGUF path inside
```

Then set `[brain] models = ["minecraft-brain"]` and `prompt_style = "compact"`.

### 5. Measure and repeat

Give the student and the teacher the same set of tasks and compare from the traces: share of tasks done,
decisions per task, invalid replies, rejected "done" claims. Then add the student's own successful tasks to the
data, and teacher runs on the tasks it fails, and train again.

## Sources

* Andy-4 and Andy-4-micro: [Ollama Andy-4](https://ollama.com/sweaterdog/andy-4:latest),
  [Andy-4-micro on Hugging Face](https://huggingface.co/Sweaterdog/Andy-4-micro),
  [Andy-4-micro q8](https://ollama.com/Sweaterdog/Andy-4:micro-q8_0),
  [Andy-3.6-small card](https://huggingface.co/Sweaterdog/Andy-3.6-small/blob/main/README.md)
* Mindcraft: [kolbytn/mindcraft](https://github.com/kolbytn/mindcraft); MineCollab paper
  [arXiv 2504.17950](https://arxiv.org/pdf/2504.17950)
* MineMA / ODYSSEY: [IJCAI-25 paper](https://www.ijcai.org/proceedings/2025/22),
  [arXiv 2407.15325](https://arxiv.org/pdf/2407.15325),
  [MineMA-8B commit](https://huggingface.co/Aiwensile2/MineMA-8B/commit/2c64114c43c092df89b0fe01492e0c92d92fbe55)
* OmniJARVIS [arXiv 2407.00114](https://arxiv.org/pdf/2407.00114), OpenHA [arXiv 2509.13347](https://arxiv.org/pdf/2509.13347)
* Qwen3.5 small models: [Qwen3.5-0.8B card](https://huggingface.co/Qwen/Qwen3.5-0.8B),
  [Qwen3.5-9B card](https://huggingface.co/Qwen/Qwen3.5-9B), [Unsloth: run Qwen3.5](https://unsloth.ai/docs/models/qwen3.5)
* Fine-tuning memory: [Unsloth Qwen3.5 fine-tuning guide](https://unsloth.ai/docs/models/qwen3.5/fine-tune),
  [Unsloth requirements](https://docs.unsloth.ai/get-started/beginner-start-here/unsloth-requirements),
  [Unsloth Qwen3-2507](https://unsloth.ai/docs/models/tutorials/qwen3-how-to-run-and-fine-tune/qwen3-2507)
* 6 GB model choices: [promptquorum 6 GB](https://www.promptquorum.com/prompt-bites/best-local-llm-6gb-vram),
  [promptquorum tool calling](https://www.promptquorum.com/power-local-llm/best-local-models-tool-calling-2026),
  [runaihome 6 GB](https://runaihome.com/blog/best-local-llm-6gb-vram-2026/),
  [localaimaster 6 GB](https://localaimaster.com/vram/best-llm-6gb-vram)
* Thinking switches: [Qwen3-4B card](https://huggingface.co/Qwen/Qwen3-4B),
  [Ollama issue 10809](https://github.com/ollama/ollama/issues/10809),
  [Hermes agent issue on empty content](https://github.com/NousResearch/hermes-agent/issues/46131),
  [llama.cpp PR 26045](https://github.com/ggml-org/llama.cpp/pull/26045)
* Free GPUs: [GMI Cloud overview](https://www.gmicloud.ai/en/blog/best-free-gpu-cloud-options-for-ai-startups-and-researchers)
