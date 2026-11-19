"""Helpers for the ``datadog/fakeintake`` harness endpoints (not Datadog APIs).

* :class:`FakeIntake` reads captured payloads back (raw view) and installs response overrides.
* :class:`FakeintakeQuery` is a ``LogQueryClient`` over the capture: ``search`` returns the
  captured intake logs whose message contains every term of the query (quotes ignored),
  converted back to ``LogRecord``. It lets the conformance logging scenarios assert send -> read
  back against the real container; Datadog's own search API is not available there (it is
  unit-tested with fixtures, run against wire stubs here and proven by the live sandbox job).
"""

from __future__ import annotations

import base64
import gzip
import json
import zlib
from collections.abc import Mapping
from datetime import datetime, timezone
from typing import Any

import httpx

import grow_datadog_logs as sdk

INTAKE = "/api/v2/logs"


class Payload:
    """One captured request body (decoded)."""

    def __init__(self, raw: bytes) -> None:
        self.raw = raw
        self.logs: list[dict[str, Any]] = []
        try:
            doc = json.loads(raw)
        except ValueError:
            return
        if isinstance(doc, list):
            self.logs = [item for item in doc if isinstance(item, dict)]
        elif isinstance(doc, dict):
            self.logs = [doc]


class FakeIntake:
    def __init__(self, env: Mapping[str, str]) -> None:
        base = env.get("FAKEINTAKE_URL") or env["GROW_DATADOG_LOGS_URL"]
        self.base = base.rstrip("/")
        self.http = httpx.Client(timeout=60.0)

    def payloads(self, endpoint: str = INTAKE) -> list[Payload]:
        resp = self.http.get(f"{self.base}/fakeintake/payloads", params={"endpoint": endpoint})
        resp.raise_for_status()
        out = []
        for item in resp.json().get("payloads") or []:
            data = base64.b64decode(item.get("data") or "")
            encoding = item.get("encoding")
            if encoding == "gzip":
                data = gzip.decompress(data)
            elif encoding == "deflate":
                data = zlib.decompress(data)
            out.append(Payload(data))
        return out

    def override(self, method: str, endpoint: str, status: int, body: str) -> None:
        spec = {
            "method": method,
            "endpoint": endpoint,
            "status_code": status,
            "content_type": "application/json",
            "body": base64.b64encode(body.encode()).decode(),
        }
        resp = self.http.post(f"{self.base}/fakeintake/configure/override", json=spec)
        resp.raise_for_status()

    def restore_defaults(self) -> None:
        self.override("POST", INTAKE, 202, "{}")
        self.override("GET", "/api/v1/validate", 200, '{"valid":true}')

    def logs_with(self, marker: str) -> tuple[list[dict[str, Any]], list[Payload]]:
        """Captured intake logs whose message contains ``marker`` and the payloads holding them."""
        logs: list[dict[str, Any]] = []
        hits: list[Payload] = []
        for payload in self.payloads(INTAKE):
            found = [log for log in payload.logs if marker in str(log.get("message", ""))]
            if found:
                logs.extend(found)
                hits.append(payload)
        return logs, hits


def captured_record(log: Mapping[str, Any]) -> sdk.LogRecord:
    """A captured Datadog log turned back into a ``LogRecord``."""
    attributes: dict[str, Any] = {}
    resource: dict[str, Any] = {}
    values: dict[str, Any] = {"body": "", "severity_text": "INFO"}
    for key, value in log.items():
        if key == "message":
            values["body"] = str(value)
        elif key == "status":
            values["severity_text"] = str(value)
        elif key == "timestamp" and isinstance(value, int):
            values["timestamp"] = datetime.fromtimestamp(value / 1000, tz=timezone.utc)
        elif key == "service":
            resource["service.name"] = value
        elif key == "hostname":
            resource["host.name"] = value
        elif key == "otel.trace_id":
            values["trace_id"] = value
        elif key == "otel.span_id":
            values["span_id"] = value
        else:
            attributes[key] = sdk.to_attr_value(value)
    return sdk.LogRecord(
        severity=sdk.severity_from_status(values["severity_text"]),
        attributes=attributes,
        resource=resource,
        **values,
    )


class FakeintakeQuery(sdk.BaseLogQueryClient):
    """Category query client over the fakeintake capture (see the module docs)."""

    def __init__(self, intake: FakeIntake) -> None:
        self.intake = intake

    def search(
        self,
        query: str,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int = 100,
        order: sdk.SearchOrder = "desc",
        cursor: str | None = None,
    ) -> sdk.LogSearchResult:
        terms = query.replace('"', " ").split()
        records = [
            captured_record(log)
            for payload in self.intake.payloads(INTAKE)
            for log in payload.logs
            if all(term in str(log.get("message", "")) for term in terms)
        ]
        return sdk.LogSearchResult(records=records)
