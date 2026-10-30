# Observe E2E runbook (이슈 #13)

영문 버전: [`OBSERVE_E2E_RUNBOOK_EN.md`](OBSERVE_E2E_RUNBOOK_EN.md)

샘플 앱 → Java Client SDK → Cloud API `POST /api/v2/observe/events` → 중앙 원장 → 원장 조회 API까지의
비차단 Observe 파일럿을 검증하는 절차입니다.

- 검증 대상: 실제 사용량 전송, 중앙 원장 금액 대조, 서버 장애·API Key 폐기 시 provider 결과 불변, SDK 재시도
- provider는 네트워크 없는 가짜 `ChatModel`(`DemoChatModel`, 호출당 8/3 토큰)이라 provider API key가 필요 없습니다.
- Token Pilot 라이브러리 기능 데모(context admission, 예산 예약, 멱등성 등)는 이 문서의 범위가 아닙니다.
  tokenpilot 저장소의 `token-pilot-sample-app/DEMO_RUNBOOK.md`를 참고하세요.

명령은 bash 기준입니다. Windows에서는 Git Bash를 사용합니다.

## 1. 사전 준비

### 1.1. 도구

- JDK 25, Docker, `curl`, `openssl` (Git Bash에 포함)
- JSON을 보기 좋게 보려면 `jq`(선택). 아래 명령은 `jq` 없이도 동작합니다.

### 1.2. 미배포 의존성 로컬 설치

`token-pilot-starter`와 `token-pilot-client-tokenpilot`은 원격 저장소에 배포되지 않았습니다.
샘플 앱을 빌드하기 전에 두 저장소를 로컬 Maven 저장소(`~/.m2`)에 설치합니다.

```bash
cd <tokenpilot 저장소>        && ./gradlew publishToMavenLocal
cd <token-pilot-client 저장소> && ./gradlew publishToMavenLocal
```

### 1.3. 빌드 구조

샘플 앱은 서버 빌드에 포함되지 않는 독립 Gradle 빌드입니다(자체 `settings.gradle`).
서버 저장소 루트의 Gradle wrapper를 `-p .`로 빌려 쓰므로, 샘플 앱 명령은 `token-pilot-sample-app`에서 실행합니다.

### 1.4. 프로필

| 프로필 | 예산(budget) | Observe 전송 | 용도 |
|---|---|---|---|
| `observe` | 꺼짐 | 됨 | **이 문서의 E2E 검증** |
| `demo` | 켜짐 | **안 됨** (5장 참고) | Token Pilot 기능 데모 |

두 프로필 모두 `--token-pilot.client.endpoint=...`를 줘야 SDK가 켜집니다(기본값 꺼짐).

## 2. 자동 테스트 (서버·DB 불필요)

```bash
cd token-pilot-cloud-api/token-pilot-sample-app
../gradlew -p . --no-daemon test
```

| 테스트 | 검증 내용 |
|---|---|
| `ObserveProfileE2ETest.ObserveProfile` | `observe` 프로필 실제 기동 → 3회 호출 → 가짜 수집 서버에 실제 사용량(8/3) 이벤트 3건, 프롬프트 원문 미포함, 로컬 비용 합계. 요청 모델 전달(가격 누락 검증용), 허용 외 모델 거부 |
| `ObserveProfileE2ETest.DemoProfileKnownGap` | `demo` 프로필(예산 켜짐)은 현재 SDK로 0건 전송됨 (5장 제약을 고정. SDK가 고쳐지면 실패하므로 그때 갱신) |
| `ObservePilotE2ETest` | SDK 자동 구성 단위: v2 경로, 지정 태그만 전송, 가격 없는 모델·모델 불일치 메타데이터, 503 후 재시도, 401(폐기 키) 재시도 없이 버림, 서버 다운 시 provider 결과 불변 |
| `DemoScenarioE2ETest` | `demo` 프로필 HTTP 계약 |

## 3. 실서버 E2E

모든 비밀값은 셸 환경 변수로만 다룹니다. 3장의 명령은 **3.1의 변수를 export한 터미널**에서 실행합니다
(서버·샘플 앱을 다른 터미널에서 띄운다면 그 터미널에도 같은 값을 export).

### 3.1. 환경 변수

```bash
export DB_PASSWORD="$(openssl rand -hex 16)"         # 로컬 MySQL root 비밀번호
export JWT_SECRET="$(openssl rand -hex 32)"          # 서버 JWT 서명 키 (원장 조회 토큰에도 사용)
export TP_API_KEY="tpk_demo_$(openssl rand -hex 16)" # 프로젝트 API Key (SDK가 사용)
```

### 3.2. MySQL

```bash
docker run --name token-mysql-e2e -p 3308:3306 \
  -e MYSQL_ROOT_PASSWORD="$DB_PASSWORD" \
  -e MYSQL_DATABASE=token_ledger \
  -d mysql:8.0
```

### 3.3. Cloud API 서버 (8081)

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

| 옵션 | 이유 |
|---|---|
| `--server.port=8081` | 샘플 앱(8080)과 충돌 방지 |
| `ddl-auto=none` | 현재 기본값 `validate`로는 `pricing_catalogs.generated_yaml` 타입 불일치로 기동 실패 (서버 이슈) |
| `observe.ingestion.enabled=true` | v2 수집 endpoint 등록 (꺼져 있으면 404) |
| `rate-unit-tokens=1000` | 가격표 요율 단위. 기본값 `0`이면 모든 이벤트가 `UNPRICED(RATE_UNIT_UNCONFIRMED)`. Token Pilot 요율도 1,000토큰당 |

### 3.4. 검증용 데이터 등록

Flyway 마이그레이션이 끝난 뒤(`Started TokenLedgerCloudApplication` 로그) 실행합니다.

- 조직 id는 반드시 `default-org` (원장 조회 API가 현재 이 값으로 고정)
- 프로젝트 API Key는 BCrypt 해시로 저장 (`httpd` 이미지의 `htpasswd`로 생성)
- 가격표는 샘플 앱 `application-observe.yml`과 같은 요율. 캐시·추론 요율도 같은 값으로 넣어 해당 토큰 수를 몰라도 가격이 확정되게 함
- `users` 행은 원장 조회 JWT의 사용자(3.6)

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

### 3.5. 샘플 앱 (observe 프로필)

```bash
cd token-pilot-cloud-api/token-pilot-sample-app

../gradlew -p . --no-daemon bootRun \
  --args='--spring.profiles.active=observe --token-pilot.client.endpoint=http://localhost:8081'
```

나머지 SDK 설정(`ingestion-api: observe-v2`, `api-key: ${TP_API_KEY}`, `project-key: demo-project`,
`environment: demo`, `forwarded-tags`)은 `application.yml`에 있습니다.

검증용 endpoint:

| endpoint | 동작 |
|---|---|
| `GET /test/token-pilot/observe/call?count=N` | 가짜 provider를 N번(1~20) 호출. 각 호출의 사용량이 SDK로 전송됨 |
| `GET /test/token-pilot/observe/call?count=N&model=gpt-4o-mini-2024-07-18` | 서버에 가격표가 없는 모델로 호출 (가격 누락 검증용). 허용 모델은 이 둘뿐 |
| `GET /test/token-pilot/observe/stats` | 이벤트를 만들지 않고 현재 통계만 조회 |
| `GET /test/token-pilot/observe/stats?flush=true` | SDK 큐가 빌 때까지(최대 5초) 기다린 뒤 조회 |

응답 필드:

| 필드 | 의미 |
|---|---|
| `providerAnswered` | provider 응답을 정상으로 받은 호출 수 (Observe 실패와 무관해야 함) |
| `localRecordedEvents`, `localCostUsd` | Token Pilot이 기록한 이벤트 수·비용 합계 (원장 대조 기준값) |
| `sdk.recorded` / `created` / `retried` / `queued` | SDK 큐 진입 / 서버가 새로 기록(201) / 재시도 횟수 / 전송 대기 |
| `sdk.droppedTotal`, `sdk.dropped` | 버린 이벤트 수와 이유 (`RETRIES_EXHAUSTED`, `REQUEST_REFUSED` 등) |

### 3.6. 원장 조회 준비

원장 조회 API(`/api/observe/**`)는 로그인이 필요합니다. 로컬 검증에서는 `JWT_SECRET`으로 토큰을 직접 서명합니다.

> 회원 API Key(`X-API-KEY`) 인증은 현재 서버에서 500(`LazyInitializationException`)이 나서 쓰지 않습니다 (서버 이슈).

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

`projectId`는 프로젝트 key가 아니라 id(`proj-demo`)입니다.

### 3.7. 정상 수집과 원장 대조

```bash
curl -s "http://localhost:8080/test/token-pilot/observe/call?count=5"; echo
curl -s "http://localhost:8080/test/token-pilot/observe/stats?flush=true"; echo

ledger ingestion-lag  "period=today"   # eventCount, 수집 지연(secondsSinceLastReceived)
ledger spend-summary  ""               # ledgerConfirmedSpentUsd
ledger unpriced       "period=month"   # 가격 누락 목록
ledger reconciliation "period=month"   # UNPRICED / RECONCILIATION_REQUIRED 건수
```

대조 기준 (새 MySQL 컨테이너에서 처음 실행한 경우):

| 샘플 앱 | 중앙 원장 | 기대 |
|---|---|---|
| `sdk.created` | `ingestion-lag.eventCount` | 같음 |
| `localCostUsd` | `spend-summary.ledgerConfirmedSpentUsd` | 같음 (표기 자릿수 차이 무시) |
| — | `unpriced.unpricedEventCount`, `reconciliation.*` | `0` |

호출당 비용: 8 × 0.00015/1K + 3 × 0.00060/1K = **0.000003 USD**. 원장은 누적값이므로 다시 대조할 때는
컨테이너를 새로 만들거나 전후 차이로 비교합니다.

#### 가격 누락(UNPRICED) 조회

샘플 앱은 가격을 알지만 서버 가격표에는 없는 모델로 호출합니다.

```bash
curl -s "http://localhost:8080/test/token-pilot/observe/call?count=1&model=gpt-4o-mini-2024-07-18"; echo
curl -s "http://localhost:8080/test/token-pilot/observe/stats?flush=true"; echo

ledger unpriced       "period=month"
ledger spend-summary  ""
```

기대 결과:
- `unpriced.items`에 `gpt-4o-mini-2024-07-18` 1건 (서버 사유 `PRICE_NOT_FOUND`)
- 이 이벤트는 `ledgerConfirmedSpentUsd`에 더해지지 않고 `unreflectedEventCount`가 1 증가
  (그래서 이 단계 이후에는 `localCostUsd`와 `ledgerConfirmedSpentUsd`가 이 호출분만큼 차이 남)
- `ingestion-lag.unsettledCount`, `reconciliation.unpricedEventCount`도 1 증가

### 3.8. 장애 주입 ①: 서버 다운

1. 서버 터미널에서 `Ctrl+C`로 종료합니다.
2. 호출하고 몇 초 뒤 통계를 봅니다.

```bash
curl -s -w '\n응답시간 %{time_total}s\n' "http://localhost:8080/test/token-pilot/observe/call?count=3"
sleep 5
curl -s "http://localhost:8080/test/token-pilot/observe/stats"; echo
```

기대 결과:
- `providerAnswered=3`, 응답 시간은 수 ms 수준 (Observe 실패가 provider 호출을 막지 않음)
- 이벤트당 재시도 4회(`max-attempts` 기본값 5 = 첫 시도 + 4회) 후 `dropped.RETRIES_EXHAUSTED`가 3 증가
- **재시도는 수 초 안에 끝나므로, 그보다 긴 서버 중단 동안의 사용량은 중앙에 남지 않습니다.** (5장)

3. 3.3 명령으로 서버를 다시 띄우고, `call` 후 `stats?flush=true`에서 `created`가 다시 늘어나는지 확인합니다.

### 3.9. 장애 주입 ②: 프로젝트 API Key 폐기

```bash
docker exec -e MYSQL_PWD="$DB_PASSWORD" token-mysql-e2e mysql -uroot token_ledger \
  -e "UPDATE project_api_keys SET status='REVOKED' WHERE id='key-demo';"

curl -s "http://localhost:8080/test/token-pilot/observe/call?count=2"; echo
curl -s "http://localhost:8080/test/token-pilot/observe/stats?flush=true"; echo
ledger ingestion-lag "period=today"
```

기대 결과:
- `providerAnswered=2`
- `dropped.REQUEST_REFUSED`가 2 증가, `retried`는 그대로 (401은 재시도하지 않고 바로 버림)
- 원장 `eventCount` 변화 없음

복구:

```bash
docker exec -e MYSQL_PWD="$DB_PASSWORD" token-mysql-e2e mysql -uroot token_ledger \
  -e "UPDATE project_api_keys SET status='ACTIVE' WHERE id='key-demo';"
```

### 3.10. 정리

```bash
docker rm -f token-mysql-e2e
unset DB_PASSWORD JWT_SECRET TP_API_KEY TP_API_KEY_HASH TP_LEDGER_JWT
```

## 4. 참고 실행 결과 (2026-10-28, 로컬)

| 단계 | 샘플 앱 | 중앙 원장 |
|---|---|---|
| 정상 5회 | `created=5`, dropped 0 | `eventCount=5`, `ledgerConfirmedSpentUsd=0.000015`, unpriced 0 |
| 앱 재시작 후 4회 | `localCostUsd=0.000012`, `created=4` | `eventCount=9`, `0.000027` (+4건, +0.000012) |
| 서버 다운 3회 | `providerAnswered=3`(0.005s), `retried=12`, `RETRIES_EXHAUSTED=3` | 변화 없음 |
| 서버 재기동 후 2회 | `created` +2 | `eventCount=11` |
| 키 폐기 2회 | `providerAnswered=2`, `REQUEST_REFUSED=2`, `retried` 변화 없음 | `eventCount=11` 유지 |
| 키 복구 후 1회 | `created` +1 | `eventCount=12`, `0.000036` |
| (새 컨테이너) 정상 2회 + 가격 없는 모델 1회 | `created=3`, `localCostUsd=0.000009` | `eventCount=3`, `ledgerConfirmedSpentUsd=0.000006`, `unreflectedEventCount=1`, `unpriced.items=[gpt-4o-mini-2024-07-18 1건]` |

## 5. 현재 제약

| 제약 | 영향 | 상태 |
|---|---|---|
| 예산(budget)을 켜면 SDK로 이벤트가 전송되지 않음 | Token Pilot이 예산 경로에서 `LedgerListener` 대신 `ReservationAccountingListener`로 알리는데, SDK는 `LedgerListener`만 구독 | SDK 이슈 예정. `DemoProfileKnownGap` 테스트로 고정 |
| 실패·timeout·호출 전 차단 흐름 미전송 | SDK가 `callStatus=SUCCEEDED`만 보냄 | 위 SDK 이슈에 포함 예정 |
| 짧은 재시도 창 | 서버 중단이 수 초를 넘으면 그 사이 사용량 유실 (`RETRIES_EXHAUSTED`) | `token-pilot.client.max-attempts`로 조정 가능, 운영 기준은 논의 필요 |
| batch 부분 실패 | SDK v2 전송은 단건이라 샘플 앱 경로에 batch 없음 | 서버 `IngestionBatchIntegrationTest`가 검증 |
| 수집 지표 | SDK 통계는 `/observe/stats`로만 노출 (Prometheus 미노출) | 수집 지연은 서버 `/api/observe/ledger/ingestion-lag` |
| 서버 기동 `ddl-auto=none`, 회원 API Key 500 | 런북에서 우회 (3.3, 3.6) | 서버 이슈, 팀 합의 필요 |

## 6. 문제 해결

| 증상 | 원인 |
|---|---|
| 샘플 앱 기동 실패 `apiKey must not be blank` | 샘플 앱 터미널에 `TP_API_KEY`가 export되지 않음 |
| `Could not resolve cloud.token-pilot:...` | 1.2 로컬 설치를 하지 않음 |
| 서버 기동 실패 `Schema validation: wrong column type ... generated_yaml` | `--spring.jpa.hibernate.ddl-auto=none` 누락 |
| `sdk.dropped.REQUEST_REFUSED` 증가 | API Key·`project-key`·`environment` 불일치 또는 키 폐기 |
| 원장 조회 404 "프로젝트를 찾을 수 없습니다" | 조직 id가 `default-org`가 아니거나 `projectId`에 key를 넣음 |
| 원장에 `UNPRICED` | `rate-unit-tokens` 누락(`RATE_UNIT_UNCONFIRMED`) 또는 가격표 없음(`PRICE_NOT_FOUND`) |
| `sdk.created`가 늘지 않음 (dropped도 0) | `demo` 프로필로 실행함 → `observe` 프로필 사용 |
| 8080 포트 사용 중 | 다른 프로세스가 사용 중이면 `--server.port=<다른 포트>`를 추가하고 curl 주소도 바꿈 |
