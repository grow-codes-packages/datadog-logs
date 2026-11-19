// Created by sdk-kit 0.1.0; owned by this repository.
//! Conformance scenarios for `core` and `logging` against datadog/fakeintake (Docker).
//!
//! Capabilities: `query` — the logging scenarios read records back from the fakeintake raw
//! capture ([`FakeIntakeQuery`]); Datadog's search API cannot run in the container (unit-tested
//! with fixtures, exercised against wire stubs in `datadog.rs`, proven by the live job). No
//! `version`: Datadog is a SaaS without a server version (`server_version` is UNSUPPORTED).

use std::sync::Arc;

use grow_datadog_logs as sdk;
use grow_datadog_logs_integration::{conformance_dir, setup, FakeIntakeQuery};
use sdk::testing::logging::{LoggingHandler, LoggingTarget};
use sdk::testing::{ConformanceRunner, CoreHandler};
use sdk::{DatadogLogsClient as Client, LogClientOptions};

#[tokio::test]
async fn conformance() {
    if !setup() {
        return;
    }
    let logging = LoggingHandler::new(|batch| async move {
        let client = Client::from_env()?;
        let opts = LogClientOptions {
            batch,
            ..Default::default()
        }
        .resource_attribute("service.name", "grow-conformance-rust")
        .resource_attribute("deployment.environment", "conformance");
        Ok(LoggingTarget {
            client: Arc::new(client.log_client(opts)?),
            query: Some(Arc::new(FakeIntakeQuery::default())),
        })
    });
    let report = ConformanceRunner::new(conformance_dir())
        .capabilities(["query"])
        .handler(CoreHandler::new(Client::from_env))
        .handler(logging)
        .run()
        .await
        .expect("scenarios load");
    println!("{report}");
    report.assert_success();
    assert!(report.passed() >= 4, "{report}");
}
