# Token Pilot demo runbook (English)

This demo uses a network-free fake `ChatModel` that is enabled only in the
`demo` Spring profile. It repeatedly exercises context admission, atomic
reservation, idempotency, release, and actual reconciliation without separate
credentials or external calls.

## 1. Start the application

Run the following from the repository root:

```bash
./gradlew --no-daemon :token-pilot-sample-app:bootRun \
  --args='--spring.profiles.active=demo'
```

The application listens on `http://localhost:8080`. No API key is required.

## 2. Run the scenarios

Show the demo status and scenario index first:

```bash
curl -s http://localhost:8080/test/token-pilot/demo | jq
```

Run all eight scenarios and print the key verification fields:

```bash
curl -s http://localhost:8080/test/token-pilot/demo/run | jq '{
  runId,
  scenarios: [.scenarios[] | {
    scenario,
    status,
    providerInvocations: .evidence.providerInvocations,
    admittedRequests: .evidence.admittedRequests,
    blockedRequests: .evidence.blockedRequests,
    accountingState: .evidence.accountingState,
    budget: .evidence.budget
  }],
  metrics
}'
```

Expected results:

| Scenario | Expected result |
| --- | --- |
| `context-fit` | `PASS`, admission `FITS`, provider invocations `0` |
| `context-block` | `PASS`, admission `EXCEEDS`, provider invocations `0` |
| `budget-concurrency` | `PASS`, one admitted request, seven blocked requests, one provider invocation |
| `idempotency` | `PASS`, the repeated key is reused and the provider is called once |
| `release` | `PASS`, the reservation is released before dispatch |
| `reconciliation-success` | `PASS`, accounting state `COMMITTED` |
| `reconciliation-failure` | `PASS`, accounting state `RECONCILIATION_REQUIRED` |
| `reconciliation-unknown` | `PASS`, accounting state `RECONCILIATION_REQUIRED` |

Run an individual scenario when recording a focused result:

```bash
curl -s http://localhost:8080/test/token-pilot/demo/context-block | jq
curl -s http://localhost:8080/test/token-pilot/demo/budget-concurrency | jq
curl -s http://localhost:8080/test/token-pilot/demo/idempotency | jq
curl -s http://localhost:8080/test/token-pilot/demo/reconciliation-failure | jq
```

## 3. Start Prometheus and Grafana

Keep the application running on the host and start only the monitoring
containers:

```bash
docker compose -f token-pilot-sample-app/docker-compose.yml up --build -d
```

Open these addresses:

- Prometheus targets: `http://localhost:9090/targets` — `token-pilot-backend` should be `UP`
- Prometheus query UI: `http://localhost:9090/graph`
- Grafana: `http://localhost:3000` — select the `Token Pilot Demo` dashboard

Prometheus scrapes `host.docker.internal:8080/actuator/prometheus`. Run the demo
endpoint, wait about ten seconds, and then inspect the `tokenpilot_*` values in
Grafana or directly from the endpoint:

```bash
curl -s http://localhost:8080/actuator/prometheus \
  | rg '^tokenpilot_(preflight|budget|cost|reconciliation)'
```

Stop the monitoring containers when finished:

```bash
docker compose -f token-pilot-sample-app/docker-compose.yml down
```

## 4. Run the OpenAI provider smoke scenario

Stop the `demo` application first with `Ctrl+C`, then start the real OpenAI
provider path. This path uses external network access and an API key; provider
charges may apply. Never put the key in a source file or commit it.

The default smoke model is the Token Pilot catalog entry
`gpt-4o-mini-2024-07-18`. If you choose another model, update the Token Pilot
catalog and pricing configuration to match it.

```bash
export OPENAI_API_KEY='your OpenAI API key'
export OPENAI_MODEL='gpt-4o-mini-2024-07-18'

./gradlew --no-daemon :token-pilot-sample-app:bootRun \
  --args='--spring.profiles.active=openai-smoke'
```

In a second terminal, send one short request:

```bash
curl -sS --get http://localhost:8080/test/token-pilot/openai-smoke \
  --data-urlencode 'prompt=Reply with one short sentence confirming the provider is reachable.' \
  | jq
```

A successful response reports `status=PASS`, a `usageSource` other than
`UNAVAILABLE`, and `accountingState=COMMITTED`. It also includes normalized
provider usage and the resulting accounting cost.

Inspect the resulting meters:

```bash
curl -sS http://localhost:8080/actuator/prometheus \
  | rg '^tokenpilot_(preflight|budget|cost|reconciliation)'
```

The automated smoke test is opt-in and requires both conditions below. A normal
`./gradlew test` run does not make a provider call.

```bash
RUN_OPENAI_SMOKE=true \
  ./gradlew --no-daemon :token-pilot-sample-app:test \
  --tests io.tokenpilot.sample.OpenAiSmokeE2ETest
```

To run both paths for a recording, execute `/test/token-pilot/demo/run` with the
`demo` profile, stop the application, then restart it with `openai-smoke` and
call the smoke endpoint. Both profiles use port `8080`, so do not run them at
the same time.

## 5. Reset the demo state

Restarting `bootRun` resets the in-memory budget store and Micrometer counters.


## 6. Observe Cloud API E2E Integration and Fault Injection Verification

This section guides you through verifying the asynchronous usage ingestion pipeline (single success, pre-call blocked, reconciliation required, and batch partial failure) and non-blocking fault isolation between the sample application and the central ingestion backend (`token-pilot-cloud-api`).

### 6.1. Prerequisites (Start MySQL Container and Backend Ingestion Server)

1. Start a local MySQL container for testing (port `3307`):

```bash
docker run --name token-mysql-test -p 3307:3306 \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=token_ledger \
  -d mysql:8.0
```

2. Because the central ingestion API server defaults to port `8080`, start it on port `8081` in a separate terminal to avoid port conflicts with the sample application (`8080`):

```bash
cd token-pilot-cloud-api
export JWT_SECRET="local_test_jwt_secret_key_1234567890_abcdefg"

./gradlew bootRun --args='--server.port=8081 --spring.datasource.url=jdbc:mysql://127.0.0.1:3307/token_ledger --spring.datasource.username=root --spring.datasource.password=root --spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver --spring.jpa.hibernate.ddl-auto=none'
```

3. Once the server starts and Flyway schema migrations complete, register a test organization, project, and project API key (plaintext key: `password`):

```bash
docker exec token-mysql-test mysql -uroot -proot token_ledger -e "
INSERT IGNORE INTO organizations (id, name, plan, default_currency) VALUES ('org-demo-1111', 'Demo Org', 'FREE', 'USD');
INSERT IGNORE INTO projects (id, organization_id, project_key, name, status, default_model) VALUES ('proj-demo-1111', 'org-demo-1111', 'demo-project', 'Demo Project', 'ACTIVE', 'gpt-4o-mini');
DELETE FROM project_api_keys WHERE id='key-demo-1111';
INSERT INTO project_api_keys (id, organization_id, project_id, environment, name, key_prefix, key_hash, status) VALUES ('key-demo-1111', 'org-demo-1111', 'proj-demo-1111', 'demo', 'demo-key', 'password', '\$2a\$10\$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'ACTIVE');
"
```

### 6.2. E2E Integration and Normal Ingestion Verification

1. Start the sample application on port `8080` with the `demo` profile and explicitly enable Observe ingestion (`--token-pilot.observe.enabled=true`, which is `false` by default):

```bash
./gradlew --no-daemon :token-pilot-sample-app:bootRun \
  --args='--spring.profiles.active=demo --token-pilot.observe.enabled=true'
```

2. Trigger the demo scenarios from another terminal:

```bash
curl -s http://localhost:8080/test/token-pilot/demo/run | jq
```

3. What to verify:
    - Confirm that the sample application terminal logs `[Observe 전송 성공] Status: 201` for both `single` (committed usage) and `non_success` (pre-call blocked and reconciliation required flows).
    - Confirm that the batch partial failure flow logs `[Observe 전송 성공] (batch) Status: 200` with `acceptedCount: 1, rejectedCount: 1`.
    - Confirm that records are inserted into the `usage_events` table in the backend (`8081`).

### 6.3. Fault Injection Test (Non-Blocking and API Key Failure Verification)

Verify that the main control flow of the sample application is never blocked when the ingestion server is down or when the project API key is invalid or revoked.

1. Stop the backend server running on port `8081` (`Ctrl + C`).
2. Run the demo scenarios again against the sample application:

```bash
curl -s http://localhost:8080/test/token-pilot/demo/run | jq
```

3. What to verify:
    - All eight demo scenarios still return `PASS` without errors even though the ingestion server is offline.
    - The sample application logs `[Observe 통신 실패 - Non-blocking 격리 작동] ... java.net.ConnectException` warnings, while keeping the application healthy and responsive.
    - Confirm that `metrics.observeIngestionFailure` in the JSON response or `tokenpilot_observe_ingestion_failure_total` on `/actuator/prometheus` increments:

```bash
curl -s http://localhost:8080/actuator/prometheus \
  | rg '^tokenpilot_observe_ingestion'
```