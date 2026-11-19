package datadoglogs

import (
	"fmt"
	"slices"
	"strings"
)

// DefaultSite is the Datadog site used when none is configured (US1).
const DefaultSite = "datadoghq.com"

// DefaultSource is the ddsource sent when Config.Source is empty.
const DefaultSource = "grow-sdk"

// DefaultURL is the default API base URL (the API host of DefaultSite).
// Requests to the logs intake go to DefaultIntakeURL.
const DefaultURL = "https://api." + DefaultSite

// DefaultIntakeURL is the default logs intake base URL (the intake host of DefaultSite).
const DefaultIntakeURL = "https://http-intake.logs." + DefaultSite

// Sites lists the Datadog sites accepted by Config.Site and ResolveSite (the
// servers enum of the official OpenAPI specification).
var Sites = []string{
	"datadoghq.com",
	"us3.datadoghq.com",
	"us5.datadoghq.com",
	"ap1.datadoghq.com",
	"ap2.datadoghq.com",
	"uk1.datadoghq.com",
	"datadoghq.eu",
	"ddog-gov.com",
	"us2.ddog-gov.com",
}

// SiteEndpoints are the two base URLs of a Datadog site.
type SiteEndpoints struct {
	// IntakeURL is the logs intake base, for example https://http-intake.logs.datadoghq.com.
	IntakeURL string `json:"intakeUrl"`
	// APIURL is the API base, for example https://api.datadoghq.com.
	APIURL string `json:"apiUrl"`
}

// ResolveSite returns the intake and API base URLs of a Datadog site
// ("datadoghq.com", "datadoghq.eu", "us3.datadoghq.com", ...). The site is the
// API site, never the UI host (app.datadoghq.com). It is trimmed and
// lower-cased; an empty site means DefaultSite. Unknown sites are a CONFIG error.
func ResolveSite(site string) (SiteEndpoints, error) {
	s := strings.ToLower(strings.TrimSpace(site))
	if s == "" {
		s = DefaultSite
	}
	if !slices.Contains(Sites, s) {
		return SiteEndpoints{}, ConfigError("site is not a known Datadog site (use one of " + strings.Join(Sites, ", ") + ")")
	}
	return SiteEndpoints{IntakeURL: "https://http-intake.logs." + s, APIURL: "https://api." + s}, nil
}

// vendorEnv lists the vendor variables consulted after GROW_DATADOG_LOGS_* for
// the common fields. Datadog has no vendor variable for them.
var vendorEnv = VendorEnv{}

// Vendor variables of the Datadog specific fields, in precedence order.
var (
	vendorAPIKeyEnv   = []string{"DD_API_KEY"}
	vendorAppKeyEnv   = []string{"DD_APP_KEY", "DD_APPLICATION_KEY"}
	vendorSiteEnv     = []string{"DD_SITE"}
	vendorServiceEnv  = []string{"DD_SERVICE"}
	vendorEnvEnv      = []string{"DD_ENV"}
	vendorVersionEnv  = []string{"DD_VERSION"}
	vendorTagsEnv     = []string{"DD_TAGS"}
	vendorHostnameEnv = []string{"DD_HOSTNAME"}
)

// Config configures a Client. Build it with a struct literal or ConfigFromEnv;
// zero values mean "use the default". Call Validate to check it.
//
// Endpoints: requests go to two hosts derived from Site (the logs intake and
// the API). CommonConfig.URL sets both at once (tests, proxies, fakeintake);
// IntakeURL and APIURL override one each.
type Config struct {
	CommonConfig

	// APIKey authenticates every request (header DD-API-KEY; env
	// GROW_DATADOG_LOGS_API_KEY, then DD_API_KEY). Required.
	APIKey Secret
	// AppKey is the application key needed by the query API only (header
	// DD-APPLICATION-KEY; env GROW_DATADOG_LOGS_APP_KEY, then DD_APP_KEY,
	// DD_APPLICATION_KEY). It is never sent to the intake.
	AppKey Secret
	// Site is the Datadog site (default "datadoghq.com"; env
	// GROW_DATADOG_LOGS_SITE, then DD_SITE). See Sites.
	Site string
	// IntakeURL overrides the logs intake base URL (env GROW_DATADOG_LOGS_INTAKE_URL).
	IntakeURL string
	// APIURL overrides the API base URL (env GROW_DATADOG_LOGS_API_URL).
	APIURL string
	// Service is the reserved "service" attribute when a record has no
	// service.name resource (env GROW_DATADOG_LOGS_SERVICE, then DD_SERVICE).
	Service string
	// Env adds the tag env:<Env> when a record has no deployment.environment
	// resource (env GROW_DATADOG_LOGS_ENV, then DD_ENV).
	Env string
	// Version adds the tag version:<Version> when a record has no
	// service.version resource (env GROW_DATADOG_LOGS_VERSION, then DD_VERSION).
	Version string
	// Tags are added to every record's ddtags, as key:value strings (env
	// GROW_DATADOG_LOGS_TAGS, then DD_TAGS: separated by commas or spaces).
	Tags []string
	// Hostname is the reserved "hostname" attribute when a record has no
	// host.name resource (env GROW_DATADOG_LOGS_HOSTNAME, then DD_HOSTNAME).
	Hostname string
	// Source is the reserved "ddsource" attribute that selects Datadog
	// integration pipelines (default "grow-sdk"; env GROW_DATADOG_LOGS_SOURCE).
	Source string
}

// ConfigFromEnv reads the configuration from the process environment.
func ConfigFromEnv() (Config, error) { return ConfigFromEnvWith(Config{}, nil) }

// ConfigFromEnvWith resolves explicit values > GROW_DATADOG_LOGS_* > vendor
// variables (DD_API_KEY, DD_APP_KEY, DD_APPLICATION_KEY, DD_SITE, DD_SERVICE,
// DD_ENV, DD_VERSION, DD_TAGS, DD_HOSTNAME) > defaults. env replaces the
// process environment when non-nil. The result is validated.
func ConfigFromEnvWith(explicit Config, env map[string]string) (Config, error) {
	r := NewEnvReader(EnvPrefix, env)
	c := explicit
	if err := c.applyEnv(r, vendorEnv); err != nil {
		return Config{}, err
	}
	setSecret := func(dst *Secret, name string, vendor []string) {
		if dst.IsZero() {
			if v, ok := r.String(name, vendor...); ok {
				*dst = NewSecret(v)
			}
		}
	}
	setSecret(&c.APIKey, "API_KEY", vendorAPIKeyEnv)
	setSecret(&c.AppKey, "APP_KEY", vendorAppKeyEnv)
	setString := func(dst *string, name string, vendor []string) {
		if *dst == "" {
			if v, ok := r.String(name, vendor...); ok {
				*dst = strings.TrimSpace(v)
			}
		}
	}
	setString(&c.Site, "SITE", vendorSiteEnv)
	setString(&c.IntakeURL, "INTAKE_URL", nil)
	setString(&c.APIURL, "API_URL", nil)
	setString(&c.Service, "SERVICE", vendorServiceEnv)
	setString(&c.Env, "ENV", vendorEnvEnv)
	setString(&c.Version, "VERSION", vendorVersionEnv)
	setString(&c.Hostname, "HOSTNAME", vendorHostnameEnv)
	setString(&c.Source, "SOURCE", nil)
	if c.Tags == nil {
		if v, ok := r.String("TAGS", vendorTagsEnv...); ok {
			c.Tags = ParseTags(v)
		}
	}
	if err := c.Validate(); err != nil {
		return Config{}, err
	}
	return c, nil
}

// ParseTags splits a DD_TAGS style list: key:value tags separated by commas
// and/or whitespace. Empty items are dropped.
func ParseTags(s string) []string {
	fields := strings.FieldsFunc(s, func(r rune) bool {
		return r == ',' || r == ' ' || r == '\t' || r == '\n' || r == '\r'
	})
	out := make([]string, 0, len(fields))
	for _, f := range fields {
		if f = strings.TrimSpace(f); f != "" {
			out = append(out, f)
		}
	}
	return out
}

// Endpoints returns the effective intake and API base URLs: IntakeURL /
// APIURL, else CommonConfig.URL, else the hosts of Site. Trailing slashes are
// removed. An unknown site is a CONFIG error unless both URLs are set.
func (c Config) Endpoints() (SiteEndpoints, error) {
	ep := SiteEndpoints{IntakeURL: firstNonEmpty(c.IntakeURL, c.URL), APIURL: firstNonEmpty(c.APIURL, c.URL)}
	if ep.IntakeURL == "" || ep.APIURL == "" {
		site, err := ResolveSite(c.Site)
		if err != nil {
			return SiteEndpoints{}, err
		}
		if ep.IntakeURL == "" {
			ep.IntakeURL = site.IntakeURL
		}
		if ep.APIURL == "" {
			ep.APIURL = site.APIURL
		}
	}
	ep.IntakeURL = strings.TrimRight(strings.TrimSpace(ep.IntakeURL), "/")
	ep.APIURL = strings.TrimRight(strings.TrimSpace(ep.APIURL), "/")
	return ep, nil
}

// common returns the defaulted common config for one base URL.
func (c Config) common(baseURL string) CommonConfig {
	cc := c.CommonConfig
	cc.URL = baseURL
	return cc.withDefaults(baseURL)
}

// Validate checks the configuration and returns a CONFIG error: a known site
// (or both URLs), http/https URLs, an API key, positive timeouts and the other
// common rules.
func (c Config) Validate() error {
	ep, err := c.Endpoints()
	if err != nil {
		return err
	}
	if err := c.common(ep.IntakeURL).validate(); err != nil {
		return ConfigError("intake " + strings.TrimPrefix(err.Error(), "config: "))
	}
	if err := c.common(ep.APIURL).validate(); err != nil {
		return err
	}
	if c.APIKey.IsZero() {
		return ConfigError("apiKey is required (GROW_DATADOG_LOGS_API_KEY or DD_API_KEY)")
	}
	return nil
}

// source returns Source or DefaultSource.
func (c Config) source() string { return firstNonEmpty(c.Source, DefaultSource) }

// String renders the configuration with every credential redacted.
func (c Config) String() string {
	var b strings.Builder
	fmt.Fprintf(&b, "Config{%s", c.describe())
	for _, f := range []struct{ name, value string }{
		{"Site", c.Site}, {"IntakeURL", RedactURL(c.IntakeURL)}, {"APIURL", RedactURL(c.APIURL)},
		{"Service", c.Service}, {"Env", c.Env}, {"Version", c.Version}, {"Hostname", c.Hostname}, {"Source", c.Source},
	} {
		if f.value != "" {
			fmt.Fprintf(&b, " %s:%s", f.name, f.value)
		}
	}
	if len(c.Tags) > 0 {
		fmt.Fprintf(&b, " Tags:%s", strings.Join(c.Tags, ","))
	}
	fmt.Fprintf(&b, " APIKey:%s AppKey:%s}", c.APIKey, c.AppKey)
	return b.String()
}

// GoString renders the configuration with every credential redacted.
func (c Config) GoString() string { return c.String() }

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}
