# #99 외출증 복귀 리마인더 스케줄러 — QA 결과

관련 기획서: [99-outing-return-reminder.md](./99-outing-return-reminder.md)
코드 리뷰 결과(9단계): [99-outing-return-reminder-code-review.md](./99-outing-return-reminder-code-review.md)
(코드 리뷰 지적 5건은 QA 시작 전에 이미 전부 수정 완료 — 위 문서 상단 "반영 현황" 참고)

## 1. QA/QC (10단계)

### 자동 검증
- `./gradlew checkstyleMain checkstyleTest`: **통과** (경고 없음)
- `./gradlew build`(테스트 포함): **통과** — 전체 577개 테스트, 실패/에러 0건(코드 리뷰
  반영으로 추가한 `ScheduledTaskExecutorTest` 2건, `OutingServiceTest` 2건 포함)
- GitHub Actions CI: `.github/workflows/ci.yml`이 `push`/`pull_request`를 `main`/`dev`/
  `staging`에만 트리거하도록 되어 있어, feature 브랜치 상태에서는 실행되지 않는다 — #43
  QA와 동일하게 **PR 생성 후 확인**한다(16단계 이전에 재확인 필요).

### 수동 검증 (로컬 dev 서버, `localhost:9090`, dev 프로필, 로컬 MySQL/Redis 기동)
DB가 빈 상태였어서(이전 QA 데이터 없음) 계정을 직접 준비했다 — 아직 #15(역할 부여 API)가
없어 DISCIPLINE 역할은 SQL로 직접 부여했다:
- `gbsw` 테이블에 학생/선생님 2명/discipline 역할용 계정 1명분 명단을 직접 INSERT
- 휴대폰 인증: dev 프로필은 `ConsoleSmsSender`가 인증번호를 서버 콘솔에 출력하므로, 그
  값을 그대로 사용해 인증 → 회원가입 진행
- `qastudent1`(STUDENT, id=370), `qateacher1`(TEACHER, id=371, 이번 외출증의 담당
  선생님), `qadiscipline1`(TEACHER, id=372)로 가입. `user_role`에
  `INSERT INTO user_role (user_id, role_id) VALUES (372, 5)`로 372에 DISCIPLINE을
  직접 부여
- 학교 좌표는 `application-dev.yml`의 더미값 `0.0`/`0.0`(반경 200m)을 그대로 "학교 반경
  안"으로 사용(#43 QA와 동일)

| # | 케이스 | 기대 | 결과 |
|---|---|---|---|
| 1 | 마감 2분 뒤인 `CUSTOM` 외출증 신청 → 승인 → 출발 보고 | `scheduled_task`에 `task_type=OUTING_TIMEOUT`, `reference_id`=outing PK, `scheduled_at`=종료시각, `interval_seconds=300`, `end_at`=종료시각+3시간 row 생성 | ✅ (id=99, `scheduled_at`/`next_attempt_at`=14:31:00, `end_at`=17:31:00) |
| 2 | 마감(14:31:00) 통과 후 폴링 처리 | 학생/담당 선생님/DISCIPLINE 전원에게 알림 저장, 마감~발송 간 지연이 ±10초 이내 | ✅ 14:31:07에 user 370/371/372 앞으로 각 1건 저장 — 지연 7초 |
| 3 | 5분 뒤(14:36:07) 재폴링 | 같은 3명에게 리마인더 재발송, `scheduled_task.next_attempt_at`이 다시 +5분 갱신 | ✅ 14:36:07~08에 3명 각 1건 추가 저장(총 6건), `next_attempt_at`=14:41:07로 갱신, `failure_count=0` |
| 4 | **코드 리뷰 4번 재현**: 담당 선생님(371)에게 DISCIPLINE 역할도 추가 부여한 뒤 3번 케이스의 다음 재발송 관찰 | 371이 같은 알림을 두 번 받지 않고 정확히 1건만 수신 | ✅ 위 3번 결과에서 371은 정확히 1건만 수신(수정 전이었다면 담당 선생님 발송 1건 + DISCIPLINE 목록 발송 1건으로 2건이었을 자리) |
| 5 | 도착 보고(`returnOuting`) | 해당 `scheduled_task` row가 즉시 삭제됨(취소), 이후 더 이상 리마인더가 오지 않음 | ✅ id=99 row 삭제 확인. 별도의 두 번째 외출증(location 테스트용, id=100)도 도착 보고 즉시 삭제 확인 |
| 6 | `DEPARTED` 상태에서 학교 반경 안(`0,0`) 위치 핑 전송 | "도착 확인이 필요해요" 알림 즉시 저장 | ✅ 핑 전송 8초 후 저장 확인 |
| 7 | 5분 스로틀 안에 같은 반경에서 핑 2회 연속 전송 | 추가 알림 없음(스로틀 유지) | ✅ 알림 테이블에 추가 행 없음 |
| 8 | `GET /api/v1/notifications`(#119)로 실제 저장된 제목/본문 조회 | 한글 텍스트가 깨지지 않고 정상 표시 | ✅ ("외출 시간이 지났습니다", "도착 확인이 필요해요" 등 정상) |

최종 상태: `scheduled_task` 0건(두 외출증 모두 정상 취소), `notification` 7건(타임아웃
리마인더 최초+재발송 3+3, 위치 기반 1) — 기대와 일치.

### Medium — 환경 제약으로 직접 재현 못 한 항목(코드 리뷰 1/2/3/5번 관련)
1. **claim() 자체의 예외 전파 방지(코드 리뷰 1번)**: 실제 DB 순간 장애(커넥션 풀 고갈 등)를
   폴링 도중 인위적으로 발생시킬 방법이 로컬 수동 QA로는 없다.
   `ScheduledTaskExecutorTest.doesNotPropagateWhenClaimThrows`(mock으로 `claim()`
   호출이 예외를 던지게 해 검증)로 대체 확인했다.
2. **recordSuccess() 실패가 handler 실패로 오인되지 않음(코드 리뷰 2번)**: 마찬가지로
   handler 성공 직후에만 DB가 실패하는 타이밍을 실서버로 통제할 수 없다.
   `ScheduledTaskExecutorTest.doesNotRecordFailureWhenRecordSuccessThrows`(연속된
   `findById` 호출 중 두 번째만 실패하도록 스텁)로 대체 확인했다.
3. **`lastLocationReminderAt` 메모리 누수 정리(코드 리뷰 3번)**: 애플리케이션 인메모리
   상태라 외부에서 직접 관찰할 방법이 없다(DB로 노출되지 않음).
   `OutingServiceTest.resendsNotificationAfterThrottleEntryClearedByTimeoutCheck`로
   대체 확인했다 — outing이 사라진 것으로 감지된 뒤 스로틀 항목이 지워져 같은 간격 안에서도
   다시 알림이 나가는 것을 검증한다.
4. **위치 핑 스로틀의 get-then-put 경합(코드 리뷰 5번)**: curl 기반 순차 호출로는 두 요청을
   밀리초 단위로 정확히 겹치게 만들 수 없다(#43 QA의 낙관적 락 경합 재현 불가 판단과 동일한
   제약). 코드 리뷰 시점에 `ConcurrentHashMap.compute(...)`로의 전환 자체는 코드로
   확인했고, 회귀 여부는 위 케이스 6/7(순차 호출)로 기존 동작이 깨지지 않았음을 확인했다.

### 반영(2026-09-04) — CodeRabbit 지적 A(PR #130) 대응
위 4번의 한계(순차 curl 호출로는 동시 경합을 재현 못 함)는 CodeRabbit 자동 리뷰(PR #130)에서
같은 지적(A)으로 다시 나왔다. 이후 스로틀 자료구조 자체가 `ConcurrentHashMap.compute(...)`
에서 Redis `saveIfAbsent`(원자적 SETNX+TTL)로 바뀌면서(#99 CodeRabbit 지적 C/D 대응),
`OutingLocationReminderConcurrencyIntegrationTest`(신규)를 추가해 실 DB + 실 Redis로 진짜
동시 요청 20건을 같은 외출증에 보내 도착 확인 알림이 정확히 1건만 저장되는지 검증했다 —
`onlyOneNotificationSentWhenConcurrentPingsWithinSchoolRadius` 테스트로 통과 확인.
curl 기반 수동 QA로는 여전히 불가능한 검증이라 자동화 통합 테스트로 대체했다(위 4번과 같은
판단 근거).

## 2. 결론

Critical/High 없음. 계획된 8개 케이스(scheduled_task 등록/타임아웃 리마인더 최초 발동/
5분 재발송/담당 선생님-DISCIPLINE 중복 제거/도착 시 취소/위치 기반 즉시 알림/위치 기반
스로틀/알림 내용 정합성) 모두 로컬 dev 서버에서 기대대로 동작함을 확인했다(staging/
production 검증 아님). 특히 오늘 코드 리뷰에서 고친 5건 중 재현 가능한 4번(담당 선생님·
DISCIPLINE 중복 제거)은 실서버로 직접 재확인했고, 나머지 4건(claim 예외 전파, recordSuccess
실패 오인, 메모리 누수 정리, get-then-put 경합)은 타이밍/인메모리 상태 특성상 실서버 재현이
불가능해 단위 테스트로 대체 확인했다(#43/#120 QA의 선례와 동일한 판단).

±10초 정밀도(7초 지연)와 5분 재발송 간격(정확히 5분)이 기획서 전제와 일치함을 확인했고,
도착 처리 시 `scheduled_task` 취소가 두 케이스 모두에서 원자적으로 반영됨을 확인했다.

추가 조치 없이 다음 단계(문제사항 보고)로 진행 가능하다고 판단한다.
