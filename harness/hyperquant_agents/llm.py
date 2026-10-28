"""Minimal OpenAI-compatible chat client (OpenRouter by default) with retries and model fallback."""
from __future__ import annotations

import asyncio
import json
import logging
import re
from dataclasses import dataclass
from typing import Any

import httpx

from .config import LLMSettings, ModelRole

log = logging.getLogger(__name__)

RETRYABLE = {408, 409, 425, 429, 500, 502, 503, 504}


class LLMError(Exception):
    pass


@dataclass
class LLMReply:
    text: str
    model: str


class LLMClient:
    def __init__(self, settings: LLMSettings, transport: httpx.AsyncBaseTransport | None = None):
        headers = {"X-Title": settings.app_name, "HTTP-Referer": settings.app_url}
        if settings.api_key:
            headers["Authorization"] = f"Bearer {settings.api_key}"
        self._settings = settings
        self._client = httpx.AsyncClient(base_url=settings.base_url.rstrip("/"), headers=headers,
                                         timeout=settings.timeout_s, transport=transport)

    async def aclose(self) -> None:
        await self._client.aclose()

    async def chat(self, messages: list[dict[str, str]], role: ModelRole) -> LLMReply:
        """Asks each model of the role in turn; transient failures are retried with backoff first."""
        if not role.models:
            raise LLMError("no models configured")
        errors: list[str] = []
        for model in role.models:
            for attempt in range(self._settings.max_retries + 1):
                try:
                    text = await self._complete(model, messages, role)
                    return LLMReply(text=text, model=model)
                except _Retry as e:
                    errors.append(f"{model}: {e}")
                    if attempt < self._settings.max_retries:
                        await asyncio.sleep(e.delay if e.delay is not None else min(2 ** attempt, 20))
                except _Skip as e:
                    errors.append(f"{model}: {e}")
                    break
            log.warning("model %s failed, trying the next one", model)
        raise LLMError("all models failed: " + "; ".join(errors[-4:]))

    async def _complete(self, model: str, messages: list[dict[str, str]], role: ModelRole) -> str:
        payload = {**self._settings.extra_body, "model": model, "messages": messages,
                   "temperature": role.temperature, "max_tokens": role.max_tokens}
        try:
            response = await self._client.post("/chat/completions", json=payload)
        except httpx.TransportError as e:
            raise _Retry(repr(e)) from e
        if response.status_code in RETRYABLE:
            raise _Retry(f"HTTP {response.status_code} {response.text[:200]}", _retry_after(response))
        if response.status_code >= 400:
            raise _Skip(f"HTTP {response.status_code} {response.text[:200]}")
        try:
            data = response.json()
            if "error" in data:  # OpenRouter reports some upstream failures inside a 200
                raise _Retry(str(data["error"])[:200])
            message = data["choices"][0]["message"]
            text = message.get("content") or ""
        except (ValueError, KeyError, IndexError, TypeError) as e:
            raise _Retry(f"malformed response: {e!r}") from e
        text = strip_reasoning(text)
        if not text.strip():
            if message.get("reasoning") or message.get("reasoning_content"):
                raise _Skip("the model only produced reasoning; turn thinking off for it "
                            '(e.g. [llm] extra_body = { reasoning_effort = "none" } for Ollama)')
            raise _Retry("empty reply")
        return text


class _Retry(Exception):
    def __init__(self, message: str, delay: float | None = None):
        super().__init__(message)
        self.delay = delay


class _Skip(Exception):
    pass


def _retry_after(response: httpx.Response) -> float | None:
    try:
        return min(float(response.headers["retry-after"]), 60.0)
    except (KeyError, ValueError):
        return None


def strip_reasoning(text: str) -> str:
    """Some (free) reasoning models put their chain of thought in the reply; drop it."""
    return re.sub(r"<think>.*?</think>", "", text, flags=re.S).strip()


def extract_json(text: str) -> dict[str, Any]:
    """Finds the first JSON object in a model reply, tolerating code fences and chatter around it."""
    text = strip_reasoning(text)
    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, flags=re.S)
    candidates = [fenced.group(1)] if fenced else []
    start = text.find("{")
    while start != -1:
        end = _matching_brace(text, start)
        if end is not None:
            candidates.append(text[start:end + 1])
        start = text.find("{", (end if end is not None else start) + 1)
    for candidate in candidates:
        for attempt in (candidate, re.sub(r",\s*([}\]])", r"\1", candidate)):  # trailing commas
            try:
                value = json.loads(attempt)
            except ValueError:
                continue
            if isinstance(value, dict):
                return value
    raise ValueError("no JSON object found in the reply")


def _matching_brace(text: str, start: int) -> int | None:
    depth = 0
    in_string = False
    escaped = False
    for i in range(start, len(text)):
        ch = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
        elif ch == '"':
            in_string = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i
    return None
