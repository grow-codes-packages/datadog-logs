module github.com/grow-codes-packages/datadog-logs/go

go 1.26.0

// Dependencies are added per category by sdk-kit; "make deps" runs "go mod tidy"
// to complete the require list and write go.sum.

require go.opentelemetry.io/otel v1.47.0 // indirect

require go.opentelemetry.io/otel/trace v1.47.0

require go.uber.org/zap v1.28.0

require (
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	go.uber.org/multierr v1.10.0 // indirect
)
