# 수집 지연 조회 API (#11)

`GET /api/observe/ledger/ingestion-lag?projectId=...&environment=...&period=today`

Observe 이벤트가 호출 발생 뒤 서버에 도착하기까지 얼마나 걸리는지, 수집이 멈춰 있지 않은지 본다. 로그인한 회원만 쓸 수 있다(`/api/observe/**`).

## 요청

| 파라미터 | 필수 | 설명 |
| --- | --- | --- |
| `projectId` | 예 | 조회할 프로젝트 |
| `environment` | 아니오 | 비우면 프로젝트 전체 |
| `period` | 아니오 | 기본 `today`. 기간 경계는 **UTC 날짜** 기준이다(한국 시간 오전 9시에 날짜가 바뀐다) |

## 응답 (`data`)

| 필드 | 단위 | 설명 |
| --- | --- | --- |
| `from`, `to` | UTC | 조회 기간 |
| `eventCount` | 건 | 기간 안의 원장 이벤트 수 |
| `unsettledCount` | 건 | 그중 아직 정산되지 않은 이벤트 수 |
| `lastReceivedAt` | **UTC** | 서버가 마지막으로 받은 시각 |
| `lastOccurredAt` | UTC | 가장 최근 호출 발생 시각 |
| `secondsSinceLastReceived` | 초 | 마지막 수신 후 지난 시간. 계속 커지면 수집이 멈춘 것이다 |
| `maxDelaySeconds` | 초 | 발생→수신 지연 중 가장 긴 것 |
| `averageDelaySeconds` | 초 | 발생→수신 지연의 평균 |

- 이벤트가 없으면 시각과 지연 값은 `null`이다. 0초로 바꾸지 않는다.
- 지연은 밀리초로 합산한 뒤 마지막에만 초 단위로 버린다. 0.9초, 1.9초면 평균은 1초다(PR #33 리뷰 반영, `9826b0e`).
- 클라이언트 시계가 빨라 발생 시각이 수신보다 늦으면 그 이벤트의 지연은 0초로 본다.

## 시각은 모두 UTC (주의)

PR #33부터 `lastReceivedAt`도 UTC다. 그 전에는 서버(JVM) 시간대 값이 그대로 나가서, 서버가 `Asia/Seoul`이면 다른 시각들과 9시간 어긋났다.

응답 문자열에는 시간대 표시가 없다(예: `"2026-10-10T12:01:30"`, `Z` 없음). **화면이나 다른 서비스에서 이 값을 쓸 때는 UTC로 해석해야 한다.** 브라우저에서 `new Date("2026-10-10T12:01:30")`처럼 그대로 파싱하면 사용자 PC 시간대로 해석되어 한국에서는 9시간 틀어진다. `Z`를 붙이거나 UTC로 지정해서 파싱한다.

```js
const lastReceived = new Date(data.lastReceivedAt + "Z"); // UTC로 해석
```

이 저장소 안에는 `lastReceivedAt`을 서버 시간대로 해석하던 코드가 없다(검색 결과 DTO와 테스트뿐). 프론트엔드 등 다른 저장소에서 쓰고 있다면 위처럼 맞춘다.

## 관련

- 미귀속 비용(`/api/observe/ledger/unattributed-cost`)은 `QUARANTINED` 이벤트만 센다. 지금은 이 상태로 바꾸는 코드가 없어 항상 0이다. "미귀속"의 정의가 정해지면 따로 처리한다(#11 남은 항목).
