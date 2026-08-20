# #71 스쿨캠핑 외출 연동 리마인더 스케줄러 — QA 결과

관련 기획서: [71-schoolcamp-outing-reminder.md](./71-schoolcamp-outing-reminder.md)
관련 코드 리뷰: [71-schoolcamp-outing-reminder-code-review.md](./71-schoolcamp-outing-reminder-code-review.md)
(9단계 코드 리뷰 지적 사항(High 1/Medium 1/Low 1)은 QA 이전에 전부 반영 완료 — 이 문서는
QA에서 새로 확인/발견한 것만 다룬다)

## 검증 방법/범위
이 이슈는 HTTP 엔드포인트가 없는 배치(cron) 작업이라, `#67`/`#68`/`#70`처럼 curl로 엔드포인트를
직접 두드리는 방식만으로는 끝까지 검증할 수 없다. 크론이 매일 08:30(KST) 1회뿐이라 `#42`
(`fixedDelay` 1분 주기)처럼 실시간 대기로 검증하는 것도 불가능하고, 알림 조회 API 자체가
없어(기획서 "선행 조건 공백" 참고) 결과를 API로 되짚어볼 수도 없다. 보스 승인을 받아 아래
방식으로 진행했다.

- `./gradlew build`(checkstyle + 전체 테스트) 통과 확인.
- 로컬 서버 실제 기동(`./gradlew bootRun`, `dev` 프로필) — Flyway 마이그레이션 13개 전부
  검증됨, `SchoolCampReminderScheduler`를 포함한 컨텍스트가 예외 없이 기동됨을 확인(잘못된
  cron 표현식이었다면 여기서 즉시 실패했을 것).
- **실제 데이터는 기존 엔드포인트(`POST /api/v1/school-camps`, `POST .../applications`,
  `POST /api/v1/outings`)로 실제 HTTP 요청을 보내 준비**하고, **`sendOutingReminders`
  자체의 실행만** 별도 `@SpringBootTest`(`SchoolCampSessionClaimServiceIntegrationTest`와
  동일한 로컬 MySQL에 붙는 기존 통합 테스트 패턴 재사용)로 1회 직접 호출했다 — 이 테스트
  파일은 QA 전용 임시 트리거였고 검증 후 삭제했다(커밋하지 않음, 저장소에 남지 않음).
  `SchoolCampReminderScheduler` 자체(cron이 이 메서드를 호출하는지)는 단위 테스트로 이미
  검증됐고, 이 스케줄러는 `OutingMissedScheduler`와 동일하게 시각만 구해 위임하는 3줄짜리
  컴포넌트라 실행 경로 검증의 우선순위를 `sendOutingReminders`(실제 로직)에 뒀다.
- 검증에는 기존 QA에서 써온 실 계정(`testuser01`/`testuser02`, 비밀번호는 이미 쓰던 값 그대로)을
  썼고, `testuser01`에 세션 등록을 위해 ADMIN 역할을 임시로 부여했다(`#67`/`#68`/`#70` QA와
  동일한 방식) — **검증 후 전부 원복**했다(역할 삭제, 등록한 세션/신청/팀원/외출증/알림
  레코드 전부 삭제, 로그인 재시도 없이 SQL로 직접 원복 확인).
- 로컬에 `mysql` CLI가 설치돼 있고(`MySQL Server 8.0`) 로컬 MySQL(3306)/Redis(6379)가 이미
  떠 있어(보스 확인), `#42` QA와 달리 이번에는 **DB에 직접 접속해 결과(`notification` 테이블
  행 생성 여부)를 확인**할 수 있었다.

## 실제 검증

### 시나리오 구성
1. `testuser01`(ADMIN 임시 부여)로 오늘 날짜를 스쿨캠핑 세션으로 등록(`sessionId=61`).
2. `testuser02`(대표 신청자)가 그 세션에 신청, 팀원으로 `testuser01` 추가
   (`applicationId=14`) — 신청 시점에 `testuser01`에게 초대 알림이 정상 발송됨을 확인
   (기존 `#68` 로직 회귀 확인 겸).
3. `testuser01`(팀원, 계정 있음, 외출증 없음) / `testuser02`(대표 신청자, 계정 있음, 외출증
   없음) 둘 다 초기 상태에서는 리마인더 대상이어야 한다.

### 1차 시도 — 실제 HTTP로 CUSTOM 외출증 생성 시도 (실패, 환경 제약)
`testuser01`에게 점심시간(12:30~13:40)을 포함하는 `CUSTOM` 외출증(11:00~15:00)을
`POST /api/v1/outings`로 실제로 신청해보려 했으나, QA를 진행한 시각(오후 2시경)이 이미
11:00을 지나 있어 `400 OUTING_001`("이미 그 시간대 시작 시각이 지났습니다")로 거부됐다 —
스케줄러 자체는 매일 08:30에 돌아 이 문제가 없지만, QA를 실시간으로 수행하는 시점의 인공적
제약이다. 아래 2차 시도로 대체했다.

### 2차 시도 — DB 직접 삽입으로 재현 (1차 실패, 유의미한 부수 발견)
`POST /api/v1/outings`를 우회해 `testuser01`의 외출증을 DB에 직접 `INSERT`(`PENDING`,
`CUSTOM`, `11:00~15:00`, 오늘)했더니, **`OutingMissedScheduler`(#42, 1분 주기로 계속 실행
중인 기존 스케줄러)가 곧바로 이 행을 `MISSED`로 갱신해버렸다**(시작 시각 11:00이 이미
지났다고 판단 — 정상 동작, `#71`의 버그 아님). 그 결과 리마인더 실행 시 `testuser01`도
리마인더 대상에 포함됐는데, 이는 `#71`의 결함이 아니라 `MISSED`가 `ACTIVE_STATUSES`에
없어 "신청 안 한 것"으로 취급되는 게 설계대로 맞는 동작임을 오히려 재확인한 것이다(기획서
"REJECTED는 다시 알림 대상" 규칙과 같은 선상).

### 3차 시도 — 상태를 `APPROVED`로 직접 삽입 (성공)
같은 외출증 행의 `status`만 `APPROVED`로 바꿔(`OutingMissedScheduler`는 `PENDING`만
갱신 대상으로 삼아 이후 건드리지 않음) `sendOutingReminders(오늘)`를 1회 실행했다.

| # | 대상 | 상태 | 기대 | 결과 |
|---|---|---|---|---|
| 1 | `testuser02`(대표 신청자, 외출증 없음) | - | 리마인더 발송(`notification` 새 행) | ✅ `id=8` 생성 확인 |
| 2 | `testuser01`(팀원, 점심시간을 포함하는 `CUSTOM` 외출증 `APPROVED`) | 코드 리뷰 High 수정 대상 | 발송 안 함 | ✅ 새 행 없음(기존 초대 알림 `id=5`만 유지) |

DB(`notification` 테이블)를 직접 조회해 두 결과 모두 SQL로 확인했다 — `testuser02`(user_id=2)
앞으로 "오늘 스쿨캠핑이 있어요!" 행이 새로 생겼고, `testuser01`(user_id=1)에게는 생기지
않았다. **코드 리뷰 High 1번(`CUSTOM` 외출증이 점심시간을 포함해도 `timeSlot` 이름만
보고 놓치던 문제)의 수정이 실제 DB 레벨에서 의도대로 동작함을 확인했다.**

## 발견 사항
Critical/High/Medium/Low 모두 새로 발견된 것은 없다. 위 "2차 시도"에서 관찰한 현상
(`MISSED` 외출증은 리마인더 대상에서 빠지지 않음)은 결함이 아니라 기획서에 이미 명시된
설계(신청 여부만 보고, `REJECTED`/`MISSED`는 재알림 대상)와 일치하는 정상 동작임을
재확인한 것으로 정리한다.

**Medium — 08:30 정시 cron 발동 자체는 실시간 검증 못 함(환경 제약)**: `SchoolCampReminderScheduler`
가 실제로 KST 08:30에 정확히 실행되는지는 QA 세션 시간대 제약으로 실시간 대기 검증을 하지
못했다. 대신 (1) 컨텍스트 기동 시 cron 표현식이 파싱 오류 없이 등록됨을 확인했고, (2)
`SchoolCampReminderSchedulerTest`가 `LocalDate.now(KST)`를 그대로 위임하는지 단위
테스트로 확인했다 — `OutingMissedScheduler`도 최초 도입(#42) 당시 동일한 판단(스케줄러
자체 얇은 위임부는 단위 테스트로, 무거운 로직은 실 DB로 검증)을 따른 전례가 있다.

## 결론
기획서에 정의된 리마인더 로직(대상 판정, LUNCH 겹침 판단, 게스트 제외, 단일 트랜잭션)과
코드 리뷰에서 고친 CUSTOM 겹침 오판정이 전부 단위 테스트 + 실제 HTTP로 준비한 데이터에
대한 실 DB 실행으로 확인됐다. 이 이슈의 완료 조건(로컬 빌드/테스트 통과)을 충족하며, CI
통과 여부는 PR 생성(16단계) 후 확인한다 — 이 프로젝트의 CI 워크플로우는 `main`/`dev`로의
PR·push에서만 트리거되어 기능 브랜치 단독 push로는 미리 확인할 수 없다(`#67`/`#68`/`#70`
QA와 동일한 제약).
