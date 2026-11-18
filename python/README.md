# grow-datadog-logs (Python)

Python SDK for [Datadog Log Management](https://grow-codes-packages.github.io/datadog-logs/) by Grow Codes.
Unofficial and community maintained; "Datadog Log Management" is a trademark of Datadog, Inc..

```bash
pip install grow-datadog-logs
```

```python
from grow_datadog_logs import DatadogLogsClient, DatadogLogsConfig

# Reads GROW_DATADOG_LOGS_URL, GROW_DATADOG_LOGS_TOKEN, ... from the environment.
with DatadogLogsClient.from_env() as client:
    print(client.health())
    print(client.server_version())
```

The async client has the same methods:

```python
import asyncio
from grow_datadog_logs import AsyncDatadogLogsClient

async def main() -> None:
    async with AsyncDatadogLogsClient.from_env() as client:
        print(await client.health())

asyncio.run(main())
```

- Python 3.10 or newer, typed (`py.typed`), `httpx` based, environment proxies ignored unless
  configured.
- Retries with full-jitter backoff and `Retry-After`, connect/request/total timeouts,
  cancellation, hooks and a silent-by-default `logging` logger named `grow_datadog_logs`.
- Credentials are wrapped in `Secret` and never appear in `repr()`, logs or error messages.
- `grow_datadog_logs.testing` ships in-memory fakes and the conformance runner.

Documentation: https://grow-codes-packages.github.io/datadog-logs/ ·
Source: https://github.com/grow-codes-packages/datadog-logs · Licence: Apache-2.0
