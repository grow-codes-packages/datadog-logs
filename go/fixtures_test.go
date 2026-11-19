package datadoglogs

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

// fixture is a recorded (or OpenAPI-derived) request/response pair from the
// prep harness (testdata/fixtures; see the "source" and "verified" fields).
type fixture struct {
	Request struct {
		Method string          `json:"method"`
		Path   string          `json:"path"`
		Body   json.RawMessage `json:"body"`
	} `json:"request"`
	Response struct {
		Status  int               `json:"status"`
		Headers map[string]string `json:"headers"`
		Body    json.RawMessage   `json:"body"`
	} `json:"response"`
}

func loadFixture(t *testing.T, name string) fixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "fixtures", name+".json"))
	if err != nil {
		t.Fatalf("fixture %s: %v", name, err)
	}
	var f fixture
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatalf("fixture %s: %v", name, err)
	}
	return f
}

// body returns the response body bytes ("" for a null body).
func (f fixture) body() []byte {
	if len(f.Response.Body) == 0 || string(f.Response.Body) == "null" {
		return nil
	}
	return f.Response.Body
}

func (f fixture) write(w http.ResponseWriter) {
	for k, v := range f.Response.Headers {
		if k == "content-length" {
			continue
		}
		w.Header().Set(k, v)
	}
	w.WriteHeader(f.Response.Status)
	_, _ = w.Write(f.body())
}

const testAppKey = "app-SECRET-5678"

// routes dispatches by "METHOD /path"; unknown routes answer 404.
func routes(m map[string]func(w http.ResponseWriter, r *http.Request, n int)) func(w http.ResponseWriter, r *http.Request, n int) {
	return func(w http.ResponseWriter, r *http.Request, n int) {
		if h, ok := m[r.Method+" "+r.URL.Path]; ok {
			h(w, r, n)
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}
}

// readBody returns the request body, gunzipped when Content-Encoding is gzip.
func readBody(t *testing.T, r *http.Request) []byte {
	t.Helper()
	var rd io.Reader = r.Body
	if r.Header.Get("Content-Encoding") == "gzip" {
		zr, err := gzip.NewReader(r.Body)
		if err != nil {
			t.Errorf("gzip: %v", err)
			return nil
		}
		rd = zr
	}
	b, err := io.ReadAll(rd)
	if err != nil {
		t.Errorf("read body: %v", err)
	}
	return b
}

// decodeLogs decodes an intake body (JSON array) keeping integers exact.
func decodeLogs(t *testing.T, b []byte) []map[string]any {
	t.Helper()
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.UseNumber()
	var out []map[string]any
	if err := dec.Decode(&out); err != nil {
		t.Fatalf("intake body is not a JSON array: %v", err)
	}
	return out
}

func writeJSON(w http.ResponseWriter, status int, body string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = io.WriteString(w, body)
}
