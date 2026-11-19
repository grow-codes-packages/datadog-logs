// Command example shows the Datadog Log Management Go SDK: configuration from
// the environment, API key validation, sending logs through the batching
// LogClient and the slog handler, and searching them back.
//
//	DD_API_KEY=... DD_APP_KEY=... DD_SITE=datadoghq.eu go run .
//
// Against the Docker fakeintake (no search there):
//
//	GROW_DATADOG_LOGS_URL=http://datadog-logs:80 DD_API_KEY=test go run .
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"time"

	datadoglogs "github.com/grow-codes-packages/datadog-logs/go"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := datadoglogs.ConfigFromEnv()
	if err != nil {
		return err // CONFIG error naming the variable, never its value
	}
	if cfg.Service == "" {
		cfg.Service = "grow-example"
	}
	cfg.Hooks = &datadoglogs.Hooks{
		OnError: func(info datadoglogs.ErrorInfo) {
			if info.WillRetry {
				fmt.Fprintf(os.Stderr, "retrying %s %s (attempt %d)\n", info.Method, info.URL, info.Attempt)
			}
		},
	}
	fmt.Println("config:", cfg) // keys print as [REDACTED]

	client, err := datadoglogs.NewClient(cfg)
	if err != nil {
		return err
	}
	defer func() { _ = client.Close() }() // flushes every LogClient

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	valid, err := client.ValidateAPIKey(ctx)
	if err != nil {
		return err
	}
	if !valid {
		return errors.New("Datadog rejected the API key (check DD_API_KEY and DD_SITE)")
	}
	fmt.Println("api key: valid")

	logs, err := client.NewLogClient(datadoglogs.LogClientOptions{})
	if err != nil {
		return err
	}
	marker := fmt.Sprintf("example-%d", time.Now().UnixNano())
	logger := logs.Logger("example", map[string]datadoglogs.AttrValue{"run": marker})
	logger.Info(ctx, "user signed in "+marker, "user.id", 42)
	slog.New(datadoglogs.NewSlogHandler(logs, nil)).Warn("disk almost full "+marker, "percent", 91)
	if err := logs.Flush(ctx); err != nil {
		return err
	}
	fmt.Printf("sent: %+v\n", logs.Stats())

	query, err := client.Query()
	if errors.Is(err, datadoglogs.ErrConfig) {
		fmt.Println("no application key: skipping search")
		return nil
	}
	if err != nil {
		return err
	}
	rec, err := query.WaitForLog(ctx, `"`+marker+`"`, datadoglogs.WaitForLogOptions{Timeout: 90 * time.Second})
	if err != nil {
		return err
	}
	fmt.Printf("found: %s [%s] service=%v\n", rec.Body, rec.SeverityText, rec.Resource["service.name"])

	res, err := query.AggregateLogs(ctx,
		[]datadoglogs.Compute{{Aggregation: "count"}},
		[]datadoglogs.GroupBy{{Facet: "status"}},
		datadoglogs.AggregateLogsOptions{Query: `"` + marker + `"`, From: "now-15m"})
	if err != nil {
		return err
	}
	for _, b := range res.Buckets {
		n, _ := b.Number("c0")
		fmt.Printf("status=%v count=%v\n", b.By["status"], n)
	}
	return nil
}
