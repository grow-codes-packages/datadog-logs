module github.com/grow-codes-packages/datadog-logs/examples/go

go 1.26.0

require github.com/grow-codes-packages/datadog-logs/go v0.0.0

require (
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	go.opentelemetry.io/otel v1.47.0 // indirect
	go.opentelemetry.io/otel/trace v1.47.0 // indirect
)

replace github.com/grow-codes-packages/datadog-logs/go => ../../go
