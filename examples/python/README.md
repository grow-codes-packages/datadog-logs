# Python examples

Install the package, set your Datadog keys and run an example:

```bash
pip install grow-datadog-logs
export DD_API_KEY=...          # required
export DD_APP_KEY=...          # only for search / aggregate / indexes
export DD_SITE=datadoghq.eu    # default datadoghq.com
python examples/python/basic.py
```

Against the Docker fakeintake set `GROW_DATADOG_LOGS_URL=http://datadog-logs:80` and any
`DD_API_KEY` (fakeintake has no search API, so the examples stop after sending).

| File | Shows |
| - | - |
| `basic.py` | sync client: validate the key, send through the log client and `logging.Handler`, wait for the log, aggregate |
| `async_basic.py` | async client: health, async log client, paged search with `iterate_logs` |
| `hooks_and_logging.py` | request hooks, the `grow_datadog_logs` logger, retries and timeouts |
