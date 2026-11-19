package datadoglogs

import (
	"errors"
	"net/http"
)

// asAuthentication turns a 403 *Error into an AUTHENTICATION error with the
// same message and status (Datadog answers 403 for missing or invalid keys).
func asAuthentication(err error) error {
	var e *Error
	if !errors.As(err, &e) || e.HTTPStatus != http.StatusForbidden {
		return err
	}
	out := *e
	out.Code = ErrorCodeAuthentication
	out.Retryable = false
	return &out
}

// mapDatadogError applies the Datadog mapping (DECISIONS.md #3): a 403 from
// the intake, /api/v1/validate or the query API means a missing or invalid
// key (or, on the query API, a key without the needed permission, which
// Datadog does not tell apart) → AUTHENTICATION, keeping httpStatus 403.
func mapDatadogError(err error) error { return asAuthentication(err) }

// rateLimitHint copies X-RateLimit-Reset (seconds until the window resets)
// into Retry-After on 429 and 503 responses that carry no Retry-After, so the
// transport honours it like a Retry-After (DECISIONS.md #5).
type rateLimitHint struct {
	next http.RoundTripper
}

// RoundTrip implements http.RoundTripper.
func (r rateLimitHint) RoundTrip(req *http.Request) (*http.Response, error) {
	resp, err := r.next.RoundTrip(req)
	if err != nil {
		return resp, err
	}
	limited := resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode == http.StatusServiceUnavailable
	if limited && resp.Header.Get("Retry-After") == "" {
		if reset := resp.Header.Get("X-RateLimit-Reset"); reset != "" {
			resp.Header.Set("Retry-After", reset)
		}
	}
	return resp, nil
}
