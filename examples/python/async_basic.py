"""The async client: health, sending through the async log client and a paged search.

Cancelling the task of a call raises the SDK's CancelledError (also an asyncio.CancelledError).
"""

from __future__ import annotations

import asyncio

import grow_datadog_logs as sdk


async def main() -> None:
    async with sdk.AsyncDatadogLogsClient.from_env() as client:
        print(await client.health())
        logs = client.log_client(resource={"service.name": "grow-example-async"})
        logs.send(sdk.LogInput("async hello", severity=sdk.Severity.INFO, attributes={"n": 1}))
        await logs.flush(30)
        print("sent:", logs.stats())

        if client.config.app_key is None:
            return
        try:
            async for event in client.query().iterate_logs(
                "service:grow-example-async", from_="now-1h", limit=20
            ):
                print(event.timestamp, event.status, event.message)
        except sdk.DatadogLogsError as err:
            print(f"search failed with {err.code}: {err}")
    # close() flushed every log client created by the client


if __name__ == "__main__":
    asyncio.run(main())
