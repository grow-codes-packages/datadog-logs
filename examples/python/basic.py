"""Datadog Log Management with the synchronous client.

Configuration from the environment, API key validation, sending logs through the batching
log client and the stdlib ``logging`` handler, then searching and aggregating them back.

    DD_API_KEY=... DD_APP_KEY=... DD_SITE=datadoghq.eu python examples/python/basic.py

Against the Docker fakeintake (no search there, the example stops after sending):

    GROW_DATADOG_LOGS_URL=http://datadog-logs:80 DD_API_KEY=test python examples/python/basic.py
"""

from __future__ import annotations

import logging
import time

import grow_datadog_logs as sdk


def main() -> None:
    # Reads DD_API_KEY, DD_APP_KEY, DD_SITE, DD_SERVICE, DD_ENV, DD_TAGS, GROW_DATADOG_LOGS_* …
    config = sdk.DatadogLogsConfig.from_env(service="grow-example")
    print(config)  # keys print as [REDACTED]
    with sdk.DatadogLogsClient(config) as client:
        if not client.validate_api_key():
            raise SystemExit("Datadog rejected the API key (check DD_API_KEY and DD_SITE)")
        print("api key: valid")

        logs = client.log_client()  # batching, gzip, 1000 entries / 5 MB per request
        marker = f"example-{time.time_ns()}"
        logs.logger("example", {"run": marker}).info(f"user signed in {marker}", {"user.id": 42})

        stdlib = logging.getLogger("example.app")
        stdlib.addHandler(sdk.LogHandler(logs))
        stdlib.warning("disk almost full %s", marker, extra={"percent": 91})
        logs.flush(30)
        print("sent:", logs.stats())

        try:
            query = client.query()
        except sdk.ConfigError:
            print("no application key: skipping search")
            return
        try:
            record = query.wait_for_log(f'"{marker}"', timeout_s=90)
        except sdk.UnavailableError as err:
            print(f"search is not available here: {err}")
            return
        print(f"found: {record.body} [{record.severity_text}] {record.resource}")
        result = query.aggregate_logs(
            sdk.DatadogCompute("count"),
            [sdk.DatadogGroupBy("status")],
            query=f'"{marker}"',
            from_="now-15m",
        )
        for bucket in result.buckets:
            print(f"status={bucket.by.get('status')} count={bucket.number('c0')}")


if __name__ == "__main__":
    main()
