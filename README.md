# Token Pilot Cloud API
---
## Demo
- 🌐 Frontend: https://token-ledger-cloud-front.vercel.app
- ⚙️ Backend API: http://52.78.69.13:8080  
- 🧩 Frontend Repository: https://github.com/token-ledger/token-ledger-cloud-front
---
## Architecture

Frontend (Next.js / Vercel)
↓ (API Proxy)
Backend (Spring Boot / EC2 Docker)
↓
Database (AWS RDS MySQL)

---
## 📊 주요 기능
- 💰 총 비용 / 토큰 / 차단 요청 KPI 제공
- 📈 모델별 비용 집계
- 🏆 프로젝트별 비용 랭킹
- 📦 usage log 기반 실시간 데이터 반영

---
## API
### KPI 조회

GET /api/dashboard/kpi?projectId=1&period=week

### 모델 비용 요약

GET /api/dashboard/model-cost-summary?projectId=1&period=week

### 프로젝트 비용 랭킹

GET /api/dashboard/project-ranking?period=month

### 📥 Usage Log 수집

POST /internal/usage-logs

## 📥 Usage Log 예시
```bash
curl -X POST http://52.78.69.13:8080/internal/usage-logs \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "idempotencyKey": "idem-001",
    "projectId": 1,
    "applicationId": 1,
    "userId": 1,
    "modelId": "gpt-4o-mini",
    "inputTokens": 1200,
    "outputTokens": 800,
    "totalTokens": 2000,
    "totalCost": 0.0035,
    "currencyCode": "USD",
    "status": "SUCCESS",
    "startedAt": "2026-04-29T12:00:00",
    "finishedAt": "2026-04-29T12:00:02",
    "latencyMs": 2000
  }'
```
___

## Local 실행

### Backend (Spring Boot)

./gradlew bootRun

🐳 배포 (EC2)

docker build -t token-ledger-cloud-api .
docker run -d -p 8080:8080 token-ledger-cloud-api

### Database (RDS)

Host: token-ai-ledger-db.crumy8a0eyuf.ap-northeast-2.rds.amazonaws.com
Port: 3306
DB: token_ledger

___

### Tech Stack

* Backend: Spring Boot, JPA, MySQL
* Frontend: Next.js (App Router), TailwindCSS (`token-ledger-cloud-front`)
* Infra: AWS EC2, RDS, Docker, Vercel
* Monitoring: Micrometer (확장 예정)

___
