package datadoglogs

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// Datadog logs intake limits (https://docs.datadoghq.com/api/latest/logs/#send-logs).
const (
	// MaxBatchEntries is the maximum number of logs in one intake request.
	MaxBatchEntries = 1000
	// MaxPayloadBytes is the maximum uncompressed size of one intake request
	// (the JSON array including brackets and commas).
	MaxPayloadBytes = 5_000_000
	// MaxLogBytes is the maximum serialized size of one log. Larger logs get
	// their message truncated and TruncatedAttribute set.
	MaxLogBytes = 1_000_000
)

// TruncatedAttribute is set to true on a log whose message the SDK truncated
// to fit MaxLogBytes.
const TruncatedAttribute = "grow.truncated"

// ReservedAttributePrefix is put in front of a user attribute whose name is a
// reserved Datadog attribute (see ReservedAttributes), for example
// "service" → "attr.service".
const ReservedAttributePrefix = "attr."

// ReservedAttributes are the top-level keys the SDK sets on every Datadog log.
var ReservedAttributes = []string{
	"message", "timestamp", "status", "service", "hostname", "ddsource", "ddtags",
	"dd.trace_id", "dd.span_id", "otel.trace_id", "otel.span_id", TruncatedAttribute,
}

// IntakeExporter is the LogExporter of the Datadog logs intake
// (POST {intake}/api/v2/logs). Each Export splits the records into requests of
// at most MaxBatchEntries logs and MaxPayloadBytes bytes, truncates logs larger
// than MaxLogBytes and sends a gzip-compressed JSON array when asked.
type IntakeExporter struct {
	client *Client
}

var _ LogExporter = (*IntakeExporter)(nil)

// Export sends records to the intake. Accepted counts the records of the
// requests Datadog acknowledged (2xx; acceptance means "queued", not
// "indexed"). A record that cannot be made small enough is rejected with a
// VALIDATION error. When a later request fails after earlier ones succeeded,
// the partial result is returned with a nil error and ExportResult.Error set,
// so the batch is not resent.
func (e *IntakeExporter) Export(ctx context.Context, records []LogRecord, opts ExportOptions) (ExportResult, error) {
	var res ExportResult
	if len(records) == 0 {
		return res, nil
	}
	encoded := make([][]byte, 0, len(records))
	for _, rec := range records {
		b, ok := e.encode(rec)
		if !ok {
			res.Rejected++
			continue
		}
		encoded = append(encoded, b)
	}
	if res.Rejected > 0 {
		res.Error = ValidationError("logs.send", fmt.Sprintf("%d record(s) exceed %d bytes even with an empty message", res.Rejected, MaxLogBytes))
	}
	for _, chunk := range splitPayloads(encoded) {
		if err := e.post(ctx, chunk, opts.Gzip); err != nil {
			if res.Accepted == 0 {
				return ExportResult{Rejected: len(records)}, err
			}
			var sdkErr *Error
			_ = errors.As(err, &sdkErr) // the transport only returns *Error
			res.Rejected = len(records) - res.Accepted
			res.Error = sdkErr
			return res, nil
		}
		res.Accepted += len(chunk)
	}
	return res, nil
}

// Shutdown implements LogExporter; the connections are released by Client.Close.
func (e *IntakeExporter) Shutdown(context.Context) error { return nil }

// post sends one JSON array.
func (e *IntakeExporter) post(ctx context.Context, chunk [][]byte, gz bool) error {
	body := make([]byte, 0, payloadSize(chunk))
	body = append(body, '[')
	body = append(body, bytes.Join(chunk, []byte{','})...)
	body = append(body, ']')
	h := e.client.apiKeyHeader()
	if gz {
		var buf bytes.Buffer
		w := gzip.NewWriter(&buf)
		_, _ = w.Write(body) // writes to a bytes.Buffer cannot fail
		_ = w.Close()
		body = buf.Bytes()
		h.Set("Content-Encoding", "gzip")
	}
	_, err := e.client.intake.do(ctx, &request{
		Method: http.MethodPost, Path: pathIntake, Header: h, Body: body, ContentType: "application/json",
		Idempotent: true, Operation: "logs.send",
	})
	return mapDatadogError(err)
}

// payloadSize is the size of the JSON array of chunk.
func payloadSize(chunk [][]byte) int {
	n := 2
	for i, b := range chunk {
		if i > 0 {
			n++
		}
		n += len(b)
	}
	return n
}

// splitPayloads groups encoded logs into requests within the intake limits.
func splitPayloads(encoded [][]byte) [][][]byte {
	var out [][][]byte
	var cur [][]byte
	size := 2
	for _, b := range encoded {
		extra := len(b)
		if len(cur) > 0 {
			extra++
		}
		if len(cur) > 0 && (len(cur) >= MaxBatchEntries || size+extra > MaxPayloadBytes) {
			out = append(out, cur)
			cur, size, extra = nil, 2, len(b)
		}
		cur = append(cur, b)
		size += extra
	}
	if len(cur) > 0 {
		out = append(out, cur)
	}
	return out
}

// encode serializes one record as a Datadog log, truncating the message when
// the log exceeds MaxLogBytes. ok is false when it cannot fit.
func (e *IntakeExporter) encode(rec LogRecord) ([]byte, bool) {
	m := e.client.datadogLog(rec)
	b := marshalCompact(m)
	if len(b) <= MaxLogBytes {
		return b, true
	}
	msg, _ := m["message"].(string)
	m[TruncatedAttribute] = true
	m["message"] = ""
	overhead := len(marshalCompact(m))
	if overhead > MaxLogBytes {
		return nil, false
	}
	budget := MaxLogBytes - overhead
	for {
		m["message"] = truncateJSONString(msg, budget)
		b = marshalCompact(m)
		if len(b) <= MaxLogBytes {
			return b, true
		}
		budget -= len(b) - MaxLogBytes // defensive: escape rules differ from the estimate
	}
}

// datadogLog returns the Datadog JSON object the intake receives for rec
// (before size limits): message, timestamp (epoch ms), status, service,
// hostname, ddsource, ddtags, dd.trace_id / dd.span_id (decimal of the lower
// 64 bits), otel.trace_id / otel.span_id (hex) and the attributes, with
// reserved names prefixed by ReservedAttributePrefix.
func (c *Client) datadogLog(rec LogRecord) map[string]any {
	m := make(map[string]any, len(rec.Attributes)+12)
	for k, v := range NormalizeAttributes(rec.Attributes) {
		if slices.Contains(ReservedAttributes, k) {
			k = ReservedAttributePrefix + k
		}
		m[k] = v
	}
	ts := rec.Timestamp
	if ts.IsZero() {
		ts = time.Now()
	}
	m["message"] = rec.Body
	m["timestamp"] = ts.UnixMilli()
	m["status"] = datadogStatus(rec)
	m["ddsource"] = c.cfg.source()
	res := rec.Resource
	if s := firstNonEmpty(resourceString(res, "service.name"), c.cfg.Service); s != "" {
		m["service"] = s
	}
	if h := firstNonEmpty(resourceString(res, "host.name"), c.cfg.Hostname); h != "" {
		m["hostname"] = h
	}
	if tags := c.ddtags(res); tags != "" {
		m["ddtags"] = tags
	}
	if id, ok := lower64Decimal(rec.TraceID, 32); ok {
		m["dd.trace_id"] = id
		m["otel.trace_id"] = strings.ToLower(rec.TraceID)
		if sid, ok := lower64Decimal(rec.SpanID, 16); ok {
			m["dd.span_id"] = sid
			m["otel.span_id"] = strings.ToLower(rec.SpanID)
		}
	}
	return m
}

// ddtags joins env, version and the configured tags (de-duplicated, stable order).
func (c *Client) ddtags(res map[string]AttrValue) string {
	var tags []string
	add := func(t string) {
		if t != "" && !slices.Contains(tags, t) {
			tags = append(tags, t)
		}
	}
	if env := firstNonEmpty(resourceString(res, "deployment.environment"), c.cfg.Env); env != "" {
		add("env:" + env)
	}
	if v := firstNonEmpty(resourceString(res, "service.version"), c.cfg.Version); v != "" {
		add("version:" + v)
	}
	for _, t := range c.cfg.Tags {
		add(strings.TrimSpace(t))
	}
	return strings.Join(tags, ",")
}

func resourceString(res map[string]AttrValue, key string) string {
	switch v := res[key].(type) {
	case nil:
		return ""
	case string:
		return v
	default:
		return fmt.Sprint(v)
	}
}

// datadogStatus is the Datadog status of rec: a vendor severity text
// lower-cased, else the name of the severity range (trace … fatal).
func datadogStatus(rec LogRecord) string {
	if t := strings.TrimSpace(rec.SeverityText); t != "" && !strings.EqualFold(t, rec.Severity.String()) {
		return strings.ToLower(t)
	}
	if rec.Severity == SeverityUnspecified {
		return "info"
	}
	return strings.ToLower(Severity(wireSeverity(rec.Severity)).String())
}

// lower64Decimal returns the decimal form of the lower 64 bits of a hex id of
// the given length (Datadog's dd.trace_id / dd.span_id convention).
func lower64Decimal(id string, length int) (string, bool) {
	if len(id) != length {
		return "", false
	}
	if _, err := hex.DecodeString(id); err != nil {
		return "", false
	}
	n, err := strconv.ParseUint(id[len(id)-16:], 16, 64)
	if err != nil {
		return "", false
	}
	return strconv.FormatUint(n, 10), true
}

// marshalCompact encodes v as compact JSON without HTML escaping.
func marshalCompact(v any) []byte {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		// Normalized attribute values always encode; keep the record readable anyway.
		b, _ := json.Marshal(fmt.Sprint(v))
		return b
	}
	return bytes.TrimSuffix(buf.Bytes(), []byte{'\n'})
}

// truncateJSONString returns the longest rune-boundary prefix of s whose JSON
// string content (without quotes) fits budget bytes.
func truncateJSONString(s string, budget int) string {
	used := 0
	for i := 0; i < len(s); {
		r, size := utf8.DecodeRuneInString(s[i:])
		n := jsonRuneLen(r, size)
		if used+n > budget {
			return s[:i]
		}
		used += n
		i += size
	}
	return s
}

// jsonRuneLen is the encoded size of one rune in an encoding/json string
// (HTML escaping off).
func jsonRuneLen(r rune, size int) int {
	switch {
	case r == utf8.RuneError && size == 1:
		return 6 // �
	case r == '"' || r == '\\' || r == '\b' || r == '\f' || r == '\n' || r == '\r' || r == '\t':
		return 2
	case r < 0x20 || r == ' ' || r == ' ':
		return 6
	}
	return size
}
