# grow-datadog-logs (Python)

Python SDK for [Datadog Log Management](https://grow-codes-packages.github.io/datadog-logs/) by Grow Codes.
Unofficial and community maintained; "Datadog Log Management" is a trademark of Datadog, Inc. Not affiliated with or endorsed by Datadog.

```bash
pip install grow-datadog-logs
```

```python
import grow_datadog_logs as sdk

# Reads DD_API_KEY, DD_APP_KEY, DD_SITE, DD_SERVICE, DD_ENV, DD_TAGS, GROW_DATADOG_LOGS_* ...
with sdk.DatadogLogsClient.from_env() as client:
    assert client.validate_api_key()
    logs = client.log_client(resource={"service.name": "checkout"})  # batching, gzip
    logs.logger().info("user signed in", {"user": {"id": 42}})
    logs.flush(10)

    query = client.query()  # needs the application key
    page = query.search_logs('service:checkout "signed in"', from_="now-1h", limit=50)
    counts = query.aggregate_logs(sdk.DatadogCompute("count"), [sdk.DatadogGroupBy("status")])
# close() flushed every log client
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
