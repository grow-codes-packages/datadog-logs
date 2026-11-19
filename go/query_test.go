package datadoglogs

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// searchBody is the decoded request body of a search/aggregate call.
type searchBody struct {
	Filter struct {
		Query       string   `json:"query"`
		From        string   `json:"from"`
		To          string   `json:"to"`
		Indexes     []string `json:"indexes"`
		StorageTier string   `json:"storage_tier"`
	} `json:"filter"`
	Sort string `json:"sort"`
	Page struct {
		Limit  int    `json:"limit"`
		Cursor string `json:"cursor"`
	} `json:"page"`
	Compute []Compute `json:"compute"`
	GroupBy []GroupBy `json:"group_by"`
}

type queryServer struct {
	*testServer
	mu     sync.Mutex
	bodies []searchBody
}

func (s *queryServer) body(i int) searchBody {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.bodies[i]
}

// newQueryServer checks the auth headers, records bodies and dispatches to h.
func newQueryServer(t *testing.T, h func(w http.ResponseWriter, r *http.Request, b searchBody, n int)) *queryServer {
	t.Helper()
	s := &queryServer{}
	s.testServer = newTestServer(t, func(w http.ResponseWriter, r *http.Request, n int) {
		if r.Header.Get(HeaderAPIKey) != testToken || r.Header.Get(HeaderAppKey) != testAppKey {
			writeJSON(w, 403, `{"errors":["Forbidden"]}`)
			return
		}
		var b searchBody
		if r.Method == http.MethodPost {
			if err := json.Unmarshal(readBody(t, r), &b); err != nil {
				t.Errorf("body: %v", err)
			}
		}
		s.mu.Lock()
		s.bodies = append(s.bodies, b)
		s.mu.Unlock()
		h(w, r, b, n)
	})
	return s
}

func newQuery(t *testing.T, url string, mutate func(*Config)) *LogsQuery {
	t.Helper()
	q, err := newTestClient(t, url, mutate).Query()
	if err != nil {
		t.Fatal(err)
	}
	return q
}

// pagedSearch serves page1 then page2 (by cursor).
func pagedSearch(t *testing.T) func(w http.ResponseWriter, r *http.Request, b searchBody, n int) {
	p1, p2 := loadFixture(t, "searchLogs.page1"), loadFixture(t, "searchLogs.page2_last")
	return func(w http.ResponseWriter, _ *http.Request, b searchBody, _ int) {
		if b.Page.Cursor == "" {
			p1.write(w)
			return
		}
		p2.write(w)
	}
}

func TestSearchLogs(t *testing.T) {
	srv := newQueryServer(t, func(w http.ResponseWriter, r *http.Request, b searchBody, n int) {
		if r.URL.Path != pathSearch || r.Method != http.MethodPost {
			w.WriteHeader(404)
			return
		}
		pagedSearch(t)(w, r, b, n)
	})
	q := newQuery(t, srv.URL, nil)

	page, err := q.SearchLogs(t.Context(), SearchLogsRequest{
		Query: `service:checkout "run-7f3a"`, From: "2026-11-18T12:00:00Z", To: "2026-11-18T12:15:00Z",
		Limit: 2, Indexes: []string{"main"}, StorageTier: "indexes",
	})
	if err != nil {
		t.Fatal(err)
	}
	b := srv.body(0)
	if b.Filter.Query != `service:checkout "run-7f3a"` || b.Filter.From != "2026-11-18T12:00:00Z" || b.Sort != "-timestamp" ||
		b.Page.Limit != 2 || b.Filter.Indexes[0] != "main" || b.Filter.StorageTier != "indexes" {
		t.Fatalf("request %+v", b)
	}
	if len(page.Events) != 2 || page.NextCursor == "" || page.Status != "done" || page.RequestID == "" || page.Elapsed != 38*time.Millisecond {
		t.Fatalf("page %+v", page)
	}
	e := page.Events[0]
	if e.ID == "" || e.Host != "host-a" || e.Service != "checkout" || e.Message != "user signed in run-7f3a #2" ||
		!e.Timestamp.Equal(time.Date(2026, 11, 18, 12, 1, 2, 345_000_000, time.UTC)) {
		t.Fatalf("event %+v", e)
	}
	rec := page.Records()[0]
	if rec.Body != e.Message || rec.Severity != SeverityInfo || rec.SeverityText != "info" ||
		rec.Resource["service.name"] != "checkout" || rec.Resource["host.name"] != "host-a" ||
		rec.Resource["deployment.environment"] != "test" || rec.Resource["service.version"] != "1.2.3" ||
		rec.TraceID != "4bf92f3577b34da6a3ce929d0e0e4736" || rec.SpanID != "00f067aa0ba902b7" ||
		rec.Attributes["datadog.log_id"] != e.ID || rec.Attributes["run"] != "run-7f3a" {
		t.Fatalf("record %+v", rec)
	}
	if u, _ := rec.Attributes["user"].(map[string]AttrValue); u["id"] != int64(42) {
		t.Fatalf("int attribute %#v", rec.Attributes["user"])
	}

	t.Run("defaults and next page", func(t *testing.T) {
		before := srv.calls.Load()
		page2, err := q.SearchLogs(t.Context(), SearchLogsRequest{Cursor: page.NextCursor, Sort: SortTimestampAsc, Limit: MaxSearchPageLimit})
		if err != nil || len(page2.Events) != 1 || page2.NextCursor != "" {
			t.Fatal(page2, err)
		}
		b := srv.body(int(before))
		if b.Filter.From != DefaultSearchFrom || b.Filter.To != DefaultSearchTo || b.Filter.Indexes[0] != "*" || b.Sort != "timestamp" || b.Filter.StorageTier != "indexes" ||
			b.Page.Limit != MaxSearchPageLimit || b.Page.Cursor != page.NextCursor || b.Filter.Query != "" {
			t.Fatalf("defaults %+v", b)
		}
		if _, err := q.SearchLogs(t.Context(), SearchLogsRequest{}); err != nil || srv.body(int(before)+1).Page.Limit != DefaultSearchLimit {
			t.Fatal("default limit")
		}
		for _, bad := range []SearchLogsRequest{{Sort: "up"}, {Limit: -1}, {Limit: MaxSearchPageLimit + 1}} {
			if _, err := q.SearchLogs(t.Context(), bad); !errors.Is(err, ErrValidation) {
				t.Fatalf("%+v: %v", bad, err)
			}
		}
	})

	t.Run("iterator", func(t *testing.T) {
		var msgs []string
		for e, err := range q.IterateLogs(t.Context(), SearchLogsRequest{Query: "run"}) {
			if err != nil {
				t.Fatal(err)
			}
			msgs = append(msgs, e.Message)
		}
		if len(msgs) != 3 || !strings.HasSuffix(msgs[2], "#0") {
			t.Fatalf("all pages %v", msgs)
		}
		before := srv.calls.Load()
		n := 0
		for _, err := range q.IterateLogs(t.Context(), SearchLogsRequest{Limit: 3, PageSize: 2}) {
			if err != nil {
				t.Fatal(err)
			}
			n++
		}
		if n != 3 || srv.body(int(before)).Page.Limit != 2 || srv.body(int(before)+1).Page.Limit != 1 {
			t.Fatalf("limit/pageSize: %d", n)
		}
		for _, bad := range []SearchLogsRequest{{Limit: -1}, {PageSize: -1}, {PageSize: MaxSearchPageLimit + 1}} {
			var got error
			for _, err := range q.IterateLogs(t.Context(), bad) {
				got = err
			}
			if !errors.Is(got, ErrValidation) {
				t.Fatalf("%+v: %v", bad, got)
			}
		}
		n = 0
		for _, err := range q.IterateLogs(t.Context(), SearchLogsRequest{Limit: 1}) {
			if err != nil {
				t.Fatal(err)
			}
			n++
		}
		if n != 1 {
			t.Fatalf("limit 1 → %d", n)
		}
		n = 0
		for _, err := range q.IterateLogs(t.Context(), SearchLogsRequest{}) {
			if err != nil {
				t.Fatal(err)
			}
			n++
			break
		}
		if n != 1 {
			t.Fatal("break")
		}
	})

	t.Run("search (LogQueryClient)", func(t *testing.T) {
		before := srv.calls.Load()
		start := time.Date(2026, 11, 18, 12, 0, 0, 0, time.UTC)
		res, err := q.Search(t.Context(), "run", SearchOptions{Start: start, End: start.Add(time.Hour), Order: OrderAsc})
		if err != nil || len(res.Records) != 2 || res.NextCursor == "" || res.Total != nil {
			t.Fatal(res, err)
		}
		b := srv.body(int(before))
		if b.Filter.From != "2026-11-18T12:00:00.000Z" || b.Filter.To != "2026-11-18T13:00:00.000Z" || b.Sort != "timestamp" || b.Page.Limit != 100 {
			t.Fatalf("mapping %+v", b)
		}
		all, err := SearchAll(t.Context(), q, "run", SearchOptions{}, 0)
		if err != nil || len(all) != 3 {
			t.Fatal(len(all), err)
		}
	})

	t.Run("waitForLog", func(t *testing.T) {
		rec, err := q.WaitForLog(t.Context(), "run", WaitForLogOptions{
			Timeout: 5 * time.Second, Predicate: func(r LogRecord) bool { return strings.HasSuffix(r.Body, "#0") },
		})
		if err != nil || !strings.HasSuffix(rec.Body, "#0") {
			t.Fatal(rec, err)
		}
	})
}

func TestSearchResponses(t *testing.T) {
	serve := func(t *testing.T, name string) *LogsQuery {
		f := loadFixture(t, name)
		srv := newQueryServer(t, func(w http.ResponseWriter, _ *http.Request, _ searchBody, _ int) { f.write(w) })
		return newQuery(t, srv.URL, func(cfg *Config) { cfg.Retry = fastRetry(0) })
	}
	t.Run("partial results", func(t *testing.T) {
		page, err := serve(t, "searchLogs.warnings_partial").SearchLogs(t.Context(), SearchLogsRequest{})
		if err != nil || len(page.Warnings) != 1 || page.Warnings[0].Code != "unknown_index" || len(page.Events) != 1 {
			t.Fatal(page, err)
		}
		page, err = serve(t, "searchLogs.timeout_status").SearchLogs(t.Context(), SearchLogsRequest{})
		if err != nil || page.Status != "timeout" || len(page.Events) != 0 {
			t.Fatal(page, err)
		}
		page, err = serve(t, "searchLogs.empty").SearchLogs(t.Context(), SearchLogsRequest{})
		if err != nil || len(page.Events) != 0 {
			t.Fatal(page, err)
		}
	})
	t.Run("errors", func(t *testing.T) {
		cases := map[string]ErrorCode{
			"searchLogs.fakeintake_default": ErrorCodeUnavailable,
			"searchLogs.400_invalid_query":  ErrorCodeValidation,
			"searchLogs.401_missing_keys":   ErrorCodeAuthentication,
			"searchLogs.403_invalid_keys":   ErrorCodeAuthentication,
			"searchLogs.429_rate_limited":   ErrorCodeRateLimited,
		}
		for name, code := range cases {
			_, err := serve(t, name).SearchLogs(t.Context(), SearchLogsRequest{})
			if CodeOf(err) != code || strings.Contains(err.Error(), testAppKey) {
				t.Errorf("%s: %v", name, err)
			}
		}
		var errOut error
		for _, err := range serve(t, "searchLogs.400_invalid_query").IterateLogs(t.Context(), SearchLogsRequest{}) {
			errOut = err
		}
		if !errors.Is(errOut, ErrValidation) {
			t.Fatalf("iterator error %v", errOut)
		}
	})
	t.Run("403 is authentication", func(t *testing.T) {
		srv := newQueryServer(t, func(w http.ResponseWriter, _ *http.Request, _ searchBody, _ int) {
			writeJSON(w, 403, `{"errors":["Forbidden","Application key is invalid"]}`)
		})
		_, err := newQuery(t, srv.URL, nil).SearchLogs(t.Context(), SearchLogsRequest{})
		if e := asError(t, err); e.Code != ErrorCodeAuthentication || e.HTTPStatus != 403 {
			t.Fatalf("%+v", e)
		}
	})
	t.Run("invalid JSON", func(t *testing.T) {
		srv := newQueryServer(t, func(w http.ResponseWriter, _ *http.Request, _ searchBody, _ int) { writeJSON(w, 200, `nope`) })
		if _, err := newQuery(t, srv.URL, nil).SearchLogs(t.Context(), SearchLogsRequest{}); !errors.Is(err, ErrUnavailable) {
			t.Fatal(err)
		}
	})
	t.Run("repeated cursor", func(t *testing.T) {
		srv := newQueryServer(t, func(w http.ResponseWriter, _ *http.Request, _ searchBody, _ int) {
			writeJSON(w, 200, `{"data":[{"id":"a","attributes":{"message":"m"}}],"meta":{"page":{"after":"same"}}}`)
		})
		var last error
		calls := 0
		for _, err := range newQuery(t, srv.URL, nil).IterateLogs(t.Context(), SearchLogsRequest{}) {
			last = err
			calls++
		}
		if !errors.Is(last, ErrUnavailable) || calls != 3 || srv.calls.Load() != 2 {
			t.Fatalf("%v calls=%d requests=%d", last, calls, srv.calls.Load())
		}
	})
}

func TestRateLimitResetHint(t *testing.T) {
	t.Run("long reset is not waited for", func(t *testing.T) {
		f := loadFixture(t, "searchLogs.429_rate_limited")
		srv := newQueryServer(t, func(w http.ResponseWriter, _ *http.Request, _ searchBody, _ int) { f.write(w) })
		_, err := newQuery(t, srv.URL, nil).SearchLogs(t.Context(), SearchLogsRequest{})
		e := asError(t, err)
		if e.Code != ErrorCodeRateLimited || e.RetryAfter != 1820*time.Second || srv.calls.Load() != 1 {
			t.Fatalf("%+v calls=%d", e, srv.calls.Load())
		}
	})
	t.Run("short reset is honoured", func(t *testing.T) {
		srv := newQueryServer(t, func(w http.ResponseWriter, r *http.Request, b searchBody, n int) {
			if n == 1 {
				w.Header().Set("X-RateLimit-Reset", "0")
				writeJSON(w, 429, `{"errors":["Too many requests"]}`)
				return
			}
			loadFixture(t, "searchLogs.empty").write(w)
		})
		if _, err := newQuery(t, srv.URL, nil).SearchLogs(t.Context(), SearchLogsRequest{}); err != nil || srv.calls.Load() != 2 {
			t.Fatalf("%v calls=%d", err, srv.calls.Load())
		}
	})
	t.Run("503 reset is the hint", func(t *testing.T) {
		srv := newQueryServer(t, func(w http.ResponseWriter, _ *http.Request, _ searchBody, _ int) {
			w.Header().Set("X-RateLimit-Reset", "90")
			writeJSON(w, 503, `{"errors":["busy"]}`)
		})
		_, err := newQuery(t, srv.URL, nil).SearchLogs(t.Context(), SearchLogsRequest{})
		if e := asError(t, err); e.Code != ErrorCodeUnavailable || e.RetryAfter != 90*time.Second || srv.calls.Load() != 1 {
			t.Fatalf("%+v calls=%d", e, srv.calls.Load())
		}
	})
	t.Run("retry-after wins", func(t *testing.T) {
		srv := newQueryServer(t, func(w http.ResponseWriter, _ *http.Request, _ searchBody, _ int) {
			w.Header().Set("Retry-After", "120")
			w.Header().Set("X-RateLimit-Reset", "1")
			writeJSON(w, 429, `{}`)
		})
		_, err := newQuery(t, srv.URL, nil).SearchLogs(t.Context(), SearchLogsRequest{})
		if e := asError(t, err); e.RetryAfter != 120*time.Second {
			t.Fatalf("%+v", e)
		}
	})
}

func TestAggregateLogs(t *testing.T) {
	fixtures := map[string]fixture{}
	for _, n := range []string{"aggregateLogs.count_by_service", "aggregateLogs.timeseries", "aggregateLogs.paged", "aggregateLogs.401_missing_keys"} {
		fixtures[n] = loadFixture(t, n)
	}
	var serveName atomic.Value
	srv := newQueryServer(t, func(w http.ResponseWriter, r *http.Request, _ searchBody, _ int) {
		if r.URL.Path != pathAggregate {
			w.WriteHeader(404)
			return
		}
		fixtures[serveName.Load().(string)].write(w)
	})
	q := newQuery(t, srv.URL, nil)

	serveName.Store("aggregateLogs.count_by_service")
	res, err := q.AggregateLogs(t.Context(),
		[]Compute{{Aggregation: "count", Type: "total"}},
		[]GroupBy{{Facet: "service", Limit: 10, Sort: &GroupBySort{Aggregation: "count", Order: "desc"}}},
		AggregateLogsOptions{Query: "service:checkout", Indexes: []string{"main"}})
	if err != nil || len(res.Buckets) != 1 || res.Buckets[0].By["service"] != "checkout" || res.RequestID == "" || res.Elapsed != 52*time.Millisecond {
		t.Fatal(res, err)
	}
	if n, ok := res.Buckets[0].Number("c0"); !ok || n != 3 {
		t.Fatal(n, ok)
	}
	b := srv.body(0)
	if b.Compute[0].Aggregation != "count" || b.GroupBy[0].Facet != "service" || b.GroupBy[0].Sort.Order != "desc" || b.Filter.Query != "service:checkout" || b.Page.Cursor != "" {
		t.Fatalf("request %+v", b)
	}

	serveName.Store("aggregateLogs.timeseries")
	res, err = q.AggregateLogs(t.Context(), []Compute{{Aggregation: "count", Type: "timeseries", Interval: "5m"}}, nil, AggregateLogsOptions{})
	if err != nil {
		t.Fatal(err)
	}
	pts := res.Buckets[0].Timeseries("c0")
	if len(pts) != 3 || pts[0].Value != 3 || !pts[1].Time.Equal(time.Date(2026, 11, 18, 12, 5, 0, 0, time.UTC)) {
		t.Fatalf("points %+v", pts)
	}
	if res.Buckets[0].Timeseries("c9") != nil {
		t.Fatal("missing series")
	}
	if _, ok := res.Buckets[0].Number("c0"); ok {
		t.Fatal("a series is not a number")
	}

	serveName.Store("aggregateLogs.paged")
	res, err = q.AggregateLogs(t.Context(), []Compute{{Aggregation: "count"}}, []GroupBy{{Facet: "@user.id", Limit: 1}}, AggregateLogsOptions{Cursor: "abc"})
	if err != nil || res.NextCursor == "" || res.Buckets[0].By["@user.id"] != int64(42) || srv.body(2).Page.Cursor != "abc" || len(srv.body(2).Compute) != 1 || srv.body(2).Compute[0].Type != "total" {
		t.Fatal(res, err)
	}

	if v, ok := (AggregateBucket{Computes: map[string]AttrValue{"a": "1.5", "b": 2.5, "c": true}}).Number("a"); !ok || v != 1.5 {
		t.Fatal("string number")
	}
	if v, _ := (AggregateBucket{Computes: map[string]AttrValue{"b": 2.5}}).Number("b"); v != 2.5 {
		t.Fatal("float")
	}

	serveName.Store("aggregateLogs.401_missing_keys")
	count := []Compute{{Aggregation: "count"}}
	if _, err := q.AggregateLogs(t.Context(), count, nil, AggregateLogsOptions{}); !errors.Is(err, ErrAuthentication) {
		t.Fatal(err)
	}
	for _, bad := range [][]Compute{nil, {{}}} {
		if _, err := q.AggregateLogs(t.Context(), bad, nil, AggregateLogsOptions{}); !errors.Is(err, ErrValidation) {
			t.Fatal(err)
		}
	}
	if _, err := q.AggregateLogs(t.Context(), count, []GroupBy{{}}, AggregateLogsOptions{}); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
	nodata := newQueryServer(t, func(w http.ResponseWriter, _ *http.Request, _ searchBody, _ int) { writeJSON(w, 200, `{"errors":[]}`) })
	if _, err := newQuery(t, nodata.URL, nil).AggregateLogs(t.Context(), count, nil, AggregateLogsOptions{}); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
}

func TestListStreamsLabels(t *testing.T) {
	f := loadFixture(t, "listStreams.indexes")
	srv := newQueryServer(t, func(w http.ResponseWriter, r *http.Request, _ searchBody, _ int) {
		if r.Method != http.MethodGet || r.URL.Path != pathIndexes {
			w.WriteHeader(404)
			return
		}
		f.write(w)
	})
	q := newQuery(t, srv.URL, nil)
	if names, err := q.ListStreams(t.Context()); err != nil || len(names) != 1 || names[0] != "main" {
		t.Fatal(names, err)
	}
	ix, err := q.ListIndexes(t.Context())
	if err != nil || ix[0] != (LogIndex{Name: "main", Query: "*", RetentionDays: 15, DailyLimit: 300000000}) {
		t.Fatal(ix, err)
	}
	if _, err := q.ListLabels(t.Context()); !errors.Is(err, ErrUnsupported) {
		t.Fatal(err)
	}
	empty := newQueryServer(t, func(w http.ResponseWriter, _ *http.Request, _ searchBody, _ int) { w.WriteHeader(200) })
	if _, err := newQuery(t, empty.URL, nil).ListStreams(t.Context()); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	other := newQueryServer(t, func(w http.ResponseWriter, _ *http.Request, _ searchBody, _ int) { writeJSON(w, 200, `{}`) })
	if _, err := newQuery(t, other.URL, nil).ListStreams(t.Context()); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	denied := newQueryServer(t, func(w http.ResponseWriter, _ *http.Request, _ searchBody, _ int) {
		writeJSON(w, 403, `{"errors":["Forbidden"]}`)
	})
	if _, err := newQuery(t, denied.URL, nil).ListStreams(t.Context()); !errors.Is(err, ErrAuthentication) {
		t.Fatal(err)
	}
}

func TestTail(t *testing.T) {
	t.Run("delivers each log once", func(t *testing.T) {
		srv := newQueryServer(t, func(w http.ResponseWriter, r *http.Request, b searchBody, n int) { pagedSearch(t)(w, r, b, n) })
		q := newQuery(t, srv.URL, nil)
		var mu sync.Mutex
		var got []string
		h, err := q.Tail(t.Context(), "run", func(r LogRecord) {
			mu.Lock()
			got = append(got, r.Body)
			mu.Unlock()
			panic("callbacks may panic")
		}, TailOptions{Interval: 5 * time.Millisecond, Start: time.Date(2026, 11, 18, 12, 0, 0, 0, time.UTC)})
		if err != nil {
			t.Fatal(err)
		}
		deadline := time.Now().Add(5 * time.Second)
		for srv.calls.Load() < 6 && time.Now().Before(deadline) {
			time.Sleep(5 * time.Millisecond)
		}
		h.Stop()
		h.Stop()
		mu.Lock()
		defer mu.Unlock()
		if len(got) != 3 {
			t.Fatalf("got %v", got)
		}
		lt := h.(*LogTail)
		if lt.Err() != nil {
			t.Fatal(lt.Err())
		}
		b := srv.body(0)
		if b.Sort != "timestamp" || b.Filter.From != "2026-11-18T12:00:00.000Z" || b.Filter.To != "now" {
			t.Fatalf("tail request %+v", b)
		}
	})

	t.Run("non-retryable error ends the tail", func(t *testing.T) {
		srv := newQueryServer(t, func(w http.ResponseWriter, _ *http.Request, _ searchBody, _ int) {
			writeJSON(w, 400, `{"errors":["bad query"]}`)
		})
		h, err := newQuery(t, srv.URL, nil).Tail(t.Context(), "(", func(LogRecord) {}, TailOptions{})
		if err != nil {
			t.Fatal(err)
		}
		lt := h.(*LogTail)
		select {
		case <-lt.Done():
		case <-time.After(5 * time.Second):
			t.Fatal("tail did not end")
		}
		if !errors.Is(lt.Err(), ErrValidation) {
			t.Fatal(lt.Err())
		}
	})

	t.Run("retryable errors keep polling and Close stops it", func(t *testing.T) {
		srv := newQueryServer(t, func(w http.ResponseWriter, _ *http.Request, _ searchBody, _ int) { writeJSON(w, 503, `{}`) })
		c := newTestClient(t, srv.URL, func(cfg *Config) { cfg.Retry = fastRetry(0) })
		q, _ := c.Query()
		h, err := q.Tail(context.Background(), "x", func(LogRecord) {}, TailOptions{Interval: time.Millisecond})
		if err != nil {
			t.Fatal(err)
		}
		deadline := time.Now().Add(5 * time.Second)
		for srv.calls.Load() < 3 && time.Now().Before(deadline) {
			time.Sleep(time.Millisecond)
		}
		if err := c.Close(); err != nil {
			t.Fatal(err)
		}
		lt := h.(*LogTail)
		<-lt.Done()
		if lt.Err() != nil || srv.calls.Load() < 3 {
			t.Fatalf("err %v calls %d", lt.Err(), srv.calls.Load())
		}
		if _, err := q.Tail(t.Context(), "x", func(LogRecord) {}, TailOptions{}); !errors.Is(err, ErrCancelled) {
			t.Fatalf("tail after close: %v", err)
		}
	})

	t.Run("validation", func(t *testing.T) {
		q := newQuery(t, "http://127.0.0.1:1", nil)
		if _, err := q.Tail(t.Context(), "x", nil, TailOptions{}); !errors.Is(err, ErrValidation) {
			t.Fatal(err)
		}
	})
}

func TestNormalization(t *testing.T) {
	ids := []struct{ in, want string }{
		{"11803532876627986230", "0000000000000000a3ce929d0e0e4736"},
		{"4BF92F3577B34DA6A3CE929D0E0E4736", "4bf92f3577b34da6a3ce929d0e0e4736"},
		{"abc", "00000000000000000000000000000abc"},
		{"99999999999999999999", ""},
		{"xyz", ""},
		{"", ""},
		{strings.Repeat("a", 40), ""},
	}
	for _, tc := range ids {
		if got := normalizeID(tc.in, 32); got != tc.want {
			t.Errorf("normalizeID(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
	sev := map[string]Severity{
		"emerg": SeverityFatal, "critical": SeverityFatal, "err": SeverityError, "warning": SeverityWarn,
		"ok": SeverityInfo, "": SeverityInfo, "debug": SeverityDebug, "trace": SeverityTrace, "INFO3": SeverityInfo + 2, "weird": SeverityInfo,
	}
	for in, want := range sev {
		if got := statusSeverity(in); got != want {
			t.Errorf("statusSeverity(%q) = %v", in, got)
		}
	}
	e := LogEvent{Message: "m", Status: "error", Attributes: map[string]AttrValue{
		"dd.trace_id": int64(42), "otel": map[string]AttrValue{"span_id": "0000000000000007"}, "dd": map[string]AttrValue{"span_id": 1.5},
	}}
	rec := e.Record()
	if rec.TraceID != "0000000000000000000000000000002a" || rec.SpanID != "0000000000000007" || rec.Severity != SeverityError {
		t.Fatalf("%+v", rec)
	}
	if attrString(1.5) != "1.5" || attrString(true) != "" {
		t.Fatal("attrString")
	}
	if got := (LogEvent{Attributes: map[string]AttrValue{"dd": map[string]AttrValue{"trace_id": "5"}}}).Record().SpanID; got != "" {
		t.Fatal(got)
	}
}
