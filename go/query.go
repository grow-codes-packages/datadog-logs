package datadoglogs

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"iter"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// LogsSort is the sort order of SearchLogs (Datadog wire values).
type LogsSort string

// Sort orders of SearchLogs.
const (
	// SortTimestampAsc returns the oldest logs first.
	SortTimestampAsc LogsSort = "timestamp"
	// SortTimestampDesc returns the newest logs first (default).
	SortTimestampDesc LogsSort = "-timestamp"
)

// Query defaults.
const (
	// DefaultSearchFrom is the default lower time bound of a search (date math).
	DefaultSearchFrom = "now-15m"
	// DefaultSearchTo is the default upper time bound of a search.
	DefaultSearchTo = "now"
	// MaxSearchPageLimit is the largest page size of SearchLogs (Datadog maximum).
	MaxSearchPageLimit = 5000
	// DefaultIteratorPageSize is the page size of IterateLogs when PageSize is 0.
	DefaultIteratorPageSize = 1000
	// DefaultQueryPollInterval is the poll interval of WaitForLog and Tail when
	// none is given (the Datadog search rate limit is low).
	DefaultQueryPollInterval = 5 * time.Second
)

// FormatTime formats t for From / To: ISO-8601 UTC with milliseconds.
func FormatTime(t time.Time) string { return t.UTC().Format("2006-01-02T15:04:05.000Z") }

// SearchLogsRequest is the input of LogsQuery.SearchLogs and IterateLogs.
type SearchLogsRequest struct {
	// Query is the Datadog log search query (default "*"), for example
	// `service:checkout "run-7f3a"`.
	Query string
	// From and To bound the search: ISO-8601, epoch milliseconds or date math
	// ("now-15m"). Defaults DefaultSearchFrom / DefaultSearchTo. See FormatTime.
	From, To string
	// Limit is the page size of SearchLogs (1..MaxSearchPageLimit, default
	// 100) and the total number of logs of IterateLogs (0 = all).
	Limit int
	// PageSize is the page size of IterateLogs (1..MaxSearchPageLimit,
	// default min(Limit, DefaultIteratorPageSize)). SearchLogs ignores it.
	PageSize int
	// Sort is SortTimestampDesc (default) or SortTimestampAsc.
	Sort LogsSort
	// Indexes to search (default ["*"]).
	Indexes []string
	// StorageTier is "indexes" (default), "online-archives" or "flex".
	StorageTier string
	// Cursor continues a previous search (SearchLogsPage.NextCursor).
	Cursor string
}

// LogEvent is one log as returned by the Datadog search API.
type LogEvent struct {
	// ID is the Datadog log id.
	ID string `json:"id"`
	// Timestamp is when the log happened.
	Timestamp time.Time `json:"timestamp"`
	// Host, Service, Status and Message are reserved Datadog attributes.
	Host    string `json:"host,omitempty"`
	Service string `json:"service,omitempty"`
	Status  string `json:"status,omitempty"`
	Message string `json:"message"`
	// Tags are the log tags (env:test, version:1.2.3, ...).
	Tags []string `json:"tags,omitempty"`
	// Attributes are the custom attributes (nested objects after Datadog's
	// JSON preprocessing, for example {"dd":{"trace_id":"…"}}).
	Attributes map[string]AttrValue `json:"attributes"`
}

// SearchWarning is a partial-result warning of the search API (for example
// code "unknown_index").
type SearchWarning struct {
	Code   string `json:"code"`
	Title  string `json:"title,omitempty"`
	Detail string `json:"detail,omitempty"`
}

// SearchLogsPage is one page of SearchLogs.
type SearchLogsPage struct {
	// Events in the requested order, as returned by Datadog.
	Events []LogEvent `json:"events"`
	// NextCursor continues the search; empty on the last page.
	NextCursor string `json:"nextCursor,omitempty"`
	// Status is "done", or "timeout" for partial results.
	Status string `json:"status,omitempty"`
	// Warnings report partial results (unknown index, ...).
	Warnings []SearchWarning `json:"warnings,omitempty"`
	// RequestID is meta.request_id.
	RequestID string `json:"requestId,omitempty"`
	// Elapsed is the server-side duration.
	Elapsed time.Duration `json:"elapsed,omitempty"`
}

// Records returns the logs as normalized LogRecords (see LogEvent.Record).
func (p *SearchLogsPage) Records() []LogRecord {
	out := make([]LogRecord, len(p.Events))
	for i, e := range p.Events {
		out[i] = e.Record()
	}
	return out
}

// LogsQuery is the Datadog query client (needs the application key):
// SearchLogs, IterateLogs, AggregateLogs, ListIndexes / ListStreams, Tail
// (polling search) and WaitForLog. It implements LogQueryClient. Get it from
// Client.Query.
type LogsQuery struct {
	client *Client
}

var _ LogQueryClient = (*LogsQuery)(nil)

func (q *LogsQuery) headers() http.Header {
	h := q.client.apiKeyHeader()
	h.Set(HeaderAppKey, q.client.cfg.AppKey.Reveal())
	return h
}

// call sends a query request and decodes the JSON answer into out (numbers
// as json.Number).
func (q *LogsQuery) call(ctx context.Context, op, method, path string, body, out any) error {
	res, err := q.client.api.do(ctx, &request{
		Method: method, Path: path, Header: q.headers(), JSON: body, Idempotent: true, Operation: op,
	})
	if err != nil {
		return mapDatadogError(err)
	}
	dec := json.NewDecoder(bytes.NewReader(res.Body))
	dec.UseNumber()
	if err := dec.Decode(out); err != nil {
		return &Error{Code: ErrorCodeUnavailable, Message: op + ": unexpected response (invalid JSON)", HTTPStatus: res.Status}
	}
	return nil
}

type wireFilter struct {
	Query       string   `json:"query,omitempty"`
	From        string   `json:"from"`
	To          string   `json:"to"`
	Indexes     []string `json:"indexes"`
	StorageTier string   `json:"storage_tier,omitempty"`
}

func newFilter(query, from, to string, indexes []string, tier string) wireFilter {
	if len(indexes) == 0 {
		indexes = []string{"*"}
	}
	return wireFilter{
		Query: query, From: firstNonEmpty(from, DefaultSearchFrom), To: firstNonEmpty(to, DefaultSearchTo),
		Indexes: indexes, StorageTier: firstNonEmpty(tier, "indexes"),
	}
}

type wirePage struct {
	Limit  int    `json:"limit,omitempty"`
	Cursor string `json:"cursor,omitempty"`
}

type wireMeta struct {
	Elapsed json.Number `json:"elapsed"`
	Page    *struct {
		After string `json:"after"`
	} `json:"page"`
	RequestID string          `json:"request_id"`
	Status    string          `json:"status"`
	Warnings  []SearchWarning `json:"warnings"`
}

func (m wireMeta) after() string {
	if m.Page == nil {
		return ""
	}
	return m.Page.After
}

func (m wireMeta) elapsed() time.Duration {
	ms, _ := m.Elapsed.Int64()
	return time.Duration(ms) * time.Millisecond
}

type wireLog struct {
	ID         string `json:"id"`
	Attributes struct {
		Timestamp  string         `json:"timestamp"`
		Host       string         `json:"host"`
		Service    string         `json:"service"`
		Status     string         `json:"status"`
		Message    string         `json:"message"`
		Tags       []string       `json:"tags"`
		Attributes map[string]any `json:"attributes"`
	} `json:"attributes"`
}

// SearchLogs returns one page of logs matching the request
// (POST /api/v2/logs/events/search). Pass NextCursor back as Cursor for the
// next page, or use IterateLogs. A "timeout" status and warnings mean
// partial results and are returned, not raised. A Limit outside
// 0..MaxSearchPageLimit is a VALIDATION error.
func (q *LogsQuery) SearchLogs(ctx context.Context, req SearchLogsRequest) (*SearchLogsPage, error) {
	const op = "logs.searchLogs"
	if req.Limit < 0 || req.Limit > MaxSearchPageLimit {
		return nil, ValidationError(op, fmt.Sprintf("limit must be between 1 and %d", MaxSearchPageLimit))
	}
	limit := req.Limit
	if limit == 0 {
		limit = DefaultSearchLimit
	}
	sort := req.Sort
	if sort == "" {
		sort = SortTimestampDesc
	}
	if sort != SortTimestampAsc && sort != SortTimestampDesc {
		return nil, ValidationError(op, "sort must be \"timestamp\" or \"-timestamp\"")
	}
	body := map[string]any{
		"filter": newFilter(req.Query, req.From, req.To, req.Indexes, req.StorageTier),
		"sort":   string(sort),
		"page":   wirePage{Limit: limit, Cursor: req.Cursor},
	}
	var resp struct {
		Data *[]wireLog `json:"data"`
		Meta wireMeta   `json:"meta"`
	}
	if err := q.call(ctx, op, http.MethodPost, pathSearch, body, &resp); err != nil {
		return nil, err
	}
	if resp.Data == nil {
		return nil, &Error{Code: ErrorCodeUnavailable, Message: op + ": unexpected response (no data array)", RequestID: resp.Meta.RequestID}
	}
	page := &SearchLogsPage{
		Events: make([]LogEvent, 0, len(*resp.Data)), NextCursor: resp.Meta.after(), Status: resp.Meta.Status,
		Warnings: resp.Meta.Warnings, RequestID: resp.Meta.RequestID, Elapsed: resp.Meta.elapsed(),
	}
	for _, w := range *resp.Data {
		ts, _ := time.Parse(time.RFC3339Nano, w.Attributes.Timestamp)
		page.Events = append(page.Events, LogEvent{
			ID: w.ID, Timestamp: ts.UTC(), Host: w.Attributes.Host, Service: w.Attributes.Service,
			Status: w.Attributes.Status, Message: w.Attributes.Message, Tags: w.Attributes.Tags,
			Attributes: NormalizeAttributes(w.Attributes.Attributes),
		})
	}
	return page, nil
}

// IterateLogs iterates over every matching log, following the cursor page by
// page until the last page or req.Limit logs (0 = all). The iteration stops at
// the first error, which is yielded once: a negative Limit or a PageSize
// outside 0..MaxSearchPageLimit is a VALIDATION error, a repeated cursor an
// UNAVAILABLE error (protocol loop guard).
func (q *LogsQuery) IterateLogs(ctx context.Context, req SearchLogsRequest) iter.Seq2[LogEvent, error] {
	return func(yield func(LogEvent, error) bool) {
		const op = "logs.iterateLogs"
		total := req.Limit
		switch {
		case total < 0:
			yield(LogEvent{}, ValidationError(op, "limit must be a positive integer"))
			return
		case req.PageSize < 0 || req.PageSize > MaxSearchPageLimit:
			yield(LogEvent{}, ValidationError(op, fmt.Sprintf("pageSize must be between 1 and %d", MaxSearchPageLimit)))
			return
		}
		pageSize := req.PageSize
		if pageSize == 0 {
			pageSize = DefaultIteratorPageSize
			if total > 0 {
				pageSize = min(total, pageSize)
			}
		}
		seen := map[string]bool{}
		count := 0
		for {
			req.Limit = pageSize
			if total > 0 {
				req.Limit = min(pageSize, total-count)
			}
			page, err := q.SearchLogs(ctx, req)
			if err != nil {
				yield(LogEvent{}, err)
				return
			}
			for _, e := range page.Events {
				if !yield(e, nil) {
					return
				}
				count++
				if total > 0 && count >= total {
					return
				}
			}
			if page.NextCursor == "" || len(page.Events) == 0 {
				return
			}
			if seen[page.NextCursor] || page.NextCursor == req.Cursor {
				yield(LogEvent{}, &Error{Code: ErrorCodeUnavailable, Message: op + ": the service repeated a page cursor"})
				return
			}
			seen[page.NextCursor] = true
			req.Cursor = page.NextCursor
		}
	}
}

// Search returns one page of normalized records (LogQueryClient). Start/End
// default to the last 15 minutes, Limit to 100 (at most MaxSearchPageLimit),
// Order to newest first.
func (q *LogsQuery) Search(ctx context.Context, query string, opts SearchOptions) (LogSearchResult, error) {
	opts = opts.WithDefaults()
	req := SearchLogsRequest{Query: query, Limit: opts.Limit, Cursor: opts.Cursor, Sort: SortTimestampDesc}
	if opts.Order == OrderAsc {
		req.Sort = SortTimestampAsc
	}
	if !opts.Start.IsZero() {
		req.From = FormatTime(opts.Start)
	}
	if !opts.End.IsZero() {
		req.To = FormatTime(opts.End)
	}
	page, err := q.SearchLogs(ctx, req)
	if err != nil {
		return LogSearchResult{}, err
	}
	return LogSearchResult{Records: page.Records(), NextCursor: page.NextCursor}, nil
}

// LogIndex is one Datadog log index.
type LogIndex struct {
	Name string `json:"name"`
	// Query is the index filter query.
	Query         string `json:"query,omitempty"`
	RetentionDays int64  `json:"retentionDays,omitempty"`
	DailyLimit    int64  `json:"dailyLimit,omitempty"`
	IsRateLimited bool   `json:"isRateLimited,omitempty"`
}

// ListIndexes returns the log indexes (GET /api/v1/logs/config/indexes;
// needs the logs_read_config permission).
func (q *LogsQuery) ListIndexes(ctx context.Context) ([]LogIndex, error) {
	const op = "logs.listIndexes"
	var resp struct {
		Indexes *[]struct {
			Name   string `json:"name"`
			Filter struct {
				Query string `json:"query"`
			} `json:"filter"`
			RetentionDays json.Number `json:"num_retention_days"`
			DailyLimit    json.Number `json:"daily_limit"`
			IsRateLimited bool        `json:"is_rate_limited"`
		} `json:"indexes"`
	}
	if err := q.call(ctx, op, http.MethodGet, pathIndexes, nil, &resp); err != nil {
		return nil, err
	}
	if resp.Indexes == nil {
		return nil, &Error{Code: ErrorCodeUnavailable, Message: op + ": unexpected response (no indexes array)"}
	}
	out := make([]LogIndex, 0, len(*resp.Indexes))
	for _, ix := range *resp.Indexes {
		days, _ := ix.RetentionDays.Int64()
		limit, _ := ix.DailyLimit.Int64()
		out = append(out, LogIndex{Name: ix.Name, Query: ix.Filter.Query, RetentionDays: days, DailyLimit: limit, IsRateLimited: ix.IsRateLimited})
	}
	return out, nil
}

// ListStreams returns the names of the log indexes (see ListIndexes).
func (q *LogsQuery) ListStreams(ctx context.Context) ([]string, error) {
	indexes, err := q.ListIndexes(ctx)
	if err != nil {
		return nil, err
	}
	names := make([]string, len(indexes))
	for i, ix := range indexes {
		names[i] = ix.Name
	}
	return names, nil
}

// ListLabels returns an UNSUPPORTED error: Datadog has no public API that
// lists facets or attribute names.
func (q *LogsQuery) ListLabels(context.Context) ([]string, error) {
	return nil, UnsupportedError("logs.listLabels", "Datadog has no API that lists log facets")
}

// WaitForLog polls Search until a record matches query (and opts.Predicate).
// Interval defaults to DefaultQueryPollInterval (the search rate limit is
// low), Timeout to 30 s; it returns a TIMEOUT error when nothing matches.
func (q *LogsQuery) WaitForLog(ctx context.Context, query string, opts WaitForLogOptions) (LogRecord, error) {
	if opts.Interval <= 0 {
		opts.Interval = DefaultQueryPollInterval
	}
	return WaitForLog(ctx, q, query, opts)
}

// Record converts the log to a normalized LogRecord: body ← message,
// severityText ← status (severity mapped from Datadog's status words),
// resource ← service, host and the env:/version: tags, attributes ← the
// custom attributes plus datadog.log_id and datadog.tags, traceId/spanId ←
// otel.trace_id/otel.span_id or dd.trace_id/dd.span_id (decimal or hex).
func (e LogEvent) Record() LogRecord {
	rec := LogRecord{
		Timestamp:    e.Timestamp,
		Severity:     statusSeverity(e.Status),
		SeverityText: e.Status,
		Body:         e.Message,
		Attributes:   cloneAttrs(e.Attributes),
		Resource:     map[string]AttrValue{},
	}
	if e.ID != "" {
		rec.Attributes["datadog.log_id"] = e.ID
	}
	if len(e.Tags) > 0 {
		tags := make([]AttrValue, len(e.Tags))
		for i, t := range e.Tags {
			tags[i] = t
		}
		rec.Attributes["datadog.tags"] = tags
	}
	if e.Service != "" {
		rec.Resource["service.name"] = e.Service
	}
	if e.Host != "" {
		rec.Resource["host.name"] = e.Host
	}
	for _, t := range e.Tags {
		if v, ok := strings.CutPrefix(t, "env:"); ok {
			rec.Resource["deployment.environment"] = v
		} else if v, ok := strings.CutPrefix(t, "version:"); ok {
			rec.Resource["service.version"] = v
		}
	}
	rec.TraceID = normalizeID(firstNonEmpty(attrLookup(e.Attributes, "otel", "trace_id"), attrLookup(e.Attributes, "dd", "trace_id")), 32)
	if rec.TraceID != "" {
		rec.SpanID = normalizeID(firstNonEmpty(attrLookup(e.Attributes, "otel", "span_id"), attrLookup(e.Attributes, "dd", "span_id")), 16)
	}
	return rec
}

// statusSeverity maps a Datadog status word to a severity (unknown → INFO).
func statusSeverity(status string) Severity {
	switch strings.ToLower(strings.TrimSpace(status)) {
	case "emerg", "emergency", "alert", "critical", "fatal":
		return SeverityFatal
	case "error", "err":
		return SeverityError
	case "warn", "warning":
		return SeverityWarn
	case "notice", "info", "ok", "":
		return SeverityInfo
	case "debug":
		return SeverityDebug
	case "trace":
		return SeverityTrace
	}
	if s, ok := ParseSeverity(status); ok {
		return s
	}
	return SeverityInfo
}

// attrLookup finds "<ns>.<key>" as a flat key or nested object.
func attrLookup(attrs map[string]AttrValue, ns, key string) string {
	if v, ok := attrs[ns+"."+key]; ok {
		return attrString(v)
	}
	if inner, ok := attrs[ns].(map[string]AttrValue); ok {
		return attrString(inner[key])
	}
	return ""
}

func attrString(v AttrValue) string {
	switch x := v.(type) {
	case string:
		return x
	case int64:
		return strconv.FormatInt(x, 10)
	case float64:
		return strconv.FormatFloat(x, 'f', -1, 64)
	}
	return ""
}

// normalizeID turns a Datadog id (decimal of up to 64 bits, or hex) into
// lower-case hex left-padded to length; invalid ids give "".
func normalizeID(id string, length int) string {
	id = strings.ToLower(strings.TrimSpace(id))
	if id == "" {
		return ""
	}
	if len(id) <= 20 && strings.Trim(id, "0123456789") == "" {
		n, err := strconv.ParseUint(id, 10, 64)
		if err != nil {
			return ""
		}
		return fmt.Sprintf("%0*x", length, n)
	}
	if len(id) > length {
		return ""
	}
	if _, err := hex.DecodeString(strings.Repeat("0", len(id)%2) + id); err != nil {
		return ""
	}
	return strings.Repeat("0", length-len(id)) + id
}
