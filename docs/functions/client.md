# Client and status

Datadog has no health or version endpoint, so `health()`, `ping()` and `validateApiKey()` all use
`GET https://api.<site>/api/v1/validate` with the API key; `serverVersion()` is `UNSUPPORTED`.
See also [Client](../client.md) for the lifecycle rules.

| Function | Request | Result | Errors |
| - | - | - | - |
| `fromEnv()` | — | client | `CONFIG` (no API key, unknown site, invalid value) |
| `health()` | `GET /api/v1/validate` | `HealthStatus`: 2xx `ok`; 401/403 `unauthorized` (`healthy=false`); 429 `degraded`; 5xx `unhealthy`; network `unreachable` | only `CANCELLED`, `CONFIG` |
| `ping()` | `GET /api/v1/validate` | latency | 403 → `AUTHENTICATION`; 2xx without `{"valid": true}` → `UNAVAILABLE` |
| `validateApiKey()` | `GET /api/v1/validate` | `true` / `false` (403) | other failures as mapped errors |
| `serverVersion()` | none | — | always `UNSUPPORTED` |
| `close()` | flushes log clients | — | never; later calls raise `CANCELLED` |

## Check the key

=== "TypeScript"

    ```ts
    const client = DatadogLogsClient.fromEnv();
    const health = await client.health();       // { healthy, status, details: { apiKeyValid } }
    if (!(await client.validateApiKey())) throw new Error("check DD_API_KEY and DD_SITE");
    console.log(await client.ping(), "ms");
    await client.serverVersion().catch((e) => console.log(e.code)); // "UNSUPPORTED"
    ```

=== "Rust"

    ```rust
    let client = DatadogLogsClient::from_env()?;
    let health = client.health().await?;
    if !client.validate_api_key().await? {
        eprintln!("check DD_API_KEY and DD_SITE");
    }
    println!("{:?}", client.ping().await?);
    assert_eq!(client.server_version().await.unwrap_err().code(), ErrorCode::Unsupported);
    ```

=== "Go"

    ```go
    client, err := datadoglogs.NewClientFromEnv()
    health, _ := client.Health(ctx)
    valid, err := client.ValidateAPIKey(ctx)
    latency, err := client.Ping(ctx)
    _, err = client.ServerVersion(ctx) // errors.Is(err, datadoglogs.ErrUnsupported)
    ```

=== "Python"

    ```python
    client = DatadogLogsClient.from_env()
    health = client.health()
    if not client.validate_api_key():
        raise SystemExit("check DD_API_KEY and DD_SITE")
    print(client.ping(), "ms")
    try:
        client.server_version()
    except UnsupportedError:
        pass
    ```

=== "Java"

    ```java
    DatadogLogsClient client = DatadogLogsClient.fromEnv();
    HealthStatus health = client.health();
    if (!client.validateApiKey()) throw new IllegalStateException("check DD_API_KEY and DD_SITE");
    Duration latency = client.ping();
    // client.serverVersion() throws UnsupportedException
    ```

## Sites and tags

The site resolver maps a site to its two base URLs and `parseTags` applies the `DD_TAGS` rules
(comma and/or whitespace separated, duplicates dropped).

| Language | Site resolver | Tags |
| - | - | - |
| TypeScript | `resolveSite("datadoghq.eu")` → `{ site, intakeUrl, apiUrl }`, `DATADOG_SITES`, `isDatadogSite` | `parseTags("a:1, b:2")` |
| Rust | `resolve_site("datadoghq.eu")?` → `SiteEndpoints` | `parse_tags("a:1, b:2")` |
| Go | `datadoglogs.ResolveSite("datadoghq.eu")`, `datadoglogs.Sites` | `datadoglogs.ParseTags("a:1, b:2")` |
| Python | `resolve_site("datadoghq.eu")` | `parse_tags("a:1, b:2")` |
| Java | `DatadogSites.resolve("datadoghq.eu")` | `DatadogLogsConfig.parseTags("a:1, b:2")` |
