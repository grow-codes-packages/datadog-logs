package datadoglogs

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf8"
)

// intakeServer records every intake body (decoded) and answers with respond.
type intakeServer struct {
	*testServer
	mu      sync.Mutex
	bodies  [][]map[string]any
	raw     [][]byte
	headers []http.Header
}

func newIntakeServer(t *testing.T, respond func(w http.ResponseWriter, n int)) *intakeServer {
	t.Helper()
	s := &intakeServer{}
	s.testServer = newTestServer(t, func(w http.ResponseWriter, r *http.Request, n int) {
		if r.Method != http.MethodPost || r.URL.Path != pathIntake {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		b := readBody(t, r)
		s.mu.Lock()
		s.raw = append(s.raw, b)
		s.bodies = append(s.bodies, decodeLogs(t, b))
		s.headers = append(s.headers, r.Header.Clone())
		s.mu.Unlock()
		if respond == nil {
			writeJSON(w, http.StatusAccepted, `{}`)
			return
		}
		respond(w, n)
	})
	return s
}

func (s *intakeServer) logs() []map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []map[string]any
	for _, b := range s.bodies {
		out = append(out, b...)
	}
	return out
}

func traceFlags(v uint8) *uint8 { return &v }

func TestIntakeExport(t *testing.T) {
	srv := newIntakeServer(t, nil)
	c := newTestClient(t, srv.URL, func(cfg *Config) {
		cfg.Service, cfg.Env, cfg.Version, cfg.Hostname = "cfg-svc", "cfg-env", "9.9", "cfg-host"
		cfg.Tags = []string{"team:sdk", "env:dup", "team:sdk"}
	})
	ts := time.Date(2026, 11, 18, 12, 1, 2, 345_000_000, time.UTC)
	rec := LogRecord{
		Timestamp: ts, Severity: SeverityError, SeverityText: "ERROR", Body: `user signed in <&> "q" ` + " ",
		Attributes: map[string]AttrValue{"user": map[string]any{"id": 42}, "service": "user-svc", "status": "x", "n": 7},
		Resource:   map[string]AttrValue{"service.name": "checkout", "host.name": "host-a", "deployment.environment": "test", "service.version": "1.2.3"},
		TraceID:    "4BF92F3577B34DA6A3CE929D0E0E4736", SpanID: "00f067aa0ba902b7", TraceFlags: traceFlags(1),
	}
	res, err := c.Exporter().Export(t.Context(), []LogRecord{rec}, ExportOptions{Gzip: true})
	if err != nil || res.Accepted != 1 || res.Rejected != 0 {
		t.Fatalf("export %+v %v", res, err)
	}
	h := srv.headers[0]
	if h.Get(HeaderAPIKey) != testToken || h.Get(HeaderAppKey) != "" || h.Get("Content-Encoding") != "gzip" ||
		h.Get("Content-Type") != "application/json" || h.Get("User-Agent") != UserAgent {
		t.Fatalf("headers %v", h)
	}
	if !strings.Contains(string(srv.raw[0]), `<&>`) {
		t.Fatalf("HTML must not be escaped: %s", srv.raw[0])
	}
	got := srv.logs()[0]
	want := map[string]string{
		"message": rec.Body, "timestamp": "1795003262345", "status": "error", "service": "checkout", "hostname": "host-a",
		"ddsource": DefaultSource, "ddtags": "env:test,version:1.2.3,team:sdk,env:dup",
		"dd.trace_id": "11803532876627986230", "dd.span_id": "67667974448284343",
		"otel.trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "otel.span_id": "00f067aa0ba902b7",
		"attr.service": "user-svc", "attr.status": "x", "n": "7",
	}
	for k, v := range want {
		if s := toString(got[k]); s != v {
			t.Errorf("%s = %q, want %q", k, s, v)
		}
	}
	if u, _ := got["user"].(map[string]any); toString(u["id"]) != "42" {
		t.Errorf("nested attribute %v", got["user"])
	}

	t.Run("config fallbacks without gzip", func(t *testing.T) {
		before := len(srv.logs())
		res, err := c.Exporter().Export(t.Context(), []LogRecord{{Body: "plain", Severity: SeverityInfo + 1, TraceID: "zz", SpanID: "1"}}, ExportOptions{})
		if err != nil || res.Accepted != 1 {
			t.Fatal(res, err)
		}
		if srv.headers[len(srv.headers)-1].Get("Content-Encoding") != "" {
			t.Fatal("gzip off")
		}
		got := srv.logs()[before]
		if got["service"] != "cfg-svc" || got["hostname"] != "cfg-host" || got["ddtags"] != "env:cfg-env,version:9.9,team:sdk,env:dup" ||
			got["status"] != "info" || got["dd.trace_id"] != nil {
			t.Fatalf("fallbacks %v", got)
		}
		if ms, _ := got["timestamp"].(json.Number).Int64(); time.Since(time.UnixMilli(ms)) > time.Minute {
			t.Fatal("zero timestamp must become now")
		}
	})

	t.Run("empty", func(t *testing.T) {
		if res, err := c.Exporter().Export(t.Context(), nil, ExportOptions{}); err != nil || res.Accepted != 0 {
			t.Fatal(res, err)
		}
		if c.Exporter().Shutdown(t.Context()) != nil {
			t.Fatal("shutdown")
		}
	})
}

func toString(v any) string {
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

func TestDatadogStatus(t *testing.T) {
	cases := []struct {
		rec  LogRecord
		want string
	}{
		{LogRecord{Severity: SeverityTrace}, "trace"},
		{LogRecord{Severity: SeverityDebug + 2, SeverityText: "DEBUG3"}, "debug"},
		{LogRecord{Severity: SeverityWarn, SeverityText: "WARN"}, "warn"},
		{LogRecord{Severity: SeverityFatal}, "fatal"},
		{LogRecord{Severity: SeverityInfo, SeverityText: "Notice"}, "notice"},
		{LogRecord{}, "info"},
	}
	for _, tc := range cases {
		if got := datadogStatus(tc.rec); got != tc.want {
			t.Errorf("%+v: %s, want %s", tc.rec, got, tc.want)
		}
	}
	if _, ok := lower64Decimal("0123456789abcdeg", 16); ok {
		t.Fatal("invalid hex")
	}
	if got, _ := lower64Decimal("ffffffffffffffff", 16); got != "18446744073709551615" {
		t.Fatal(got)
	}
}

func TestIntakeLimits(t *testing.T) {
	t.Run("split by entries", func(t *testing.T) {
		srv := newIntakeServer(t, nil)
		c := newTestClient(t, srv.URL, nil)
		recs := make([]LogRecord, 2500)
		for i := range recs {
			recs[i] = LogRecord{Body: "m", Severity: SeverityInfo}
		}
		res, err := c.Exporter().Export(t.Context(), recs, ExportOptions{Gzip: true})
		if err != nil || res.Accepted != 2500 || len(srv.bodies) != 3 || len(srv.bodies[0]) != MaxBatchEntries || len(srv.bodies[2]) != 500 {
			t.Fatalf("%+v %v bodies=%d", res, err, len(srv.bodies))
		}
	})

	t.Run("split by bytes", func(t *testing.T) {
		srv := newIntakeServer(t, nil)
		c := newTestClient(t, srv.URL, nil)
		big := strings.Repeat("x", 900_000)
		recs := make([]LogRecord, 12)
		for i := range recs {
			recs[i] = LogRecord{Body: big}
		}
		res, err := c.Exporter().Export(t.Context(), recs, ExportOptions{})
		if err != nil || res.Accepted != 12 || len(srv.raw) < 3 {
			t.Fatalf("%+v %v requests=%d", res, err, len(srv.raw))
		}
		for i, b := range srv.raw {
			if len(b) > MaxPayloadBytes {
				t.Errorf("request %d has %d bytes", i, len(b))
			}
		}
	})

	t.Run("truncate oversized logs", func(t *testing.T) {
		srv := newIntakeServer(t, nil)
		c := newTestClient(t, srv.URL, nil)
		msg := strings.Repeat("ü\"\n\x01 <", 150_000) + "\xff" + strings.Repeat("a", 200_000)
		res, err := c.Exporter().Export(t.Context(), []LogRecord{{Body: msg, Attributes: map[string]AttrValue{TruncatedAttribute: "user"}}}, ExportOptions{Gzip: true})
		if err != nil || res.Accepted != 1 {
			t.Fatal(res, err)
		}
		raw := srv.raw[0]
		got := srv.logs()[0]
		if len(raw)-2 > MaxLogBytes || len(raw)-2 < MaxLogBytes-16 || got[TruncatedAttribute] != true || got["attr."+TruncatedAttribute] != "user" {
			t.Fatalf("size %d truncated=%v", len(raw)-2, got[TruncatedAttribute])
		}
		m, _ := got["message"].(string)
		if !utf8.ValidString(m) || !strings.HasPrefix(msg, m) {
			t.Fatal("message must be a rune-boundary prefix")
		}
		ascii := strings.Repeat("b", 1_200_000)
		if out := truncateJSONString(ascii, 10); out != "bbbbbbbbbb" {
			t.Fatal(out)
		}
		if out := truncateJSONString("short", 100); out != "short" {
			t.Fatal(out)
		}
		if b, ok := c.Exporter().encode(LogRecord{Body: ascii}); !ok || len(b) != MaxLogBytes {
			t.Fatalf("ascii truncation to exactly the limit: %d", len(b))
		}
	})

	t.Run("reject logs too large without a message", func(t *testing.T) {
		srv := newIntakeServer(t, nil)
		c := newTestClient(t, srv.URL, nil)
		huge := LogRecord{Body: "m", Attributes: map[string]AttrValue{"blob": strings.Repeat("z", MaxLogBytes)}}
		res, err := c.Exporter().Export(t.Context(), []LogRecord{huge, {Body: "ok"}}, ExportOptions{})
		if err != nil || res.Accepted != 1 || res.Rejected != 1 || !errors.Is(res.Error, ErrValidation) {
			t.Fatalf("%+v %v", res, err)
		}
		res, err = c.Exporter().Export(t.Context(), []LogRecord{huge}, ExportOptions{})
		if err != nil || res.Accepted != 0 || res.Rejected != 1 || res.Error == nil || len(srv.raw) != 1 {
			t.Fatalf("all rejected: %+v %v", res, err)
		}
	})

	t.Run("marshal fallback", func(t *testing.T) {
		if b := marshalCompact(map[string]any{"f": func() {}}); len(b) == 0 {
			t.Fatal("fallback")
		}
	})
}

func TestIntakeErrors(t *testing.T) {
	recs := []LogRecord{{Body: "a"}}
	cases := []struct {
		fixture string
		code    ErrorCode
		calls   int32
	}{
		{"send.400_injected", ErrorCodeValidation, 1},
		{"send.401_injected", ErrorCodeAuthentication, 1},
		{"send.403_injected", ErrorCodeAuthentication, 1},
		{"send.408_injected", ErrorCodeTimeout, 2},
		{"send.413_injected", ErrorCodeValidation, 1},
		{"send.429_injected", ErrorCodeRateLimited, 2},
		{"send.500_injected", ErrorCodeUnavailable, 2},
		{"send.503_injected", ErrorCodeUnavailable, 2},
	}
	for _, tc := range cases {
		f := loadFixture(t, tc.fixture)
		srv := newIntakeServer(t, func(w http.ResponseWriter, _ int) { f.write(w) })
		c := newTestClient(t, srv.URL, func(cfg *Config) { cfg.Retry = fastRetry(1) })
		_, err := c.Exporter().Export(t.Context(), recs, ExportOptions{Gzip: true})
		e := asError(t, err)
		if e.Code != tc.code || e.HTTPStatus != f.Response.Status || srv.calls.Load() != tc.calls || strings.Contains(e.Error(), testToken) {
			t.Errorf("%s: %+v calls=%d", tc.fixture, e, srv.calls.Load())
		}
	}

	t.Run("retry then accept", func(t *testing.T) {
		srv := newIntakeServer(t, func(w http.ResponseWriter, n int) {
			if n == 1 {
				writeJSON(w, 500, `{"errors":["Internal"]}`)
				return
			}
			writeJSON(w, 202, `{}`)
		})
		c := newTestClient(t, srv.URL, nil)
		if res, err := c.Exporter().Export(t.Context(), recs, ExportOptions{}); err != nil || res.Accepted != 1 || srv.calls.Load() != 2 {
			t.Fatal(res, err)
		}
	})

	t.Run("partial failure is not resent", func(t *testing.T) {
		srv := newIntakeServer(t, func(w http.ResponseWriter, n int) {
			if n == 2 {
				writeJSON(w, 400, `{"errors":["bad"]}`)
				return
			}
			writeJSON(w, 202, `{}`)
		})
		c := newTestClient(t, srv.URL, nil)
		many := make([]LogRecord, 1500)
		res, err := c.Exporter().Export(t.Context(), many, ExportOptions{})
		if err != nil || res.Accepted != 1000 || res.Rejected != 500 || !errors.Is(res.Error, ErrValidation) {
			t.Fatalf("%+v %v", res, err)
		}
	})
}

func TestNewLogClient(t *testing.T) {
	srv := newIntakeServer(t, nil)
	c := newTestClient(t, srv.URL, func(cfg *Config) { cfg.Service, cfg.Env, cfg.Version, cfg.Hostname = "svc", "env1", "v1", "h1" })
	lc, err := c.NewLogClient(LogClientOptions{
		Batch:    BatchOptions{MaxBatchSize: 5000, MaxQueueSize: 5000, MaxBatchBytes: 10 * MaxPayloadBytes, FlushInterval: time.Hour},
		Resource: map[string]AttrValue{"service.name": "override"},
	})
	if err != nil {
		t.Fatal(err)
	}
	recs := make([]LogEntry, 1200)
	for i := range recs {
		recs[i] = LogInput{Message: "hello", Attributes: map[string]AttrValue{"password": "hunter2"}}
	}
	lc.SendBatch(t.Context(), recs)
	lc.Logger("app", nil).Warn(t.Context(), "warned", "k", "v")
	if err := lc.Flush(t.Context()); err != nil {
		t.Fatal(err)
	}
	logs := srv.logs()
	if len(logs) != 1201 || lc.Stats().Sent != 1201 {
		t.Fatalf("sent %d stats %+v", len(logs), lc.Stats())
	}
	for _, b := range srv.bodies {
		if len(b) > MaxBatchEntries {
			t.Fatalf("batch of %d", len(b))
		}
	}
	first, last := logs[0], logs[len(logs)-1]
	if first["service"] != "override" || first["hostname"] != "h1" || first["ddtags"] != "env:env1,version:v1" || first["password"] != Redacted {
		t.Fatalf("first %v", first)
	}
	if last["status"] != "warn" || last["logger.name"] != "app" || last["k"] != "v" {
		t.Fatalf("logger record %v", last)
	}
	if _, err := c.NewLogClient(LogClientOptions{Batch: BatchOptions{MaxQueueSize: -1}}); !errors.Is(err, ErrConfig) {
		t.Fatalf("bad options: %v", err)
	}
}

func TestCleanTag(t *testing.T) {
	cases := map[string]string{
		"team:sdk":        "team:sdk",
		"team: my squad":  "team:_my_squad",
		"a,b":             "a_b",
		" lead , trail\t": "_lead_trail_",
		"x y":             "x_y",
		"":                "",
	}
	for in, want := range cases {
		if got := cleanTag(in); got != want {
			t.Errorf("cleanTag(%q) = %q, want %q", in, got, want)
		}
	}
	c := &Client{cfg: Config{Env: "prod env", Tags: []string{"a b", "a,b", "c"}}}
	if got := c.ddtags(nil); got != "env:prod_env,a_b,c" {
		t.Fatalf("ddtags %q", got)
	}
}
