// Package datadoglogs is the Grow Codes SDK for Datadog Log Management.
//
// It is an independent HTTP client for the public Datadog Logs APIs: the logs
// intake (POST /api/v2/logs), log search and aggregation (needs an
// application key), log indexes and API key validation.
//
// Create a client from the environment (DD_API_KEY, DD_APP_KEY, DD_SITE,
// DD_SERVICE, DD_ENV, DD_VERSION, DD_TAGS or the GROW_DATADOG_LOGS_* variables)
// or from a Config literal, then send logs through a batching LogClient:
//
//	client, err := datadoglogs.NewClientFromEnv()
//	if err != nil {
//		return err
//	}
//	defer client.Close() // flushes every LogClient
//	logs, err := client.NewLogClient(datadoglogs.LogClientOptions{})
//	if err != nil {
//		return err
//	}
//	logs.Logger("checkout", nil).Info(ctx, "user signed in", "user.id", 42)
//
// Read logs back with the query client:
//
//	q, err := client.Query() // CONFIG error without an application key
//	page, err := q.SearchLogs(ctx, datadoglogs.SearchLogsRequest{Query: `service:checkout "signed in"`, From: "now-1h"})
//	for event, err := range q.IterateLogs(ctx, datadoglogs.SearchLogsRequest{Query: "status:error", Limit: 5000}) { … }
//	res, err := q.AggregateLogs(ctx, []datadoglogs.Compute{{Aggregation: "count"}}, []datadoglogs.GroupBy{{Facet: "service"}}, datadoglogs.AggregateLogsOptions{})
//
// Every error is an *Error; compare codes with errors.Is against the Err*
// sentinels (errors.Is(err, datadoglogs.ErrAuthentication)). Datadog answers
// 403 for missing or invalid keys (and, on the query API, for keys without the
// needed permission); the SDK maps every 403 to AUTHENTICATION. Credentials are wrapped in Secret and never
// appear in errors, hooks or logs.
//
// Datadog is a trademark of Datadog, Inc. This package is not affiliated with
// or endorsed by Datadog, Inc.
package datadoglogs
