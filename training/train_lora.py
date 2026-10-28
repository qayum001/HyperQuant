"""LoRA fine-tune of a small model on agent decisions exported with `hq-agents dataset`.

    pip install unsloth          # brings torch, transformers, trl, peft, datasets
    python train_lora.py --train train.jsonl --val train.val.jsonl --out minecraft-brain

Written as a template and not run yet: it was authored without a GPU. The defaults target a text-only Qwen3 4B
instruct model with 4-bit QLoRA, which is the variant most likely to fit a 6 GB card. For Qwen3.5 models, Unsloth
recommends 16-bit LoRA instead of 4-bit (drop --load-in-4bit) and that needs more memory: train those on a free
Colab or Kaggle GPU and only run the result locally. See docs/MODELS.md.
"""
from __future__ import annotations

import argparse
import dataclasses
import inspect


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", default="unsloth/Qwen3-4B-Instruct-2507")
    ap.add_argument("--train", required=True, help="JSONL from `hq-agents dataset`")
    ap.add_argument("--val", help="validation JSONL")
    ap.add_argument("--out", default="minecraft-brain")
    ap.add_argument("--max-seq-length", type=int, default=4096,
                    help="longer examples are dropped; use --prompt-style compact when exporting to keep them short")
    ap.add_argument("--load-in-4bit", action=argparse.BooleanOptionalAction, default=True,
                    help="QLoRA (default). Use --no-load-in-4bit for Qwen3.5")
    ap.add_argument("--rank", type=int, default=16)
    ap.add_argument("--epochs", type=float, default=2.0)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--batch-size", type=int, default=1)
    ap.add_argument("--grad-accum", type=int, default=8)
    ap.add_argument("--user-marker", default="<|im_start|>user\n",
                    help="chat-template text before a user turn (Qwen default; Llama 3: '<|start_header_id|>user<|end_header_id|>\\n\\n')")
    ap.add_argument("--assistant-marker", default="<|im_start|>assistant\n",
                    help="chat-template text before a reply (Llama 3: '<|start_header_id|>assistant<|end_header_id|>\\n\\n')")
    ap.add_argument("--gguf", default="q4_k_m", help="GGUF quantization to export for Ollama/llama.cpp; '' to skip")
    return ap.parse_args()


def main() -> None:
    args = parse_args()
    # Imported here so --help works without a GPU stack installed.
    from unsloth import FastLanguageModel
    from unsloth.chat_templates import train_on_responses_only
    from datasets import load_dataset
    from trl import SFTConfig, SFTTrainer

    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=args.model, max_seq_length=args.max_seq_length, load_in_4bit=args.load_in_4bit)
    model = FastLanguageModel.get_peft_model(
        model, r=args.rank, lora_alpha=args.rank, lora_dropout=0, bias="none",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        use_gradient_checkpointing="unsloth", random_state=3407)

    files = {"train": args.train}
    if args.val:
        files["validation"] = args.val
    data = load_dataset("json", data_files=files)
    data = data.map(lambda batch: {"text": [tokenizer.apply_chat_template(m, tokenize=False) for m in batch["messages"]]},
                    batched=True, remove_columns=["messages"])
    # Drop examples that do not fit instead of truncating them: a cut-off example loses its reply.
    before = len(data["train"])
    data = data.filter(lambda ex: len(tokenizer(ex["text"]).input_ids) <= args.max_seq_length)
    print(f"{len(data['train'])} of {before} training examples fit in {args.max_seq_length} tokens")

    # TRL renames things between releases; pass whichever names this version understands.
    config_fields = {f.name for f in dataclasses.fields(SFTConfig)}
    config = dict(
        dataset_text_field="text", per_device_train_batch_size=args.batch_size,
        gradient_accumulation_steps=args.grad_accum, num_train_epochs=args.epochs, learning_rate=args.lr,
        warmup_ratio=0.05, lr_scheduler_type="cosine", optim="adamw_8bit", weight_decay=0.01, logging_steps=5,
        output_dir=args.out, report_to="none", seed=3407)
    config["max_length" if "max_length" in config_fields else "max_seq_length"] = args.max_seq_length
    config["eval_strategy" if "eval_strategy" in config_fields else "evaluation_strategy"] = \
        "epoch" if args.val else "no"
    trainer_kwargs = dict(model=model, train_dataset=data["train"], eval_dataset=data.get("validation"),
                          args=SFTConfig(**config))
    if "processing_class" in inspect.signature(SFTTrainer.__init__).parameters:
        trainer_kwargs["processing_class"] = tokenizer
    else:
        trainer_kwargs["tokenizer"] = tokenizer
    trainer = SFTTrainer(**trainer_kwargs)
    # Learn the replies only; the long prompts are input, not something to imitate.
    trainer = train_on_responses_only(trainer, instruction_part=args.user_marker, response_part=args.assistant_marker)
    trainer.train()

    model.save_pretrained(f"{args.out}/lora")
    tokenizer.save_pretrained(f"{args.out}/lora")
    if args.gguf:
        model.save_pretrained_gguf(f"{args.out}/gguf", tokenizer, quantization_method=args.gguf)
        print(f"GGUF written to {args.out}/gguf; see training/Modelfile.example to load it into Ollama")


if __name__ == "__main__":
    main()
