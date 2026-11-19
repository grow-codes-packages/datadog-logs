package datadoglogs

import (
	"context"
	"net/http"
	"strconv"
	"time"
)

// Compute is one aggregation of AggregateLogs. Results are keyed c0, c1, …
// in Compute order.
type Compute struct {
	// Aggregation is count, cardinality, pc75, pc90, pc95, pc98, pc99, sum,
	// min, max, avg or median.
	Aggregation string `json:"aggregation"`
	// Type is "total" (Datadog default) or "timeseries".
	Type string `json:"type,omitempty"`
	// Interval is the timeseries bucket size, for example "5m".
	Interval string `json:"interval,omitempty"`
	// Metric is the measured attribute, for example "@duration" (not for count).
	Metric string `json:"metric,omitempty"`
}

// GroupBySort orders the buckets of a GroupBy.
type GroupBySort struct {
	Aggregation string `json:"aggregation,omitempty"`
	// Order is "asc" or "desc".
	Order string `json:"order,omitempty"`
	// Type is "alphabetical" or "measure".
	Type   string `json:"type,omitempty"`
	Metric string `json:"metric,omitempty"`
}

// GroupByHistogram buckets a numeric facet.
type GroupByHistogram struct {
	Interval float64 `json:"interval"`
	Min      float64 `json:"min"`
	Max      float64 `json:"max"`
}

// GroupBy splits the aggregation by a facet.
type GroupBy struct {
	// Facet is required, for example "service" or "@user.id".
	Facet string `json:"facet"`
	// Limit is the maximum number of buckets (Datadog default 10).
	Limit     int               `json:"limit,omitempty"`
	Sort      *GroupBySort      `json:"sort,omitempty"`
	Missing   AttrValue         `json:"missing,omitempty"`
	Total     AttrValue         `json:"total,omitempty"`
	Histogram *GroupByHistogram `json:"histogram,omitempty"`
}

// AggregateLogsOptions filter the logs of LogsQuery.AggregateLogs.
type AggregateLogsOptions struct {
	// Query, From, To, Indexes and StorageTier filter the logs like SearchLogsRequest.
	Query       string
	From, To    string
	Indexes     []string
	StorageTier string
	// Cursor continues a previous aggregation (AggregateLogsResult.NextCursor).
	Cursor string
}

// AggregateBucket is one group of an aggregation.
type AggregateBucket struct {
	// By maps each GroupBy facet to the bucket's value (empty without GroupBy).
	By map[string]AttrValue `json:"by"`
	// Computes maps c0, c1, … to a number, a string or a timeseries
	// ([]AttrValue of {"time","value"} objects).
	Computes map[string]AttrValue `json:"computes"`
}

// TimeseriesPoint is one point of a timeseries compute.
type TimeseriesPoint struct {
	Time  time.Time `json:"time"`
	Value float64   `json:"value"`
}

// Number returns compute key (for example "c0") as a number.
func (b AggregateBucket) Number(key string) (float64, bool) {
	return attrFloat(b.Computes[key])
}

// Timeseries returns compute key as timeseries points (nil when it is not one).
func (b AggregateBucket) Timeseries(key string) []TimeseriesPoint {
	items, ok := b.Computes[key].([]AttrValue)
	if !ok {
		return nil
	}
	out := make([]TimeseriesPoint, 0, len(items))
	for _, it := range items {
		m, _ := it.(map[string]AttrValue)
		ts, _ := m["time"].(string)
		t, _ := time.Parse(time.RFC3339Nano, ts)
		v, _ := attrFloat(m["value"])
		out = append(out, TimeseriesPoint{Time: t.UTC(), Value: v})
	}
	return out
}

func attrFloat(v AttrValue) (float64, bool) {
	switch x := v.(type) {
	case int64:
		return float64(x), true
	case float64:
		return x, true
	case string:
		f, err := strconv.ParseFloat(x, 64)
		return f, err == nil
	}
	return 0, false
}

// AggregateLogsResult is the answer of AggregateLogs.
type AggregateLogsResult struct {
	Buckets []AggregateBucket `json:"buckets"`
	// NextCursor continues the aggregation; empty on the last page.
	NextCursor string          `json:"nextCursor,omitempty"`
	Status     string          `json:"status,omitempty"`
	Warnings   []SearchWarning `json:"warnings,omitempty"`
	RequestID  string          `json:"requestId,omitempty"`
	Elapsed    time.Duration   `json:"elapsed,omitempty"`
}

// AggregateLogs computes the aggregations in compute (results keyed c0, c1,
// … in order) over the logs matching opts, optionally grouped by facets
// (POST /api/v2/logs/analytics/aggregate). An empty compute list, an empty
// Aggregation or an empty Facet is a VALIDATION error.
func (q *LogsQuery) AggregateLogs(ctx context.Context, compute []Compute, groupBy []GroupBy, opts AggregateLogsOptions) (*AggregateLogsResult, error) {
	const op = "logs.aggregateLogs"
	if len(compute) == 0 {
		return nil, ValidationError(op, "compute must not be empty")
	}
	for _, c := range compute {
		if c.Aggregation == "" {
			return nil, ValidationError(op, "every compute needs an aggregation")
		}
	}
	for _, g := range groupBy {
		if g.Facet == "" {
			return nil, ValidationError(op, "every groupBy needs a facet")
		}
	}
	body := map[string]any{"compute": compute, "filter": newFilter(opts.Query, opts.From, opts.To, opts.Indexes, opts.StorageTier)}
	if len(groupBy) > 0 {
		body["group_by"] = groupBy
	}
	if opts.Cursor != "" {
		body["page"] = wirePage{Cursor: opts.Cursor}
	}
	var resp struct {
		Data *struct {
			Buckets []struct {
				By       map[string]any `json:"by"`
				Computes map[string]any `json:"computes"`
			} `json:"buckets"`
		} `json:"data"`
		Meta wireMeta `json:"meta"`
	}
	if err := q.call(ctx, op, http.MethodPost, pathAggregate, body, &resp); err != nil {
		return nil, err
	}
	if resp.Data == nil {
		return nil, &Error{Code: ErrorCodeUnavailable, Message: op + ": unexpected response (no data)", RequestID: resp.Meta.RequestID}
	}
	out := &AggregateLogsResult{
		Buckets: make([]AggregateBucket, 0, len(resp.Data.Buckets)), NextCursor: resp.Meta.after(),
		Status: resp.Meta.Status, Warnings: resp.Meta.Warnings, RequestID: resp.Meta.RequestID, Elapsed: resp.Meta.elapsed(),
	}
	for _, b := range resp.Data.Buckets {
		out.Buckets = append(out.Buckets, AggregateBucket{By: NormalizeAttributes(b.By), Computes: NormalizeAttributes(b.Computes)})
	}
	return out, nil
}
