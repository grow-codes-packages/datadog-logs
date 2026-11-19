package integration

import (
	"bytes"
	"compress/gzip"
	"compress/zlib"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	datadoglogs "github.com/grow-codes-packages/datadog-logs/go"
)

// fakeIntake talks to the datadog/fakeintake harness endpoints (not Datadog
// APIs): raw payload read-back and response overrides.
type fakeIntake struct {
	base string
	http *http.Client
}

func newFakeIntake(t *testing.T) *fakeIntake {
	t.Helper()
	base := os.Getenv("FAKEINTAKE_URL")
	if base == "" {
		base = os.Getenv("GROW_DATADOG_LOGS_URL")
	}
	return &fakeIntake{base: strings.TrimRight(base, "/"), http: &http.Client{Timeout: 60 * time.Second}}
}

// payload is one captured request body (decoded).
type payload struct {
	Raw  []byte
	Logs []map[string]any
}

// payloads returns every captured body of endpoint, decoded from the raw view.
func (f *fakeIntake) payloads(ctx context.Context, endpoint string) ([]payload, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, f.base+"/fakeintake/payloads?endpoint="+url.QueryEscape(endpoint), nil)
	if err != nil {
		return nil, err
	}
	resp, err := f.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("fakeintake payloads: HTTP %d", resp.StatusCode)
	}
	var body struct {
		Payloads []struct {
			Data     string `json:"data"`
			Encoding string `json:"encoding"`
		} `json:"payloads"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return nil, err
	}
	out := make([]payload, 0, len(body.Payloads))
	for _, p := range body.Payloads {
		data, err := base64.StdEncoding.DecodeString(p.Data)
		if err != nil {
			return nil, err
		}
		var rd io.Reader = bytes.NewReader(data)
		switch p.Encoding {
		case "gzip":
			if rd, err = gzip.NewReader(rd); err != nil {
				return nil, err
			}
		case "deflate":
			if rd, err = zlib.NewReader(rd); err != nil {
				return nil, err
			}
		}
		raw, err := io.ReadAll(rd)
		if err != nil {
			return nil, err
		}
		pl := payload{Raw: raw}
		dec := json.NewDecoder(bytes.NewReader(raw))
		dec.UseNumber()
		var anyBody any
		if dec.Decode(&anyBody) == nil {
			switch v := anyBody.(type) {
			case []any:
				for _, item := range v {
					if m, ok := item.(map[string]any); ok {
						pl.Logs = append(pl.Logs, m)
					}
				}
			case map[string]any:
				pl.Logs = []map[string]any{v}
			}
		}
		out = append(out, pl)
	}
	return out, nil
}

// override makes method+endpoint answer status/body (payloads are still captured).
func (f *fakeIntake) override(t *testing.T, method, endpoint string, status int, body string) {
	t.Helper()
	spec, _ := json.Marshal(map[string]any{
		"method": method, "endpoint": endpoint, "status_code": status, "content_type": "application/json",
		"body": base64.StdEncoding.EncodeToString([]byte(body)),
	})
	req, _ := http.NewRequestWithContext(context.Background(), http.MethodPost, f.base+"/fakeintake/configure/override", bytes.NewReader(spec))
	req.Header.Set("Content-Type", "application/json")
	resp, err := f.http.Do(req)
	if err != nil {
		t.Fatalf("override %s %s: %v", method, endpoint, err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("override %s %s: HTTP %d", method, endpoint, resp.StatusCode)
	}
}

// logsWith returns every captured intake log whose message contains marker,
// and the payloads that held at least one of them.
func (f *fakeIntake) logsWith(t *testing.T, marker string) ([]map[string]any, []payload) {
	t.Helper()
	pls, err := f.payloads(t.Context(), "/api/v2/logs")
	if err != nil {
		t.Fatal(err)
	}
	var logs []map[string]any
	var hit []payload
	for _, p := range pls {
		found := false
		for _, l := range p.Logs {
			if msg, _ := l["message"].(string); strings.Contains(msg, marker) {
				logs = append(logs, l)
				found = true
			}
		}
		if found {
			hit = append(hit, p)
		}
	}
	return logs, hit
}

// fakeintakeQuery is a LogQueryClient over the fakeintake capture: Search
// returns the captured intake logs whose message contains every term of the
// query (quotes ignored), converted back to LogRecords. It lets the
// conformance logging scenarios assert send → read back against the real
// container; Datadog's search API itself is not available there.
type fakeintakeQuery struct {
	fi *fakeIntake
}

var _ datadoglogs.LogQueryClient = fakeintakeQuery{}

func (q fakeintakeQuery) Search(ctx context.Context, query string, _ datadoglogs.SearchOptions) (datadoglogs.LogSearchResult, error) {
	pls, err := q.fi.payloads(ctx, "/api/v2/logs")
	if err != nil {
		return datadoglogs.LogSearchResult{}, datadoglogs.NewError(datadoglogs.ErrorCodeUnavailable, err.Error(), err)
	}
	terms := strings.Fields(strings.ReplaceAll(query, `"`, " "))
	var out datadoglogs.LogSearchResult
	for _, p := range pls {
		for _, l := range p.Logs {
			msg, _ := l["message"].(string)
			match := true
			for _, term := range terms {
				if !strings.Contains(msg, term) {
					match = false
					break
				}
			}
			if match {
				out.Records = append(out.Records, capturedRecord(l))
			}
		}
	}
	return out, nil
}

// capturedRecord turns a captured Datadog log back into a LogRecord.
func capturedRecord(l map[string]any) datadoglogs.LogRecord {
	rec := datadoglogs.LogRecord{Attributes: map[string]datadoglogs.AttrValue{}, Resource: map[string]datadoglogs.AttrValue{}}
	for k, v := range l {
		switch k {
		case "message":
			rec.Body, _ = v.(string)
		case "status":
			rec.SeverityText, _ = v.(string)
			rec.Severity, _ = datadoglogs.ParseSeverity(rec.SeverityText)
		case "timestamp":
			if n, ok := v.(json.Number); ok {
				ms, _ := n.Int64()
				rec.Timestamp = time.UnixMilli(ms).UTC()
			}
		case "service":
			rec.Resource["service.name"] = v
		case "hostname":
			rec.Resource["host.name"] = v
		case "otel.trace_id":
			rec.TraceID, _ = v.(string)
		case "otel.span_id":
			rec.SpanID, _ = v.(string)
		default:
			rec.Attributes[k] = datadoglogs.NormalizeAttrValue(v)
		}
	}
	return rec
}

func (fakeintakeQuery) Tail(context.Context, string, func(datadoglogs.LogRecord), datadoglogs.TailOptions) (datadoglogs.TailHandle, error) {
	return nil, datadoglogs.UnsupportedError("fakeintake.tail", "not available on the capture")
}

func (fakeintakeQuery) ListStreams(context.Context) ([]string, error) {
	return nil, datadoglogs.UnsupportedError("fakeintake.listStreams", "not available on the capture")
}

func (fakeintakeQuery) ListLabels(context.Context) ([]string, error) {
	return nil, datadoglogs.UnsupportedError("fakeintake.listLabels", "not available on the capture")
}

func (q fakeintakeQuery) WaitForLog(ctx context.Context, query string, opts datadoglogs.WaitForLogOptions) (datadoglogs.LogRecord, error) {
	return datadoglogs.WaitForLog(ctx, q, query, opts)
}
