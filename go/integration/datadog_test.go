package integration

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	datadoglogs "github.com/grow-codes-packages/datadog-logs/go"
)

// setup loads /bootstrap/env and skips outside docker/compose.yml.
func setup(t *testing.T) *fakeIntake {
	t.Helper()
	bootstrap := os.Getenv("GROW_BOOTSTRAP_ENV")
	if bootstrap == "" {
		bootstrap = "/bootstrap/env"
	}
	loadBootstrapEnv(t, bootstrap)
	if os.Getenv("GROW_DATADOG_LOGS_URL") == "" {
		t.Skip("GROW_DATADOG_LOGS_URL is not set; run through docker/compose.yml")
	}
	return newFakeIntake(t)
}

func runID(t *testing.T) string {
	t.Helper()
	b := make([]byte, 5)
	_, _ = rand.Read(b)
	return "go-" + hex.EncodeToString(b)
}

// newClient builds a client from the environment with explicit overrides.
func newClient(t *testing.T, explicit datadoglogs.Config) *datadoglogs.Client {
	t.Helper()
	cfg, err := datadoglogs.ConfigFromEnvWith(explicit, nil)
	if err != nil {
		t.Fatal(err)
	}
	c, err := datadoglogs.NewClient(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func str(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case json.Number:
		return x.String()
	case nil:
		return ""
	}
	b, _ := json.Marshal(v)
	return string(b)
}

func TestCore(t *testing.T) {
	fi := setup(t)
	c := newClient(t, datadoglogs.Config{})
	if c.Endpoints().IntakeURL != os.Getenv("GROW_DATADOG_LOGS_URL") {
		t.Fatalf("endpoints %+v", c.Endpoints())
	}

	hs, err := c.Health(t.Context())
	if err != nil || !hs.Healthy || hs.Status != "ok" || hs.Details["apiKeyValid"] != "true" {
		t.Fatalf("health %+v %v", hs, err)
	}
	if d, err := c.Ping(t.Context()); err != nil || d <= 0 {
		t.Fatalf("ping %s %v", d, err)
	}
	if ok, err := c.ValidateAPIKey(t.Context()); err != nil || !ok {
		t.Fatalf("validateApiKey %v %v", ok, err)
	}
	if _, err := c.ServerVersion(t.Context()); !errors.Is(err, datadoglogs.ErrUnsupported) {
		t.Fatalf("serverVersion %v", err)
	}

	// Rejected key: the container answers 403 like Datadog does for a missing or invalid key.
	fi.override(t, http.MethodGet, "/api/v1/validate", 403, `{"errors":["Forbidden"]}`)
	t.Cleanup(func() { fi.override(t, http.MethodGet, "/api/v1/validate", 200, `{"valid":true}`) })
	if ok, err := c.ValidateAPIKey(t.Context()); err != nil || ok {
		t.Fatalf("validateApiKey (403) %v %v", ok, err)
	}
	if _, err := c.Ping(t.Context()); !errors.Is(err, datadoglogs.ErrAuthentication) {
		t.Fatalf("ping (403) %v", err)
	}
	if hs, err := c.Health(t.Context()); err != nil || hs.Healthy || hs.Status != "unauthorized" {
		t.Fatalf("health (403) %+v %v", hs, err)
	}

	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	if err := c.Close(); err != nil {
		t.Fatal("close twice", err)
	}
}

func TestIngestRoundTrip(t *testing.T) {
	fi := setup(t)
	run := runID(t)
	c := newClient(t, datadoglogs.Config{
		Service: "cfg-svc", Env: "it", Version: "1.2.3", Hostname: "go-host", Tags: []string{"team:sdk", "lang:go"},
	})
	lc, err := c.NewLogClient(datadoglogs.LogClientOptions{Batch: datadoglogs.BatchOptions{FlushInterval: time.Hour}})
	if err != nil {
		t.Fatal(err)
	}
	flags := uint8(1)
	lc.Send(t.Context(), datadoglogs.LogInput{
		Message: "input " + run, Severity: datadoglogs.SeverityWarn,
		Attributes: map[string]datadoglogs.AttrValue{"run": run, "service": "user-value", "password": "hunter2", "user": map[string]any{"id": 42}},
	})
	lc.SendBatch(t.Context(), []datadoglogs.LogEntry{datadoglogs.LogRecord{
		Body: "record " + run + " Grüße – 日本語   \"quoted\"", Severity: datadoglogs.SeverityError, Timestamp: time.Now().Add(-time.Minute),
		Resource: map[string]datadoglogs.AttrValue{"service.name": "checkout", "deployment.environment": "test"},
		TraceID:  "4bf92f3577b34da6a3ce929d0e0e4736", SpanID: "00f067aa0ba902b7", TraceFlags: &flags,
	}})
	lc.Logger("it-logger", map[string]datadoglogs.AttrValue{"component": "facade"}).Info(t.Context(), "logger "+run, "k", "v")
	slog.New(datadoglogs.NewSlogHandler(lc, nil)).Error("slog "+run, "attempt", 3)
	if err := lc.Flush(t.Context()); err != nil {
		t.Fatal(err)
	}
	if st := lc.Stats(); st.Sent != 4 || st.Failed != 0 || st.Dropped != 0 {
		t.Fatalf("stats %+v", st)
	}

	logs, _ := fi.logsWith(t, run)
	if len(logs) != 4 {
		t.Fatalf("captured %d logs for %s", len(logs), run)
	}
	byPrefix := map[string]map[string]any{}
	for _, l := range logs {
		byPrefix[strings.Fields(str(l["message"]))[0]] = l
	}
	in := byPrefix["input"]
	for k, want := range map[string]string{
		"status": "warn", "service": "cfg-svc", "hostname": "go-host", "ddsource": "grow-sdk",
		"ddtags": "env:it,version:1.2.3,team:sdk,lang:go", "attr.service": "user-value", "password": "[REDACTED]",
		"run": run, "user": `{"id":42}`,
	} {
		if got := str(in[k]); got != want {
			t.Errorf("input %s = %q, want %q", k, got, want)
		}
	}
	if _, ok := in["timestamp"].(json.Number); !ok {
		t.Errorf("timestamp must be an integer: %v", in["timestamp"])
	}
	rec := byPrefix["record"]
	for k, want := range map[string]string{
		"status": "error", "service": "checkout", "ddtags": "env:test,version:1.2.3,team:sdk,lang:go",
		"dd.trace_id": "11803532876627986230", "dd.span_id": "67667974448284343",
		"otel.trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "otel.span_id": "00f067aa0ba902b7",
	} {
		if got := str(rec[k]); got != want {
			t.Errorf("record %s = %q, want %q", k, got, want)
		}
	}
	if !strings.Contains(str(rec["message"]), "Grüße – 日本語   \"quoted\"") {
		t.Errorf("unicode round trip: %q", rec["message"])
	}
	if l := byPrefix["logger"]; str(l["logger.name"]) != "it-logger" || str(l["component"]) != "facade" || str(l["k"]) != "v" || str(l["status"]) != "info" {
		t.Errorf("logger %v", l)
	}
	if l := byPrefix["slog"]; str(l["status"]) != "error" || str(l["attempt"]) != "3" {
		t.Errorf("slog %v", l)
	}

	t.Run("exporter without gzip", func(t *testing.T) {
		marker := runID(t)
		res, err := c.Exporter().Export(t.Context(), []datadoglogs.LogRecord{{Body: "plain " + marker}}, datadoglogs.ExportOptions{})
		if err != nil || res.Accepted != 1 {
			t.Fatal(res, err)
		}
		if logs, _ := fi.logsWith(t, marker); len(logs) != 1 || str(logs[0]["status"]) != "info" {
			t.Fatalf("plain %v", logs)
		}
		if err := c.Exporter().Shutdown(t.Context()); err != nil {
			t.Fatal(err)
		}
	})

	t.Run("close flushes", func(t *testing.T) {
		marker := runID(t)
		c2 := newClient(t, datadoglogs.Config{})
		lc2, err := c2.NewLogClient(datadoglogs.LogClientOptions{Batch: datadoglogs.BatchOptions{FlushInterval: time.Hour}})
		if err != nil {
			t.Fatal(err)
		}
		lc2.Send(t.Context(), datadoglogs.LogInput{Message: "closing " + marker})
		if err := c2.Close(); err != nil {
			t.Fatal(err)
		}
		if logs, _ := fi.logsWith(t, marker); len(logs) != 1 {
			t.Fatalf("close must flush: %d", len(logs))
		}
	})
}

func TestIntakeLimits(t *testing.T) {
	fi := setup(t)
	c := newClient(t, datadoglogs.Config{})

	t.Run("at most 1000 entries per request", func(t *testing.T) {
		marker := runID(t)
		recs := make([]datadoglogs.LogRecord, 2500)
		for i := range recs {
			recs[i] = datadoglogs.LogRecord{Body: "entry " + marker}
		}
		res, err := c.Exporter().Export(t.Context(), recs, datadoglogs.ExportOptions{Gzip: true})
		if err != nil || res.Accepted != 2500 {
			t.Fatal(res, err)
		}
		logs, pls := fi.logsWith(t, marker)
		if len(logs) != 2500 || len(pls) != 3 {
			t.Fatalf("%d logs in %d payloads", len(logs), len(pls))
		}
		for _, p := range pls {
			if len(p.Logs) > datadoglogs.MaxBatchEntries {
				t.Errorf("payload with %d entries", len(p.Logs))
			}
		}
	})

	t.Run("at most 5 MB per request", func(t *testing.T) {
		marker := runID(t)
		big := strings.Repeat("x", 900_000)
		recs := make([]datadoglogs.LogRecord, 12)
		for i := range recs {
			recs[i] = datadoglogs.LogRecord{Body: "big " + marker + " " + big}
		}
		res, err := c.Exporter().Export(t.Context(), recs, datadoglogs.ExportOptions{Gzip: true})
		if err != nil || res.Accepted != 12 {
			t.Fatal(res, err)
		}
		logs, pls := fi.logsWith(t, marker)
		if len(logs) != 12 || len(pls) < 3 {
			t.Fatalf("%d logs in %d payloads", len(logs), len(pls))
		}
		for _, p := range pls {
			if len(p.Raw) > datadoglogs.MaxPayloadBytes {
				t.Errorf("payload of %d bytes", len(p.Raw))
			}
		}
	})

	t.Run("logs over 1 MB are truncated", func(t *testing.T) {
		marker := runID(t)
		res, err := c.Exporter().Export(t.Context(), []datadoglogs.LogRecord{{Body: "huge " + marker + " " + strings.Repeat("y", 1_100_000)}}, datadoglogs.ExportOptions{Gzip: true})
		if err != nil || res.Accepted != 1 {
			t.Fatal(res, err)
		}
		logs, pls := fi.logsWith(t, marker)
		if len(logs) != 1 || logs[0][datadoglogs.TruncatedAttribute] != true || len(pls[0].Raw)-2 > datadoglogs.MaxLogBytes {
			t.Fatalf("truncation: %d logs, %d bytes", len(logs), len(pls[0].Raw))
		}
	})
}

func TestIntakeErrors(t *testing.T) {
	fi := setup(t)
	t.Cleanup(func() { fi.override(t, http.MethodPost, "/api/v2/logs", 202, `{}`) })
	c := newClient(t, datadoglogs.Config{CommonConfig: datadoglogs.CommonConfig{Retry: &datadoglogs.RetryPolicy{MaxRetries: 1, InitialBackoff: time.Millisecond}}})
	cases := []struct {
		status int
		body   string
		want   *datadoglogs.Error
		sent   int
	}{
		{400, `{"errors":["Bad Request"]}`, datadoglogs.ErrValidation, 1},
		{403, `{"errors":["Forbidden","API key is missing or invalid"]}`, datadoglogs.ErrAuthentication, 1},
		{413, `{"errors":["Payload too large"]}`, datadoglogs.ErrValidation, 1},
		{429, `{"errors":["Too many requests"]}`, datadoglogs.ErrRateLimited, 2},
		{500, `{"errors":["Internal Server Error"]}`, datadoglogs.ErrUnavailable, 2},
		{503, `{"errors":["Service Unavailable"]}`, datadoglogs.ErrUnavailable, 2},
	}
	for _, tc := range cases {
		fi.override(t, http.MethodPost, "/api/v2/logs", tc.status, tc.body)
		marker := runID(t)
		_, err := c.Exporter().Export(t.Context(), []datadoglogs.LogRecord{{Body: "fault " + marker}}, datadoglogs.ExportOptions{Gzip: true})
		var e *datadoglogs.Error
		if !errors.As(err, &e) || !errors.Is(err, tc.want) || e.HTTPStatus != tc.status {
			t.Errorf("%d: %v", tc.status, err)
		}
		if logs, _ := fi.logsWith(t, marker); len(logs) != tc.sent {
			t.Errorf("%d: %d attempts captured, want %d", tc.status, len(logs), tc.sent)
		}
	}
}

// searchStub is a Datadog search answer served by the container (wire stub,
// not a round trip: fakeintake has no search engine).
func searchStub(run string) string {
	ts := time.Now().UTC().Format(time.RFC3339Nano)
	return `{"data":[{"id":"stub-1","type":"log","attributes":{"timestamp":"` + ts + `","host":"go-host","service":"checkout",` +
		`"status":"error","message":"stub ` + run + `","tags":["env:it","version:1.2.3"],` +
		`"attributes":{"dd":{"trace_id":"11803532876627986230","span_id":"67667974448284343"},"run":"` + run + `"}}}],` +
		`"meta":{"elapsed":5,"request_id":"req-` + run + `","status":"done"}}`
}

const aggregateStub = `{"data":{"buckets":[{"by":{"service":"checkout"},"computes":{"c0":3}}]},"meta":{"elapsed":7,"request_id":"agg","status":"done"}}`

const indexesStub = `{"indexes":[{"name":"main","filter":{"query":"*"},"num_retention_days":15,"daily_limit":300000000,"is_rate_limited":false,"exclusion_filters":[]}]}`

func TestQueryWireStubs(t *testing.T) {
	fi := setup(t)
	run := runID(t)
	fi.override(t, http.MethodPost, "/api/v2/logs/events/search", 200, searchStub(run))
	fi.override(t, http.MethodPost, "/api/v2/logs/analytics/aggregate", 200, aggregateStub)
	fi.override(t, http.MethodGet, "/api/v1/logs/config/indexes", 200, indexesStub)
	c := newClient(t, datadoglogs.Config{})
	q, err := c.Query()
	if err != nil {
		t.Fatal(err)
	}

	page, err := q.SearchLogs(t.Context(), datadoglogs.SearchLogsRequest{Query: `service:checkout "` + run + `"`, From: "now-1h", Limit: 50, Indexes: []string{"main"}})
	if err != nil || len(page.Events) != 1 || page.RequestID != "req-"+run || page.NextCursor != "" {
		t.Fatalf("searchLogs %+v %v", page, err)
	}
	rec := page.Records()[0]
	if rec.Severity != datadoglogs.SeverityError || rec.TraceID != "0000000000000000a3ce929d0e0e4736" || rec.SpanID != "00f067aa0ba902b7" ||
		rec.Resource["service.version"] != "1.2.3" {
		t.Fatalf("record %+v", rec)
	}
	// The search request itself is captured by the container: assert what the SDK sent.
	sent, err := fi.payloads(t.Context(), "/api/v2/logs/events/search")
	if err != nil || len(sent) == 0 {
		t.Fatal(sent, err)
	}
	var body struct {
		Filter struct {
			Query, From, To string
			Indexes         []string
		}
		Sort string
		Page struct{ Limit int }
	}
	if err := json.Unmarshal(sent[len(sent)-1].Raw, &body); err != nil {
		t.Fatal(err)
	}
	if body.Filter.Query != `service:checkout "`+run+`"` || body.Filter.From != "now-1h" || body.Filter.To != "now" ||
		body.Filter.Indexes[0] != "main" || body.Sort != "-timestamp" || body.Page.Limit != 50 {
		t.Fatalf("captured search request %+v", body)
	}

	n := 0
	for e, err := range q.IterateLogs(t.Context(), datadoglogs.SearchLogsRequest{Query: run, Limit: 10}) {
		if err != nil || e.ID != "stub-1" {
			t.Fatal(e, err)
		}
		n++
	}
	if n != 1 {
		t.Fatalf("iterateLogs %d", n)
	}
	if res, err := q.Search(t.Context(), run, datadoglogs.SearchOptions{Start: time.Now().Add(-time.Hour)}); err != nil || len(res.Records) != 1 {
		t.Fatalf("search %+v %v", res, err)
	}
	if r, err := q.WaitForLog(t.Context(), run, datadoglogs.WaitForLogOptions{Timeout: 10 * time.Second}); err != nil || r.Body != "stub "+run {
		t.Fatalf("waitForLog %+v %v", r, err)
	}

	agg, err := q.AggregateLogs(t.Context(), []datadoglogs.Compute{{Aggregation: "count"}}, []datadoglogs.GroupBy{{Facet: "service"}}, datadoglogs.AggregateLogsOptions{Query: run})
	if n, _ := agg.Buckets[0].Number("c0"); err != nil || n != 3 || agg.Buckets[0].By["service"] != "checkout" {
		t.Fatalf("aggregateLogs %+v %v", agg, err)
	}
	if ix, err := q.ListIndexes(t.Context()); err != nil || len(ix) != 1 || ix[0].Name != "main" || ix[0].RetentionDays != 15 {
		t.Fatalf("listIndexes %+v %v", ix, err)
	}
	if names, err := q.ListStreams(t.Context()); err != nil || len(names) != 1 || names[0] != "main" {
		t.Fatalf("listStreams %v %v", names, err)
	}
	if _, err := q.ListLabels(t.Context()); !errors.Is(err, datadoglogs.ErrUnsupported) {
		t.Fatalf("listLabels %v", err)
	}

	var mu sync.Mutex
	var got []datadoglogs.LogRecord
	h, err := q.Tail(t.Context(), run, func(r datadoglogs.LogRecord) {
		mu.Lock()
		got = append(got, r)
		mu.Unlock()
	}, datadoglogs.TailOptions{Interval: 100 * time.Millisecond, Start: time.Now().Add(-time.Hour)})
	if err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		time.Sleep(300 * time.Millisecond)
		mu.Lock()
		n := len(got)
		mu.Unlock()
		if n > 0 {
			break
		}
	}
	h.Stop()
	mu.Lock()
	defer mu.Unlock()
	if len(got) != 1 || got[0].Body != "stub "+run {
		t.Fatalf("tail delivered %d records", len(got))
	}
}
