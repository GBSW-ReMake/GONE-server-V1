# #42 외출증 마감 지난 PENDING 처리 (승인/거절 차단 + MISSED 스케줄러) — QA 결과

관련 기획서: [42-outing-missed-scheduler.md](./42-outing-missed-scheduler.md)
코드 리뷰 결과(9단계): [42-outing-missed-scheduler-code-review.md](./42-outing-missed-scheduler-code-review.md)

## 1. QA/QC (10단계)

### 자동 검증
- `./gradlew checkstyleMain checkstyleTest test`: **통과** (신규 테스트 포함, 코드 리뷰
  반영 후 재실행 포함). `@SpringBootTest` 컨텍스트 로드 시 Flyway `V8__add_outing_version.sql`
  마이그레이션도 정상 적용됨을 확인.

### 수동 검증 (실서버, `localhost:9091`, dev 프로필, MySQL/Redis 로컬 기동)
`user1`(STUDENT)/`teacher1`(TEACHER) 계정으로 실제 요청을 보내 확인했다. 이번 이슈는 #41과
달리 DB를 직접 조작하지 않고, **실제로 마감이 지날 때까지 실시간으로 기다려서** 검증했다 —
승인/거절 자체가 실시간 재계산이고 스케줄러도 실제 1분 주기로 동작하므로, 실제 시간 경과가
가장 정확한 검증 방법이라고 판단했다.

| # | 케이스 | 기대 | 결과 |
|---|---|---|---|
| 1 | 마감 전 정상 승인(회귀 확인, `CUSTOM` 10:40~10:41 신청 후 즉시 승인) | 200, `APPROVED` | ✅ |
| 2 | 마감이 지난 `PENDING`(DB는 아직 `PENDING`)을 승인 시도 | 409 `OUTING_008` | ✅ |
| 3 | 마감이 지난 `PENDING`(DB는 아직 `PENDING`)을 거절 시도 | 409 `OUTING_008` | ✅ |
| 4 | 마감 직후 `GET /outings/{code}` 단건 조회 | `status: "MISSED"`(실시간 계산, DB는 아직 `PENDING`) | ✅ |
| 5 | 4번 건을 스케줄러 주기(최대 1분) 이상 대기 후 다시 승인 시도 | 409 `OUTING_005`(`ALREADY_PROCESSED`) — DB가 실제로 `MISSED`로 바뀌었다는 간접 증거 | ✅ |

**2번(승인)/3번(거절) 모두 DB가 여전히 `PENDING`인 시점에 정확히 `OUTING_008`로 막히는지가
핵심 검증 포인트다.** 3번은 첫 시도에서 curl 인자에 한글을 직접 넣어(`rejectedReason`)
`500 COMMON_007`(Jackson UTF-8 파싱 오류)이 났는데, 이는 `30-outing-approve-QA.md`에 이미
기록된 것과 동일한 **터미널 인코딩 문제(서버 버그 아님)** — `--data-binary @file`(UTF-8
파일)로 재요청해 정상 확인했다. 다만 재요청 사이에 스케줄러가 먼저 그 건을 `MISSED`로
갱신해버려 처음 재현은 `OUTING_005`로 나왔고, 별도로 마감 시각을 더 타이트하게 잡은 건을
새로 신청해(`O5`) 스케줄러보다 먼저 요청이 도착하도록 재시도해서 `OUTING_008`을 깨끗하게
재현했다(5번 표 3번 행).

**5번은 스케줄러가 실제로 DB를 갱신했다는 걸 코드 경로로 증명하는 방식이다**: `approveOuting`
의 체크 순서가 `status != PENDING`(`ALREADY_PROCESSED`) → 마감 재계산(`DEADLINE_PASSED`)
순이라, DB가 계속 `PENDING`이면 몇 번을 다시 요청해도 항상 `OUTING_008`만 나온다. 실제로
70초 대기 후 `OUTING_005`로 바뀐 것은 그 사이 DB의 `status`가 `PENDING`이 아닌 값(즉
`MISSED`)으로 실제로 바뀌었다는 뜻이다 — DB를 직접 조회하지 않고도(로컬에 `mysql` CLI가
없어 직접 조회는 불가) API 응답만으로 스케줄러 동작을 간접 증명했다.

**Medium — 환경 제약으로 직접 재현 못 한 항목**: 코드 리뷰 1번에서 지적된 "스케줄러 조회와
승인/거절 커밋이 정확히 겹치는" 레이스는 밀리초 단위로 두 트랜잭션을 정확히 겹치게 만들어야
재현되는데, curl 기반 수동 QA로는 그 타이밍을 통제할 수 없었다. 대신
`OutingServiceTest.MarkSingleOutingAsMissed.swallowsOptimisticLockingFailure`(낙관적 락
충돌 시 예외를 삼키고 넘어가는지)와 `skipsWhenAlreadyProcessed`(조회 시점에 이미 처리된
건은 건드리지 않는지) 단위 테스트로 그 두 방어선을 각각 검증했다 — #31/#41과 동일한 판단
(환경 제약 시 단위 테스트로 대체).

## 2. 결론

Critical/High 없음. QA 중 발견한 문제 1건은 서버 버그가 아니라 이 QA 스크립트(curl)의
터미널 인코딩 문제였고(위 3번 케이스 설명 참고, `30-outing-approve-QA.md`와 동일 원인),
서버 로직에는 영향이 없음을 확인했다. Medium 1건(동시성 레이스의 실서버 재현 불가)은 단위
테스트로 대체 확인 완료. 승인/거절 마감 차단과 스케줄러의 DB 실제 반영 모두 실시간 대기로
직접 검증했고, 추가 조치 없이 다음 단계(문제사항 보고)로 진행 가능하다고 판단했다.
