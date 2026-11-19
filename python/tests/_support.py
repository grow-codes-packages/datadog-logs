"""Test support: a programmable local HTTP server and config helpers."""

from __future__ import annotations

import asyncio
import atexit
import dataclasses
import json
import socket
import threading
import time
from collections import defaultdict, deque
from collections.abc import Awaitable
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import grow_datadog_logs as sdk


@dataclasses.dataclass
class Reply:
    status: int = 200
    body: Any = None
    headers: dict[str, str] = dataclasses.field(default_factory=dict)
    delay: float = 0.0  # before the status line
    drip: float = 0.0  # pause between body chunks (slow body)


@dataclasses.dataclass
class Seen:
    method: str
    path: str
    headers: dict[str, str]
    body: bytes


class MockServer:
    """Threaded HTTP server; queue replies per path, inspect the requests it saw."""

    def __init__(self) -> None:
        self.replies: dict[str, deque[Reply]] = defaultdict(deque)
        self.defaults: dict[str, Reply] = {}
        self.seen: list[Seen] = []
        server = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
                pass

            def _handle(self) -> None:
                length = int(self.headers.get("content-length") or 0)
                body = self.rfile.read(length) if length else b""
                path = self.path.split("?", 1)[0]
                server.seen.append(Seen(self.command, self.path, dict(self.headers), body))
                queue = server.replies.get(path)
                reply = queue.popleft() if queue else server.defaults.get(path, Reply())
                if reply.delay:
                    time.sleep(reply.delay)
                if isinstance(reply.body, (bytes, str)):
                    payload = reply.body.encode() if isinstance(reply.body, str) else reply.body
                    ctype = "text/plain"
                else:
                    payload = json.dumps(reply.body if reply.body is not None else {}).encode()
                    ctype = "application/json"
                try:
                    self.send_response(reply.status)
                    self.send_header("Content-Type", ctype)
                    for k, v in reply.headers.items():
                        self.send_header(k, v)
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    if reply.drip:
                        for i in range(len(payload)):
                            self.wfile.write(payload[i : i + 1])
                            self.wfile.flush()
                            time.sleep(reply.drip)
                    else:
                        self.wfile.write(payload)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            do_GET = do_POST = do_PUT = do_DELETE = do_HEAD = _handle

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self) -> str:
        host, port = self.httpd.server_address[:2]
        return f"http://{host!s}:{port}"

    def enqueue(self, path: str, *replies: Reply) -> None:
        self.replies[path].extend(replies)

    def stop(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()


def no_jitter() -> float:
    return 0.0


def fast_retry(**kw: Any) -> sdk.RetryPolicy:
    values: dict[str, Any] = {"initial_backoff": 0.01, "max_backoff": 0.05, "random": no_jitter}
    values.update(kw)
    return sdk.RetryPolicy(**values)


@dataclasses.dataclass(frozen=True, repr=False)
class CoreConfig(sdk.BaseConfig):
    """Generic config for the core transport tests (a ``token`` like the sdk-kit skeleton)."""

    url: str = sdk.DEFAULT_URL
    token: sdk.Secret | None = None

    @classmethod
    def _env_values(cls, reader: sdk.EnvReader) -> dict[str, Any]:
        values = super()._env_values(reader)
        token = reader.string("TOKEN", cls.ENV_VENDOR.get("TOKEN", ()))
        if token is not None:
            values["token"] = sdk.Secret(token)
        return values

    def validate(self) -> CoreConfig:
        super().validate()
        if self.token is not None and not self.token.reveal().strip():
            raise sdk.ConfigError("config: token must not be blank")
        return self


def make_config(url: str, **kw: Any) -> CoreConfig:
    """Config for the generic core transport tests."""
    kw.setdefault("retry", fast_retry())
    return CoreConfig(url=url, **kw)


API_KEY = "test-api-key-0123456789"
APP_KEY = "test-app-key-9876543210"


def dd_config(url: str, **kw: Any) -> sdk.DatadogLogsConfig:
    """A Datadog config pointing both bases at ``url`` (fast retries, test keys)."""
    kw.setdefault("retry", fast_retry())
    kw.setdefault("api_key", API_KEY)
    kw.setdefault("app_key", APP_KEY)
    return sdk.DatadogLogsConfig(url=url, **kw)


FIXTURES = Path(__file__).parent / "fixtures"


def fixture(name: str) -> dict[str, Any]:
    """A recorded fixture (``tests/fixtures/<name>.json``)."""
    doc: dict[str, Any] = json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))
    return doc


def fixture_reply(name: str) -> Reply:
    """The recorded response of a fixture as a :class:`Reply`."""
    resp = fixture(name)["response"]
    headers = {
        k: v
        for k, v in (resp.get("headers") or {}).items()
        if k.lower() not in ("content-type", "content-length")
    }
    body = resp.get("body")
    return Reply(int(resp["status"]), b"" if body is None else body, headers)


_REFUSING: list[socket.socket] = []
atexit.register(lambda: [sock.close() for sock in _REFUSING])


def closed_port_url() -> str:
    """URL of a port that refuses connections.

    The socket stays bound (never listening) for the whole test session: connects are refused,
    and no other server (a parallel MockServer, another process) can take the port meanwhile,
    which a bind-then-close port would allow.
    """
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    _REFUSING.append(sock)
    port = sock.getsockname()[1]
    return f"http://127.0.0.1:{port}"


def spawn_capturing(awaitable: Awaitable[Any], sink: list[BaseException]) -> asyncio.Task[Any]:
    """Run ``awaitable`` as a task and record the exception it raises *inside* the task.

    On Python < 3.11 awaiting a cancelled task raises a fresh ``asyncio.CancelledError``, so the
    SDK's ``CancelledError`` subclass is only observable from within the task itself.
    """

    async def run() -> Any:
        try:
            return await awaitable
        except BaseException as exc:
            sink.append(exc)
            raise

    return asyncio.ensure_future(run())
