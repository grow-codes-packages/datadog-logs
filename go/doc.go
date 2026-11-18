// Package datadoglogs is the Grow Codes SDK for Datadog Log Management.
//
// Create a client from the environment (GROW_DATADOG_LOGS_URL,
// GROW_DATADOG_LOGS_TOKEN, ...) or from a Config literal:
//
//	client, err := datadoglogs.NewClientFromEnv()
//	if err != nil {
//		return err
//	}
//	defer client.Close()
//	status, err := client.Health(ctx)
//
// Every error is an *Error; compare codes with errors.Is against the Err*
// sentinels (errors.Is(err, datadoglogs.ErrNotFound)). Credentials are wrapped in
// Secret and never appear in errors, hooks or logs.
//
// Datadog Log Management is a trademark of Datadog, Inc.. This package is not affiliated
// with or endorsed by Datadog, Inc..
package datadoglogs
