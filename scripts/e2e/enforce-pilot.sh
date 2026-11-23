#!/usr/bin/env bash
# Boots this server against a throwaway MySQL with the Enforce pilot settings on and seeds one project, so the
# Java Client SDK can run its real-server E2E (token-pilot-client: RealServerEnforceE2ETest).
#
#   scripts/e2e/enforce-pilot.sh up     # prints the TOKEN_PILOT_E2E_* exports for the SDK test
#   scripts/e2e/enforce-pilot.sh down   # stops the server and removes the MySQL container
#
# Seeded data (all fake): project "e2e-pilot" with environments prod and e2e-hold, one project API key for any
# environment, openai/gpt-e2e priced at prompt 2 / completion 8 USD per 1M tokens, a project policy with a 1000 USD
# monthly hard limit and an e2e-hold policy with a 1 USD monthly hard limit (to make held reservations collide).
set -euo pipefail

PORT="${E2E_SERVER_PORT:-18080}"
DB_PORT="${E2E_DB_PORT:-33308}"
CONTAINER="${E2E_DB_CONTAINER:-tp-e2e-mysql}"
STATE_DIR="${E2E_STATE_DIR:-/tmp/tp-e2e}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

ORG=00000000-0000-0000-0000-0000000000e2
USER_ID=00000000-0000-0000-0000-0000000000a1
PROJECT=00000000-0000-0000-0000-0000000000b1
EMAIL=e2e-admin@example.test

down() {
	if [[ -f "$STATE_DIR/server.pid" ]]; then
		kill "$(cat "$STATE_DIR/server.pid")" 2>/dev/null || true
		rm -f "$STATE_DIR/server.pid"
	fi
	docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}

sql() {
	docker exec -i "$CONTAINER" mysql -uroot -pe2e token_ledger 2>/dev/null
}

# HS256 JWT with the same claims as JwtTokenProvider (subject = member email, role).
jwt() {
	python3 - "$1" "$2" <<'PY'
import base64, hashlib, hmac, json, sys, time
secret, subject = sys.argv[1].encode(), sys.argv[2]
b64 = lambda raw: base64.urlsafe_b64encode(raw).rstrip(b"=").decode()
now = int(time.time())
head = b64(json.dumps({"alg": "HS256"}).encode())
body = b64(json.dumps({"sub": subject, "role": "USER", "iat": now, "exp": now + 3600}).encode())
sig = b64(hmac.new(secret, f"{head}.{body}".encode(), hashlib.sha256).digest())
print(f"{head}.{body}.{sig}")
PY
}

up() {
	down
	mkdir -p "$STATE_DIR"
	docker run -d --name "$CONTAINER" -e MYSQL_ROOT_PASSWORD=e2e -e MYSQL_DATABASE=token_ledger \
		-p "$DB_PORT:3306" mysql:8.0 --character-set-server=utf8mb4 >/dev/null
	until docker exec "$CONTAINER" mysql -uroot -pe2e -e 'select 1' token_ledger >/dev/null 2>&1; do sleep 2; done

	(cd "$ROOT" && ./gradlew -q bootJar)
	local jar java_home secret
	jar="$(ls "$ROOT"/build/libs/*.jar | grep -v plain | head -1)"
	java_home="$(/usr/libexec/java_home -v 25 2>/dev/null || echo "${JAVA_HOME:-}")"
	secret="e2e-$(openssl rand -hex 32)"

	SERVER_PORT="$PORT" \
	SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:$DB_PORT/token_ledger?useSSL=false&allowPublicKeyRetrieval=true" \
	SPRING_DATASOURCE_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver \
	SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=e2e SPRING_JPA_SHOW_SQL=false \
	JWT_SECRET="$secret" GOOGLE_CLIENT_ID=e2e GOOGLE_CLIENT_SECRET=e2e KAKAO_CLIENT_ID=e2e KAKAO_CLIENT_SECRET=e2e \
	TOKEN_PILOT_DECISION_ENABLED=true TOKEN_PILOT_DECISION_ENFORCE_ENABLED=true \
	TOKEN_PILOT_OBSERVE_INGESTION_RATE_UNIT_TOKENS=1000000 TOKEN_PILOT_RESERVATION_EXPIRY_ENABLED=true \
		nohup "${java_home:+$java_home/bin/}java" -jar "$jar" >"$STATE_DIR/server.log" 2>&1 &
	echo $! >"$STATE_DIR/server.pid"
	until curl -fsS "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; do
		kill -0 "$(cat "$STATE_DIR/server.pid")" 2>/dev/null || { tail -40 "$STATE_DIR/server.log"; exit 1; }
		sleep 2
	done

	local raw hash
	raw="tpk_live_$(uuidgen | tr 'A-Z' 'a-z')"
	hash="$(htpasswd -bnBC 10 "" "$raw" | tr -d ':\n')"
	sql <<SQL
insert into organizations(id, name, plan) values ('$ORG', 'E2E', 'PILOT');
insert into users(id, email, name, role, provider) values ('$USER_ID', '$EMAIL', 'e2e admin', 'USER', 'local');
insert into organization_members(id, organization_id, user_id, role) values (uuid(), '$ORG', '$USER_ID', 'ADMIN');
insert into projects(id, organization_id, project_key, name, status) values ('$PROJECT', '$ORG', 'e2e-pilot', 'E2E pilot', 'ACTIVE');
insert into project_environments(id, organization_id, project_id, environment) values
	(uuid(), '$ORG', '$PROJECT', 'prod'), (uuid(), '$ORG', '$PROJECT', 'e2e-hold');
insert into project_api_keys(id, organization_id, project_id, environment, name, key_prefix, key_hash, status)
	values (uuid(), '$ORG', '$PROJECT', null, 'e2e', '${raw:0:30}', '$hash', 'ACTIVE');
insert into pricing_plans(id, provider, model, currency, prompt_rate, completion_rate, version, effective_from)
	values (uuid(), 'openai', 'gpt-e2e', 'USD', 2, 8, 'e2e-v1', '2026-01-01 00:00:00');
SQL

	local token
	token="$(jwt "$secret" "$EMAIL")"
	policy() {
		curl -sS --fail-with-body -o "$STATE_DIR/policy.json" -X PUT "http://127.0.0.1:$PORT/api/policies/projects/$PROJECT$1" \
			-H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
			-d "{\"expectedVersion\":0,\"rules\":{\"currency\":\"USD\",\"monthlyHardLimitUsd\":\"$2\",\"active\":true,\"notificationThresholds\":[]},\"reason\":\"e2e\"}" \
			|| { cat "$STATE_DIR/policy.json" >&2; echo >&2; exit 1; }
	}
	policy "" 1000
	policy "?environment=e2e-hold" 1

	# Also kept in $STATE_DIR/env (source it); the key is fake and dies with "down".
	cat <<ENV | tee "$STATE_DIR/env"
export TOKEN_PILOT_E2E_ENDPOINT=http://127.0.0.1:$PORT
export TOKEN_PILOT_E2E_API_KEY=$raw
export TOKEN_PILOT_E2E_MODEL=gpt-e2e
# MySQL for checking reservations: docker exec -it $CONTAINER mysql -uroot -pe2e token_ledger
ENV
}

case "${1:-}" in
	up) up ;;
	down) down ;;
	*) echo "usage: $0 up|down" >&2; exit 2 ;;
esac
