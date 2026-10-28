"""HTTP client for the AgentBridge mod: the nerves between the harness and the avatar in the game."""
from __future__ import annotations

from typing import Any

import httpx

from .config import BridgeSettings


class BridgeError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(f"{status}: {message}")
        self.status = status
        self.message = message


class Bridge:
    def __init__(self, settings: BridgeSettings, transport: httpx.AsyncBaseTransport | None = None):
        if not settings.token:
            raise ValueError("No bridge token: set AGENTBRIDGE_TOKEN (see config/agentbridge.cfg on the server)")
        self._settings = settings
        self._client = httpx.AsyncClient(
            base_url=settings.url.rstrip("/") + "/v1",
            headers={"Authorization": f"Bearer {settings.token}"},
            timeout=settings.timeout_s,
            transport=transport,
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _request(self, method: str, path: str, *, timeout: float | None = None, **kwargs: Any) -> Any:
        try:
            response = await self._client.request(
                method, path, timeout=timeout if timeout is not None else self._settings.timeout_s, **kwargs)
        except httpx.TransportError as e:
            raise BridgeError(0, f"cannot reach the AgentBridge mod at {self._settings.url} ({e!r})") from e
        try:
            body = response.json()
        except ValueError:
            body = {"error": response.text}
        if response.status_code >= 400:
            raise BridgeError(response.status_code, body.get("error", response.text) if isinstance(body, dict) else str(body))
        return body

    async def health(self) -> dict[str, Any]:
        return await self._request("GET", "/health")

    async def agents(self) -> list[dict[str, Any]]:
        return (await self._request("GET", "/agents"))["agents"]

    async def spawn(self, name: str, near_player: str | None = None) -> dict[str, Any]:
        body: dict[str, Any] = {"name": name}
        if near_player:
            body["near_player"] = near_player
        return await self._request("POST", "/agents", json=body)

    async def despawn(self, name: str) -> None:
        await self._request("DELETE", f"/agents/{name}")

    async def observe(self, name: str, panorama: bool = False) -> dict[str, Any]:
        return await self._request("GET", f"/agents/{name}/observe", params={"panorama": str(panorama).lower()})

    async def act(self, name: str, action: str, args: dict[str, Any], timeout_s: float) -> dict[str, Any]:
        """Runs one body action and waits for it to finish (or for timeout_s, then returns its running status)."""
        return await self._request(
            "POST", f"/agents/{name}/act",
            json={"action": action, "args": args, "wait": True, "timeout_ms": int(timeout_s * 1000)},
            timeout=timeout_s + self._settings.timeout_s,
        )

    async def action_status(self, name: str) -> dict[str, Any]:
        return await self._request("GET", f"/agents/{name}/action")

    async def stop(self, name: str) -> None:
        await self._request("POST", f"/agents/{name}/stop")

    async def events(self, after: int, wait_ms: int = 25_000) -> tuple[list[dict[str, Any]], int]:
        body = await self._request("GET", "/events", params={"after": after, "wait_ms": wait_ms},
                                   timeout=wait_ms / 1000 + self._settings.timeout_s)
        return body["events"], body["last_id"]

    async def recipes(self, item: str, limit: int = 3) -> dict[str, Any]:
        return await self._request("GET", "/recipes", params={"item": item, "limit": limit})
