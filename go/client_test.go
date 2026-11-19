package datadoglogs

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"testing"
	"time"
)

// newTestClient builds a client whose intake and API point at url, with fast
// retries and both keys; mutate adjusts the config.
func newTestClient(t *testing.T, url string, mutate func(*Config)) *Client {
	t.Helper()
	cfg := Config{
		CommonConfig: CommonConfig{URL: url, Retry: fastRetry(3)},
		APIKey:       NewSecret(testToken),
		AppKey:       NewSecret(testAppKey),
	}
	if mutate != nil {
		mutate(&cfg)
	}
	c, err := NewClient(cfg)
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func TestConfig(t *testing.T) {
	t.Run("core.config.env-precedence", func(t *testing.T) {
		env := map[string]string{
			"DD_API_KEY": "dd-api", "DD_APPLICATION_KEY": "dd-app-long", "DD_SITE": "datadoghq.eu",
			"DD_SERVICE": "svc", "DD_ENV": "prod", "DD_VERSION": "1.0", "DD_TAGS": "team:a, tier:b  zone:c",
			"DD_HOSTNAME": "host-1",
		}
		cfg, err := ConfigFromEnvWith(Config{}, env)
		if err != nil {
			t.Fatal(err)
		}
		if cfg.APIKey.Reveal() != "dd-api" || cfg.AppKey.Reveal() != "dd-app-long" || cfg.Site != "datadoghq.eu" ||
			cfg.Service != "svc" || cfg.Env != "prod" || cfg.Version != "1.0" || cfg.Hostname != "host-1" ||
			strings.Join(cfg.Tags, "|") != "team:a|tier:b|zone:c" {
			t.Fatalf("vendor env: %s", cfg)
		}
		ep, _ := cfg.Endpoints()
		if ep.IntakeURL != "https://http-intake.logs.datadoghq.eu" || ep.APIURL != "https://api.datadoghq.eu" {
			t.Fatalf("endpoints %+v", ep)
		}
		env["DD_APP_KEY"] = "dd-app"
		for k, v := range map[string]string{"API_KEY": "grow-api", "SITE": "us5.datadoghq.com", "SERVICE": "gsvc", "TAGS": "x:y", "SOURCE": "go-test"} {
			env[EnvPrefix+k] = v
		}
		cfg, _ = ConfigFromEnvWith(Config{}, env)
		if cfg.APIKey.Reveal() != "grow-api" || cfg.AppKey.Reveal() != "dd-app" || cfg.Site != "us5.datadoghq.com" ||
			cfg.Service != "gsvc" || strings.Join(cfg.Tags, ",") != "x:y" || cfg.Source != "go-test" {
			t.Fatalf("grow env: %s", cfg)
		}
		cfg, _ = ConfigFromEnvWith(Config{APIKey: NewSecret("explicit"), Site: "ap1.datadoghq.com", Tags: []string{}}, env)
		if cfg.APIKey.Reveal() != "explicit" || cfg.Site != "ap1.datadoghq.com" || len(cfg.Tags) != 0 {
			t.Fatal("explicit must win")
		}
		_, err = ConfigFromEnvWith(Config{}, map[string]string{"DD_API_KEY": "k", EnvPrefix + "TOTAL_TIMEOUT": "forever-xyz"})
		if !errors.Is(err, ErrConfig) || !strings.Contains(err.Error(), EnvPrefix+"TOTAL_TIMEOUT") || strings.Contains(err.Error(), "forever-xyz") {
			t.Fatalf("invalid value: %v", err)
		}
		t.Setenv("DD_API_KEY", "proc-key")
		t.Setenv(EnvPrefix+"URL", "http://process-env:9")
		if cfg, err := ConfigFromEnv(); err != nil || cfg.URL != "http://process-env:9" {
			t.Fatalf("process env: %v %s", err, cfg.URL)
		}
	})

	t.Run("endpoints", func(t *testing.T) {
		ep, err := Config{}.Endpoints()
		if err != nil || ep.IntakeURL != DefaultIntakeURL || ep.APIURL != DefaultURL {
			t.Fatalf("default %+v %v", ep, err)
		}
		ep, _ = Config{CommonConfig: CommonConfig{URL: "http://fake:80/"}, APIURL: "http://api:1/"}.Endpoints()
		if ep.IntakeURL != "http://fake:80" || ep.APIURL != "http://api:1" {
			t.Fatalf("overrides %+v", ep)
		}
		ep, err = Config{Site: "nope.example", IntakeURL: "http://i", APIURL: "http://a"}.Endpoints()
		if err != nil || ep.APIURL != "http://a" {
			t.Fatalf("both URLs skip the site check: %v", err)
		}
		ep, _ = Config{Site: " US3.DatadogHQ.com ", IntakeURL: "http://i"}.Endpoints()
		if ep.APIURL != "https://api.us3.datadoghq.com" || ep.IntakeURL != "http://i" {
			t.Fatalf("partial override %+v", ep)
		}
		for _, s := range Sites {
			if _, err := ResolveSite(s); err != nil {
				t.Errorf("%s: %v", s, err)
			}
		}
		if _, err := ResolveSite("app.datadoghq.com"); !errors.Is(err, ErrConfig) {
			t.Fatalf("UI host: %v", err)
		}
	})

	t.Run("validate", func(t *testing.T) {
		bad := []Config{
			{},
			{APIKey: NewSecret("k"), Site: "datadoghq.org"},
			{APIKey: NewSecret("k"), IntakeURL: "ftp://x"},
			{APIKey: NewSecret("k"), APIURL: "mailto:x"},
			{APIKey: NewSecret("k"), CommonConfig: CommonConfig{RequestTimeout: -1}},
		}
		for i, c := range bad {
			if err := c.Validate(); !errors.Is(err, ErrConfig) {
				t.Errorf("case %d: %v", i, err)
			}
		}
		if err := (Config{APIKey: NewSecret("k")}).Validate(); err != nil {
			t.Fatal(err)
		}
		if _, err := NewClient(Config{}); !errors.Is(err, ErrConfig) || !strings.Contains(err.Error(), "apiKey") {
			t.Fatalf("missing key: %v", err)
		}
	})

	t.Run("core.redaction.config", func(t *testing.T) {
		cfg := Config{
			CommonConfig: CommonConfig{URL: "https://h", Headers: map[string]string{"X-Api-Key": "hdr-xyz"}},
			APIKey:       NewSecret("api-xyz"), AppKey: NewSecret("app-xyz"),
			Site: "datadoghq.com", IntakeURL: "https://i", APIURL: "https://a", Service: "s", Env: "e", Version: "v",
			Hostname: "h", Source: "src", Tags: []string{"a:b"},
		}
		forms := fmt.Sprintf("%v|%+v|%#v|%s", cfg, cfg, cfg, cfg.String())
		for _, secret := range []string{"api-xyz", "app-xyz", "hdr-xyz"} {
			if strings.Contains(forms, secret) {
				t.Fatalf("leak %s: %s", secret, forms)
			}
		}
		if !strings.Contains(forms, "APIKey:"+Redacted) || !strings.Contains(forms, "Tags:a:b") || !strings.Contains(forms, "Source:src") {
			t.Fatalf("forms: %s", forms)
		}
	})

	t.Run("parse tags", func(t *testing.T) {
		if got := strings.Join(ParseTags(" a:b,,c:d\te:f\n"), "|"); got != "a:b|c:d|e:f" {
			t.Fatal(got)
		}
	})
}

func validateServer(t *testing.T, status int, body string) *testServer {
	t.Helper()
	return newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
		if r.URL.Path != pathValidate || r.Method != http.MethodGet {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		if r.Header.Get(HeaderAPIKey) != testToken || r.Header.Get(HeaderAppKey) != "" {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		writeJSON(w, status, body)
	})
}

func TestClientHealthPingValidate(t *testing.T) {
	ok := loadFixture(t, "validateApiKey.datadog_ok")
	forbidden := loadFixture(t, "validateApiKey.403_invalid_key")
	srv := newTestServer(t, routes(map[string]func(http.ResponseWriter, *http.Request, int){
		"GET " + pathValidate: func(w http.ResponseWriter, r *http.Request, n int) {
			if r.Header.Get(HeaderAPIKey) != testToken {
				forbidden.write(w)
				return
			}
			ok.write(w)
		},
	}))
	c := newTestClient(t, srv.URL, nil)
	bad := newTestClient(t, srv.URL, func(cfg *Config) { cfg.APIKey = NewSecret("wrong-key") })

	t.Run("health", func(t *testing.T) {
		hs, err := c.Health(t.Context())
		if err != nil || !hs.Healthy || hs.Status != "ok" || hs.Details["apiKeyValid"] != "true" || hs.Details["httpStatus"] != "200" {
			t.Fatalf("%+v %v", hs, err)
		}
		hs, err = bad.Health(t.Context())
		if err != nil || hs.Healthy || hs.Status != "unauthorized" || hs.Details["apiKeyValid"] != "false" {
			t.Fatalf("bad key %+v %v", hs, err)
		}
		for status, want := range map[int]string{429: "degraded", 500: "unhealthy", 404: "unhealthy"} {
			s := validateServer(t, status, `{}`)
			hs, err := newTestClient(t, s.URL, nil).Health(t.Context())
			if err != nil || hs.Healthy || hs.Status != want {
				t.Errorf("%d: %+v %v", status, hs, err)
			}
			if s.calls.Load() != 1 {
				t.Errorf("%d: health must not retry (%d calls)", status, s.calls.Load())
			}
		}
		empty := validateServer(t, 200, ``)
		if hs, err := newTestClient(t, empty.URL, nil).Health(t.Context()); err != nil || !hs.Healthy || hs.Details["apiKeyValid"] != "false" {
			t.Fatalf("empty 200: %+v %v", hs, err)
		}
	})

	t.Run("core.health.unreachable", func(t *testing.T) {
		u := newTestClient(t, "http://"+closedAddr(t), func(cfg *Config) { cfg.ConnectTimeout = time.Second })
		if hs, err := u.Health(t.Context()); err != nil || hs.Healthy || hs.Status != "unreachable" || hs.Details["error"] == "" {
			t.Fatalf("hs=%+v err=%v", hs, err)
		}
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		if _, err := c.Health(ctx); !errors.Is(err, ErrCancelled) {
			t.Fatalf("cancelled: %v", err)
		}
	})

	t.Run("ping", func(t *testing.T) {
		if d, err := c.Ping(t.Context()); err != nil || d <= 0 {
			t.Fatalf("ping %s %v", d, err)
		}
		_, err := bad.Ping(t.Context())
		e := asError(t, err)
		if e.Code != ErrorCodeAuthentication || e.HTTPStatus != 403 || strings.Contains(e.Error(), "wrong-key") {
			t.Fatalf("bad key: %+v", e)
		}
		for _, body := range []string{`{"valid":false}`, ``} {
			s := validateServer(t, 200, body)
			if _, err := newTestClient(t, s.URL, nil).Ping(t.Context()); !errors.Is(err, ErrUnavailable) || CodeOf(err) != ErrorCodeUnavailable || asError(t, err).Retryable {
				t.Fatalf("2xx %q: %v", body, err)
			}
		}
		unauth := validateServer(t, 401, `{"errors":["Unauthorized"]}`)
		if _, err := newTestClient(t, unauth.URL, nil).Ping(t.Context()); !errors.Is(err, ErrAuthentication) {
			t.Fatalf("401: %v", err)
		}
		down := validateServer(t, 503, `{"errors":["Service Unavailable"]}`)
		if _, err := newTestClient(t, down.URL, func(cfg *Config) { cfg.Retry = fastRetry(1) }).Ping(t.Context()); !errors.Is(err, ErrUnavailable) || down.calls.Load() != 2 {
			t.Fatalf("503: %v (%d calls)", err, down.calls.Load())
		}
	})

	t.Run("validateApiKey", func(t *testing.T) {
		if v, err := c.ValidateAPIKey(t.Context()); err != nil || !v {
			t.Fatalf("valid: %v %v", v, err)
		}
		if v, err := bad.ValidateAPIKey(t.Context()); err != nil || v {
			t.Fatalf("invalid: %v %v", v, err)
		}
		cases := []struct {
			status int
			body   string
			want   bool
			code   ErrorCode
		}{
			{401, `{"errors":["Unauthorized"]}`, false, ErrorCodeAuthentication},
			{200, `{"valid":false}`, false, ""},
			{200, ``, false, ErrorCodeUnavailable},
			{200, `{"other":1}`, false, ErrorCodeUnavailable},
			{400, `{"errors":["bad"]}`, false, ErrorCodeValidation},
		}
		for _, tc := range cases {
			s := validateServer(t, tc.status, tc.body)
			v, err := newTestClient(t, s.URL, nil).ValidateAPIKey(t.Context())
			if v != tc.want || CodeOf(err) != tc.code {
				t.Errorf("%d %s: %v %v", tc.status, tc.body, v, err)
			}
		}
	})

	t.Run("serverVersion", func(t *testing.T) {
		if _, err := c.ServerVersion(t.Context()); !errors.Is(err, ErrUnsupported) {
			t.Fatal(err)
		}
	})
}

func TestClientLifecycle(t *testing.T) {
	srv := validateServer(t, 200, `{"valid":true}`)

	t.Run("core.close.idempotent", func(t *testing.T) {
		c := newTestClient(t, srv.URL, nil)
		lc, err := c.NewLogClient(LogClientOptions{})
		if err != nil {
			t.Fatal(err)
		}
		first := c.Close()
		second := c.Close()
		if first != nil || second != nil {
			t.Fatal("close")
		}
		if _, err := c.Ping(t.Context()); !errors.Is(err, ErrCancelled) {
			t.Fatalf("after close: %v", err)
		}
		if _, err := c.NewLogClient(LogClientOptions{}); !errors.Is(err, ErrCancelled) {
			t.Fatalf("log client after close: %v", err)
		}
		if lc.Stats().Queued != 0 || lc.Close(t.Context()) != nil {
			t.Fatal("log client closed by Client.Close")
		}
	})

	t.Run("close returns flush error", func(t *testing.T) {
		c := newTestClient(t, srv.URL, nil)
		_, err := c.NewLogClient(LogClientOptions{})
		if err != nil {
			t.Fatal(err)
		}
		c.mu.Lock()
		c.closers = append(c.closers, func(context.Context) error { return errors.New("boom") })
		c.mu.Unlock()
		if err := c.Close(); err == nil || err.Error() != "boom" {
			t.Fatalf("close err %v", err)
		}
	})

	t.Run("from env", func(t *testing.T) {
		t.Setenv(EnvPrefix+"URL", srv.URL)
		t.Setenv(EnvPrefix+"API_KEY", testToken)
		c, err := NewClientFromEnv()
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = c.Close() }()
		if _, err := c.Ping(t.Context()); err != nil {
			t.Fatal(err)
		}
		if c.Config().APIKey.Reveal() != testToken || c.Endpoints().APIURL != srv.URL {
			t.Fatal("config/endpoints")
		}
		if _, err := c.Query(); !errors.Is(err, ErrConfig) {
			t.Fatalf("no app key: %v", err)
		}
		t.Setenv(EnvPrefix+"URL", "mailto:x")
		if _, err := NewClientFromEnv(); !errors.Is(err, ErrConfig) {
			t.Fatalf("bad env: %v", err)
		}
	})

	t.Run("plain http warning and secrets", func(t *testing.T) {
		var buf bytes.Buffer
		logger := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
		c := newTestClient(t, srv.URL, func(cfg *Config) { cfg.Logger = logger })
		if !strings.Contains(buf.String(), "plain http") {
			t.Fatalf("warning: %s", buf.String())
		}
		if got := c.api.redact("x " + testToken + " " + testAppKey); strings.Contains(got, testToken) || strings.Contains(got, testAppKey) {
			t.Fatalf("redact %s", got)
		}
	})

	t.Run("bad TLS for the API transport", func(t *testing.T) {
		_, err := NewClient(Config{APIKey: NewSecret("k"), CommonConfig: CommonConfig{TLS: TLSConfig{CACertPEM: "bad"}}})
		if !errors.Is(err, ErrConfig) {
			t.Fatal(err)
		}
	})
}
