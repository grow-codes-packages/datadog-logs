package datadoglogs

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"
)

// TailLag is how far back each Tail poll searches again before the newest
// seen log, so logs indexed late are still delivered (ids are de-duplicated).
const TailLag = time.Minute

// LogTail is a running Tail. Stop is idempotent; Done is closed when the tail
// ends (Stop, context done, client closed or a non-retryable error, see Err).
type LogTail struct {
	cancel context.CancelFunc
	done   chan struct{}
	mu     sync.Mutex
	err    error
}

var _ TailHandle = (*LogTail)(nil)

// Stop ends the tail and waits until the poll loop has exited.
func (t *LogTail) Stop() {
	t.cancel()
	<-t.done
}

// Done is closed when the tail has ended.
func (t *LogTail) Done() <-chan struct{} { return t.done }

// Err returns the error that ended the tail, or nil (stopped or context done).
func (t *LogTail) Err() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.err
}

// Tail polls the search API every opts.Interval (default
// DefaultQueryPollInterval) for logs matching query from opts.Start (default
// now), oldest first, and calls onRecord once per log id. Datadog has no
// public streaming API, so this is a polling tail; mind the search rate
// limit. Retryable errors keep the tail polling; other errors end it (see
// LogTail.Err). Client.Close stops it.
func (q *LogsQuery) Tail(ctx context.Context, query string, onRecord func(LogRecord), opts TailOptions) (TailHandle, error) {
	if onRecord == nil {
		return nil, ValidationError("logs.tail", "onRecord is required")
	}
	start := opts.Start
	if start.IsZero() {
		start = time.Now()
	}
	interval := opts.Interval
	if interval <= 0 {
		interval = DefaultQueryPollInterval
	}
	tctx, cancel := context.WithCancel(ctx)
	t := &LogTail{cancel: cancel, done: make(chan struct{})}
	if err := q.client.register(func(context.Context) error { t.Stop(); return nil }); err != nil {
		cancel()
		return nil, err
	}
	go q.tailLoop(tctx, t, query, start, interval, onRecord)
	return t, nil
}

func (q *LogsQuery) tailLoop(ctx context.Context, t *LogTail, query string, start time.Time, interval time.Duration, onRecord func(LogRecord)) {
	defer close(t.done)
	defer t.cancel()
	seen := map[string]time.Time{}
	newest := start
	for {
		from := newest.Add(-TailLag)
		if from.Before(start) {
			from = start
		}
		req := SearchLogsRequest{Query: query, From: FormatTime(from), To: DefaultSearchTo, Sort: SortTimestampAsc}
		for e, err := range q.IterateLogs(ctx, req) {
			if err != nil {
				if ctx.Err() != nil {
					return
				}
				var sdkErr *Error
				if !errors.As(err, &sdkErr) || !sdkErr.Retryable {
					t.mu.Lock()
					t.err = err
					t.mu.Unlock()
					return
				}
				q.client.api.log(slog.LevelDebug, "logs.tail: retryable search error", slog.String("code", string(sdkErr.Code)))
				break
			}
			if _, dup := seen[e.ID]; dup {
				continue
			}
			seen[e.ID] = e.Timestamp
			if e.Timestamp.After(newest) {
				newest = e.Timestamp
			}
			rec := e.Record()
			safeCall(func() { onRecord(rec) })
		}
		for id, ts := range seen {
			if ts.Before(newest.Add(-2 * TailLag)) {
				delete(seen, id)
			}
		}
		if sleepContext(ctx, interval) != nil {
			return
		}
	}
}
