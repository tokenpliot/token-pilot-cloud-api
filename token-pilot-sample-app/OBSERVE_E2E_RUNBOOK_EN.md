# Observe E2E runbook (issue #13)

Korean version: [`OBSERVE_E2E_RUNBOOK.md`](OBSERVE_E2E_RUNBOOK.md)

Steps to verify the non-blocking Observe pilot end to end: sample app → Java Client SDK →
Cloud API `POST /api/v2/observe/events` → central ledger → ledger query API.

- Covered: real usage delivery, central ledger amount reconciliation, provider results unchanged during server outage
  and API key revocation, SDK retry
- The provider is a network-free fake `ChatModel` (`DemoChatModel`, 8/3 tokens per call), so no provider API key is needed.
- Token Pilot library feature demos (context admission, budget reservation, idempotency, ...) are out of scope.
  See `token-pilot-sample-app/DEMO_RUNBOOK.md` in the tokenpilot repository.

Commands are written for bash. On Windows, use Git Bash.

## 1. Prerequisites

### 1.1. Tools

- JDK 25, Docker, `curl`, `openssl` (bundled with Git Bash)
- `jq` is optional for pretty JSON; the commands below work without it.

### 1.2. Install unpublished dependencies locally

`token-pilot-starter` and `token-pilot-client-tokenpilot` are not published to a remote repository.
Install both into the local Maven repository (`~/.m2`) before building the sample app.

```bash
cd <tokenpilot repository>        && ./gradlew publishToMavenLocal
cd <token-pilot-client repository> && ./gradlew publishToMavenLocal
```

### 1.3. Build layout

The sample app is a standalone Gradle build (its own `settings.gradle`) and is not part of the server build.
It borrows the server repository's Gradle wrapper via `-p .`, so run sample app commands from `token-pilot-sample-app`.

### 1.4. Profiles

| Profile | Budget | Observe delivery | Purpose |
|---|---|---|---|
| `observe` | off | yes | **E2E verification in this document** |
| `demo` | on | **no** (see section 5) | Token Pilot feature demo |

In both profiles the SDK is enabled only with `--token-pilot.client.endpoint=...` (off by default).

## 2. Automated tests (no server or DB needed)

```bash
cd token-pilot-cloud-api/token-pilot-sample-app
../gradlew -p . --no-daemon test
```

| Test | What it verifies |
|---|---|
| `ObserveProfileE2ETest.ObserveProfile` | boots the real `observe` profile → 3 calls → 3 events with real usage (8/3) at a fake ingestion server, no prompt text, local cost total; requested model forwarded (missing-price check), unlisted models rejected |
| `ObserveProfileE2ETest.DemoProfileKnownGap` | the `demo` profile (budget on) currently forwards nothing (pins the section 5 limitation; fails once the SDK is fixed, then update it) |
| `ObservePilotE2ETest` | SDK auto-configuration: v2 path, only listed tags, unpriced/model-mismatch metadata, retry after 503, 401 (revoked key) dropped without retry, provider unaffected when the server is down |
| `DemoScenarioE2ETest` | `demo` profile HTTP contract |

## 3. Real-server E2E

Secrets live only in shell environment variables. Run section 3 commands in **a terminal with the 3.1 variables exported**
(if the server and sample app run in other terminals, export the same values there too).

### 3.1. Environment variables

```bash
export DB_PASSWORD="$(openssl rand -hex 16)"         # local MySQL root password
export JWT_SECRET="$(openssl rand -hex 32)"          # server JWT signing key (also signs the ledger query token)
export TP_API_KEY="tpk_demo_$(openssl rand -hex 16)" # project API key (used by the SDK)
```

### 3.2. MySQL

```bash
docker run --name token-mysql-e2e -p 3308:3306 \
  -e MYSQL_ROOT_PASSWORD="$DB_PASSWORD" \
  -e MYSQL_DATABASE=token_ledger \
  -d mysql:8.0
```

### 3.3. Cloud API server (8081)

```bash
cd token-pilot-cloud-api

./gradlew --no-daemon bootRun --args="--server.port=8081 \
  --spring.datasource.url=jdbc:mysql://127.0.0.1:3308/token_ledger \
  --spring.datasource.username=root \
  --spring.datasource.password=$DB_PASSWORD \
  --spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver \
  --spring.jpa.hibernate.ddl-auto=none \
  --token-pilot.observe.ingestion.enabled=true \
  --token-pilot.observe.ingestion.rate-unit-tokens=1000"
```

| Option | Why |
|---|---|
| `--server.port=8081` | avoids clashing with the sample app (8080) |
| `ddl-auto=none` | with the current default `validate`, startup fails on a `pricing_catalogs.generated_yaml` type mismatch (server issue) |
| `observe.ingestion.enabled=true` | registers the v2 ingestion endpoint (404 when off) |
| `rate-unit-tokens=1000` | pricing plan rate unit. With the default `0` every event is `UNPRICED(RATE_UNIT_UNCONFIRMED)`. Token Pilot rates are also per 1,000 tokens |

### 3.4. Seed verification data

Run after Flyway finishes (`Started TokenLedgerCloudApplication` in the log).

- The organization id must be `default-org` (the ledger query API currently uses this fixed value)
- The project API key is stored as a BCrypt hash (generated with `htpasswd` from the `httpd` image)
- The pricing plan matches the sample app's `application-observe.yml`. Cached and reasoning rates use the same values
  so the price is final even when those token counts are unknown
- The `users` row is the subject of the ledger query JWT (3.6)

```bash
export TP_API_KEY_HASH="$(docker run --rm httpd:2.4-alpine htpasswd -nbBC 10 "" "$TP_API_KEY" | tr -d ':\n')"

docker exec -e MYSQL_PWD="$DB_PASSWORD" token-mysql-e2e mysql -uroot token_ledger -e "
INSERT IGNORE INTO organizations (id, name, plan, default_currency)
  VALUES ('default-org', 'Demo Org', 'FREE', 'USD');
INSERT IGNORE INTO projects (id, organization_id, project_key, name, status, default_model)
  VALUES ('proj-demo', 'default-org', 'demo-project', 'Demo Project', 'ACTIVE', 'gpt-4o-2024-08-06');
INSERT IGNORE INTO pricing_plans (id, provider, model, currency, prompt_rate, completion_rate,
  reasoning_rate, cached_prompt_rate, version, effective_from)
  VALUES ('plan-demo-gpt-4o', 'openai', 'gpt-4o-2024-08-06', 'USD', 0.000150, 0.000600,
  0.000600, 0.000150, 'demo-v1', '2020-01-01 00:00:00');
DELETE FROM project_api_keys WHERE id='key-demo';
INSERT INTO project_api_keys (id, organization_id, project_id, environment, name, key_prefix, key_hash, status)
  VALUES ('key-demo', 'default-org', 'proj-demo', 'demo', 'demo-key', 'tpk_demo', '$TP_API_KEY_HASH', 'ACTIVE');
INSERT IGNORE INTO users (id, email, name, role, provider)
  VALUES ('user-demo', 'demo@example.com', 'Demo User', 'ADMIN', 'local');
"
```

### 3.5. Sample app (observe profile)

```bash
cd token-pilot-cloud-api/token-pilot-sample-app

../gradlew -p . --no-daemon bootRun \
  --args='--spring.profiles.active=observe --token-pilot.client.endpoint=http://localhost:8081'
```

The remaining SDK settings (`ingestion-api: observe-v2`, `api-key: ${TP_API_KEY}`, `project-key: demo-project`,
`environment: demo`, `forwarded-tags`) are in `application.yml`.

Verification endpoints:

| Endpoint | Behaviour |
|---|---|
| `GET /test/token-pilot/observe/call?count=N` | calls the fake provider N times (1–20); each call's usage is sent by the SDK |
| `GET /test/token-pilot/observe/call?count=N&model=gpt-4o-mini-2024-07-18` | calls with a model the server has no price for (missing-price check). Only these two models are allowed |
| `GET /test/token-pilot/observe/stats` | reads current stats without creating events |
| `GET /test/token-pilot/observe/stats?flush=true` | waits until the SDK queue drains (up to 5 s), then reads stats |

Response fields:

| Field | Meaning |
|---|---|
| `providerAnswered` | calls that got a normal provider answer (must not depend on Observe failures) |
| `localRecordedEvents`, `localCostUsd` | events and cost total recorded by Token Pilot (baseline for ledger reconciliation) |
| `sdk.recorded` / `created` / `retried` / `queued` | queued into the SDK / newly stored by the server (201) / retry attempts / waiting to send |
| `sdk.droppedTotal`, `sdk.dropped` | dropped events and reasons (`RETRIES_EXHAUSTED`, `REQUEST_REFUSED`, ...) |

### 3.6. Ledger query setup

The ledger query API (`/api/observe/**`) requires authentication. For local verification, sign a token with `JWT_SECRET`.

> Member API key (`X-API-KEY`) authentication currently returns 500 (`LazyInitializationException`) on the server, so it is not used (server issue).

```bash
b64() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now=$(date +%s)
H=$(printf '{"alg":"HS256","typ":"JWT"}' | b64)
P=$(printf '{"sub":"demo@example.com","role":"ADMIN","iat":%d,"exp":%d}' "$now" $((now + 3600)) | b64)
export TP_LEDGER_JWT="$H.$P.$(printf '%s.%s' "$H" "$P" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | b64)"

ledger() {
  curl -s -H "Authorization: Bearer $TP_LEDGER_JWT" \
    "http://localhost:8081/api/observe/ledger/$1?projectId=proj-demo&environment=demo&$2"; echo
}
```

`projectId` is the project id (`proj-demo`), not the project key.

### 3.7. Normal ingestion and ledger reconciliation

```bash
curl -s "http://localhost:8080/test/token-pilot/observe/call?count=5"; echo
curl -s "http://localhost:8080/test/token-pilot/observe/stats?flush=true"; echo

ledger ingestion-lag  "period=today"   # eventCount, ingestion lag (secondsSinceLastReceived)
ledger spend-summary  ""               # ledgerConfirmedSpentUsd
ledger unpriced       "period=month"   # missing prices
ledger reconciliation "period=month"   # UNPRICED / RECONCILIATION_REQUIRED counts
```

Reconciliation criteria (first run on a fresh MySQL container):

| Sample app | Central ledger | Expected |
|---|---|---|
| `sdk.created` | `ingestion-lag.eventCount` | equal |
| `localCostUsd` | `spend-summary.ledgerConfirmedSpentUsd` | equal (ignoring decimal formatting) |
| — | `unpriced.unpricedEventCount`, `reconciliation.*` | `0` |

Cost per call: 8 × 0.00015/1K + 3 × 0.00060/1K = **0.000003 USD**. The ledger is cumulative; to reconcile again,
recreate the container or compare before/after differences.

#### Missing price (UNPRICED) query

Call with a model the sample app can price but the server's pricing table does not have.

```bash
curl -s "http://localhost:8080/test/token-pilot/observe/call?count=1&model=gpt-4o-mini-2024-07-18"; echo
curl -s "http://localhost:8080/test/token-pilot/observe/stats?flush=true"; echo

ledger unpriced       "period=month"
ledger spend-summary  ""
```

Expected:
- `unpriced.items` lists `gpt-4o-mini-2024-07-18` with 1 event (server reason `PRICE_NOT_FOUND`)
- The event is not added to `ledgerConfirmedSpentUsd`; `unreflectedEventCount` increases by 1
  (so from here on `localCostUsd` and `ledgerConfirmedSpentUsd` differ by this call)
- `ingestion-lag.unsettledCount` and `reconciliation.unpricedEventCount` also increase by 1

### 3.8. Fault injection ①: server down

1. Stop the server with `Ctrl+C` in its terminal.
2. Call the app and check stats a few seconds later.

```bash
curl -s -w '\nresponse time %{time_total}s\n' "http://localhost:8080/test/token-pilot/observe/call?count=3"
sleep 5
curl -s "http://localhost:8080/test/token-pilot/observe/stats"; echo
```

Expected:
- `providerAnswered=3` with a response time of a few ms (Observe failures do not block provider calls)
- 4 retries per event (`max-attempts` default 5 = first attempt + 4), then `dropped.RETRIES_EXHAUSTED` increases by 3
- **Retries finish within seconds, so usage during a longer server outage is not recorded centrally.** (section 5)

3. Restart the server with the 3.3 command, then `call` and `stats?flush=true` to confirm `created` increases again.

### 3.9. Fault injection ②: revoke the project API key

```bash
docker exec -e MYSQL_PWD="$DB_PASSWORD" token-mysql-e2e mysql -uroot token_ledger \
  -e "UPDATE project_api_keys SET status='REVOKED' WHERE id='key-demo';"

curl -s "http://localhost:8080/test/token-pilot/observe/call?count=2"; echo
curl -s "http://localhost:8080/test/token-pilot/observe/stats?flush=true"; echo
ledger ingestion-lag "period=today"
```

Expected:
- `providerAnswered=2`
- `dropped.REQUEST_REFUSED` increases by 2 while `retried` stays the same (401 is dropped without retry)
- Ledger `eventCount` unchanged

Restore:

```bash
docker exec -e MYSQL_PWD="$DB_PASSWORD" token-mysql-e2e mysql -uroot token_ledger \
  -e "UPDATE project_api_keys SET status='ACTIVE' WHERE id='key-demo';"
```

### 3.10. Clean up

```bash
docker rm -f token-mysql-e2e
unset DB_PASSWORD JWT_SECRET TP_API_KEY TP_API_KEY_HASH TP_LEDGER_JWT
```

## 4. Reference run (2026-10-28, local)

| Step | Sample app | Central ledger |
|---|---|---|
| 5 normal calls | `created=5`, 0 dropped | `eventCount=5`, `ledgerConfirmedSpentUsd=0.000015`, 0 unpriced |
| 4 calls after app restart | `localCostUsd=0.000012`, `created=4` | `eventCount=9`, `0.000027` (+4, +0.000012) |
| 3 calls with server down | `providerAnswered=3` (0.005 s), `retried=12`, `RETRIES_EXHAUSTED=3` | unchanged |
| 2 calls after server restart | `created` +2 | `eventCount=11` |
| 2 calls with revoked key | `providerAnswered=2`, `REQUEST_REFUSED=2`, `retried` unchanged | `eventCount=11` unchanged |
| 1 call after restoring key | `created` +1 | `eventCount=12`, `0.000036` |
| (fresh container) 2 normal calls + 1 unpriced-model call | `created=3`, `localCostUsd=0.000009` | `eventCount=3`, `ledgerConfirmedSpentUsd=0.000006`, `unreflectedEventCount=1`, `unpriced.items=[gpt-4o-mini-2024-07-18, 1 event]` |

## 5. Current limitations

| Limitation | Impact | Status |
|---|---|---|
| With budget on, the SDK forwards no events | On the budget path Token Pilot notifies `ReservationAccountingListener` instead of `LedgerListener`, and the SDK only subscribes to `LedgerListener` | SDK issue planned; pinned by `DemoProfileKnownGap` |
| Failed, timed-out and client-blocked calls are not sent | The SDK sends only `callStatus=SUCCEEDED` | included in the SDK issue above |
| Short retry window | Usage during outages longer than a few seconds is lost (`RETRIES_EXHAUSTED`) | tunable via `token-pilot.client.max-attempts`; operating policy to be discussed |
| Batch partial failure | SDK v2 delivery is per event, so the sample app path has no batch | covered by the server's `IngestionBatchIntegrationTest` |
| Ingestion metrics | SDK stats are exposed only via `/observe/stats` (not Prometheus) | ingestion lag via the server's `/api/observe/ledger/ingestion-lag` |
| Server needs `ddl-auto=none`; member API key returns 500 | worked around in this runbook (3.3, 3.6) | server issues, team agreement needed |

## 6. Troubleshooting

| Symptom | Cause |
|---|---|
| Sample app fails with `apiKey must not be blank` | `TP_API_KEY` is not exported in the sample app terminal |
| `Could not resolve cloud.token-pilot:...` | 1.2 local install was skipped |
| Server fails with `Schema validation: wrong column type ... generated_yaml` | `--spring.jpa.hibernate.ddl-auto=none` is missing |
| `sdk.dropped.REQUEST_REFUSED` increases | API key, `project-key` or `environment` mismatch, or a revoked key |
| Ledger query 404 "프로젝트를 찾을 수 없습니다" (project not found) | organization id is not `default-org`, or the project key was passed as `projectId` |
| `UNPRICED` in the ledger | missing `rate-unit-tokens` (`RATE_UNIT_UNCONFIRMED`) or no pricing plan (`PRICE_NOT_FOUND`) |
| `sdk.created` does not increase (and nothing dropped) | running the `demo` profile → use `observe` |
| Port 8080 in use | add `--server.port=<other port>` and change the curl URLs accordingly |
