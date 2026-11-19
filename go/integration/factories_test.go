package integration

import (
	"context"
	"strings"
	"testing"

	datadoglogs "github.com/grow-codes-packages/datadog-logs/go"
	"github.com/grow-codes-packages/datadog-logs/go/testkit"
)

// capabilities the deployment under test offers (see conformance "requires").
//
//   - "query": the logging scenarios read records back from the fakeintake raw
//     capture (fakeintakeQuery, see fakeintake_test.go): what the SDK sent is
//     decoded from the container and matched by message. Datadog's own search
//     API cannot run in the container; it is unit-tested with fixtures,
//     exercised here against wire stubs and proven by the live sandbox job.
//   - no "version": Datadog is a SaaS and exposes no server version
//     (ServerVersion returns UNSUPPORTED, asserted in datadog_test.go).
//   - no "tail", "labels", "streams": the conformance suite has no scenario
//     for them; Tail / ListStreams run against wire stubs in datadog_test.go,
//     ListLabels is UNSUPPORTED.
var capabilities = []string{"query"}

// categories runs "core" plus this service's categories.
func categories() []string {
	return append([]string{"core"}, strings.Split("logging", ",")...)
}

// factories builds the conformance targets against fakeintake: core →
// *datadoglogs.Client; logging → a batching LogClient over the intake plus a
// LogQueryClient that reads the fakeintake capture.
func factories(t *testing.T) testkit.Factories {
	t.Helper()
	return testkit.Factories{
		"core": testkit.CoreFactory(func(context.Context) (testkit.CoreTarget, error) {
			return datadoglogs.NewClientFromEnv()
		}),
		"logging": func(ctx context.Context, env *testkit.Env) (any, error) {
			c, err := datadoglogs.NewClientFromEnv()
			if err != nil {
				return nil, err
			}
			env.AddCleanup(func() { _ = c.Close() })
			fi := newFakeIntake(t)
			return testkit.LoggingFactory(func(_ context.Context, opts datadoglogs.BatchOptions) (testkit.LoggingTarget, error) {
				lc, err := c.NewLogClient(datadoglogs.LogClientOptions{
					Batch:    opts,
					Resource: map[string]datadoglogs.AttrValue{"service.name": "grow-conformance-go", "deployment.environment": "conformance"},
				})
				if err != nil {
					return testkit.LoggingTarget{}, err
				}
				return testkit.LoggingTarget{Client: lc, Query: fakeintakeQuery{fi: fi}}, nil
			})(ctx, env)
		},
	}
}
