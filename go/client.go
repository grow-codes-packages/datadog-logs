package datadoglogs

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"time"
)

// Datadog API paths.
const (
	pathValidate  = "/api/v1/validate"
	pathIntake    = "/api/v2/logs"
	pathSearch    = "/api/v2/logs/events/search"
	pathAggregate = "/api/v2/logs/analytics/aggregate"
	pathIndexes   = "/api/v1/logs/config/indexes"
)

// Datadog authentication headers.
const (
	// HeaderAPIKey carries the API key on every request.
	HeaderAPIKey = "DD-API-KEY" //nolint:gosec // a header name, not a credential
	// HeaderAppKey carries the application key on query requests only.
	HeaderAppKey = "DD-APPLICATION-KEY"
)

// Client is the Datadog Log Management client. It is safe for concurrent use.
//
// Two hosts are used: the logs intake (NewLogClient, Exporter) and the API
// (Health, Ping, ValidateAPIKey, Query). Close flushes every LogClient made
// by NewLogClient and stops running tails.
type Client struct {
	cfg       Config
	endpoints SiteEndpoints
	intake    *httpTransport
	api       *httpTransport
	exporter  *IntakeExporter

	mu       sync.Mutex
	closers  []func(ctx context.Context) error
	closed   bool
	closeErr error
	once     sync.Once
}

// NewClient validates cfg and returns a client.
func NewClient(cfg Config) (*Client, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	ep, _ := cfg.Endpoints() // validated above
	cfg.CommonConfig = cfg.common(ep.APIURL)
	intake, err := newDatadogTransport(cfg.common(ep.IntakeURL), cfg)
	if err != nil {
		return nil, err
	}
	api, err := newDatadogTransport(cfg.CommonConfig, cfg)
	if err != nil {
		intake.close()
		return nil, err
	}
	c := &Client{cfg: cfg, endpoints: ep, intake: intake, api: api}
	c.exporter = &IntakeExporter{client: c}
	for _, u := range []string{ep.IntakeURL, ep.APIURL} {
		if pu, err := url.Parse(u); err == nil && pu.Scheme == "http" {
			api.log(slog.LevelWarn, "a Datadog endpoint uses plain http; the API key travels unencrypted (use only for local test intakes)")
			break
		}
	}
	return c, nil
}

func newDatadogTransport(cc CommonConfig, cfg Config) (*httpTransport, error) {
	t, err := newHTTPTransport(cc)
	if err != nil {
		return nil, err
	}
	t.client.Transport = rateLimitHint{next: t.transport}
	for _, s := range []Secret{cfg.APIKey, cfg.AppKey} {
		if !s.IsZero() {
			t.addSecret(s.Reveal())
		}
	}
	return t, nil
}

// NewClientFromEnv builds a client from the environment (see ConfigFromEnv).
func NewClientFromEnv() (*Client, error) {
	cfg, err := ConfigFromEnv()
	if err != nil {
		return nil, err
	}
	return NewClient(cfg)
}

// Config returns the effective configuration.
func (c *Client) Config() Config { return c.cfg }

// Endpoints returns the effective intake and API base URLs.
func (c *Client) Endpoints() SiteEndpoints { return c.endpoints }

func (c *Client) apiKeyHeader() http.Header {
	return http.Header{HeaderAPIKey: {c.cfg.APIKey.Reveal()}}
}

// validateBody is the answer of GET /api/v1/validate.
type validateBody struct {
	Valid *bool `json:"valid"`
}

func parseValid(body []byte) *bool {
	var v validateBody
	if json.Unmarshal(body, &v) != nil {
		return nil
	}
	return v.Valid
}

// Health reports whether the Datadog API is reachable, using
// GET /api/v1/validate (Datadog has no unauthenticated health endpoint).
// 2xx → healthy "ok"; 401/403 → not healthy, "unauthorized"; 429 → "degraded";
// 5xx and other statuses → "unhealthy"; network errors → "unreachable".
// details["apiKeyValid"] is "true" only for {"valid":true}. It fails
// only for CANCELLED and CONFIG.
func (c *Client) Health(ctx context.Context) (HealthStatus, error) {
	res, err := c.api.do(ctx, &request{
		Method: http.MethodGet, Path: pathValidate, Header: c.apiKeyHeader(),
		Operation: "health", NoRetry: true, RawStatus: true,
	})
	if err != nil {
		switch CodeOf(err) {
		case ErrorCodeCancelled, ErrorCodeConfig:
			return HealthStatus{}, err
		}
		return HealthStatus{Status: "unreachable", Details: map[string]string{"error": err.Error()}}, nil
	}
	hs := HealthStatus{Details: map[string]string{"httpStatus": strconv.Itoa(res.Status)}}
	switch {
	case res.Status >= 200 && res.Status < 300:
		hs.Healthy, hs.Status = true, "ok"
		v := parseValid(res.Body)
		hs.Details["apiKeyValid"] = strconv.FormatBool(v != nil && *v)
	case res.Status == http.StatusUnauthorized || res.Status == http.StatusForbidden:
		hs.Status = "unauthorized"
		hs.Details["apiKeyValid"] = "false"
	case res.Status == http.StatusTooManyRequests:
		hs.Status = "degraded"
	default:
		hs.Status = "unhealthy"
	}
	return hs, nil
}

// Ping validates the API key with GET /api/v1/validate and returns the
// latency. A rejected key (HTTP 401/403) is an AUTHENTICATION error; a 2xx
// answer without {"valid":true} is an UNAVAILABLE error (unexpected response).
func (c *Client) Ping(ctx context.Context) (time.Duration, error) {
	const op = "ping"
	start := time.Now()
	res, err := c.api.do(ctx, &request{Method: http.MethodGet, Path: pathValidate, Header: c.apiKeyHeader(), Operation: op})
	if err != nil {
		return 0, mapDatadogError(err)
	}
	if v := parseValid(res.Body); v == nil || !*v {
		return 0, unexpectedValidate(op, res.Status)
	}
	return time.Since(start), nil
}

func unexpectedValidate(op string, status int) error {
	return &Error{Code: ErrorCodeUnavailable, Message: op + ": unexpected response from /api/v1/validate (HTTP " + strconv.Itoa(status) + ")", HTTPStatus: status}
}

// ValidateAPIKey reports whether Datadog accepts the configured API key
// (GET /api/v1/validate): true for {"valid":true}, false for HTTP 403 or
// {"valid":false}. Other failures are returned as mapped errors; a 2xx answer
// without a "valid" field is an UNAVAILABLE error (unexpected response).
func (c *Client) ValidateAPIKey(ctx context.Context) (bool, error) {
	const op = "validateApiKey"
	res, err := c.api.do(ctx, &request{Method: http.MethodGet, Path: pathValidate, Header: c.apiKeyHeader(), Operation: op})
	if err != nil {
		var e *Error
		if errors.As(err, &e) && e.HTTPStatus == http.StatusForbidden {
			return false, nil
		}
		return false, err
	}
	v := parseValid(res.Body)
	if v == nil {
		return false, unexpectedValidate(op, res.Status)
	}
	return *v, nil
}

// ServerVersion always returns an UNSUPPORTED error: Datadog is a SaaS and
// exposes no server version.
func (c *Client) ServerVersion(context.Context) (string, error) {
	return "", UnsupportedError("serverVersion", "Datadog exposes no server version")
}

// Exporter returns the LogExporter that posts batches to the logs intake.
// Most users call NewLogClient instead.
func (c *Client) Exporter() *IntakeExporter { return c.exporter }

// NewLogClient starts a batching LogClient that sends to the logs intake. The
// batch limits are capped at the intake limits (MaxBatchEntries records,
// MaxPayloadBytes bytes). Resource defaults come from the config:
// service.name ← Service, service.version ← Version, deployment.environment
// ← Env, host.name ← Hostname (opts.Resource wins). Client.Close closes it.
func (c *Client) NewLogClient(opts LogClientOptions) (*BatchingLogClient, error) {
	if opts.Batch.MaxBatchSize > MaxBatchEntries {
		opts.Batch.MaxBatchSize = MaxBatchEntries
	}
	if opts.Batch.MaxBatchBytes > MaxPayloadBytes {
		opts.Batch.MaxBatchBytes = MaxPayloadBytes
	}
	res := map[string]AttrValue{}
	for k, v := range map[string]string{
		"service.name": c.cfg.Service, "service.version": c.cfg.Version,
		"deployment.environment": c.cfg.Env, "host.name": c.cfg.Hostname,
	} {
		if v != "" {
			res[k] = v
		}
	}
	for k, v := range opts.Resource {
		res[k] = v
	}
	opts.Resource = res
	lc, err := NewBatchingLogClient(c.exporter, opts)
	if err != nil {
		return nil, err
	}
	if err := c.register(lc.Close); err != nil {
		_ = lc.Close(context.Background())
		return nil, err
	}
	return lc, nil
}

// Query returns the query client (search, aggregate, tail, indexes). It needs
// Config.AppKey: a missing application key is a CONFIG error.
func (c *Client) Query() (*LogsQuery, error) {
	if c.cfg.AppKey.IsZero() {
		return nil, ConfigError("appKey is required for the query API (GROW_DATADOG_LOGS_APP_KEY or DD_APP_KEY)")
	}
	return &LogsQuery{client: c}, nil
}

// register adds a closer run by Close; it fails with CANCELLED after Close.
func (c *Client) register(fn func(ctx context.Context) error) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return &Error{Code: ErrorCodeCancelled, Message: "client is closed"}
	}
	c.closers = append(c.closers, fn)
	return nil
}

// Close flushes and closes every LogClient made by NewLogClient, stops running
// tails and releases connections. It is idempotent and returns the first
// flush error.
func (c *Client) Close() error {
	c.once.Do(func() {
		c.mu.Lock()
		c.closed = true
		closers := c.closers
		c.closers = nil
		c.mu.Unlock()
		ctx, cancel := context.WithTimeout(context.Background(), c.cfg.TotalTimeout)
		defer cancel()
		for _, fn := range closers {
			if err := fn(ctx); err != nil && c.closeErr == nil {
				c.closeErr = err
			}
		}
		c.intake.close()
		c.api.close()
	})
	return c.closeErr
}
