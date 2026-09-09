# #141 ScheduledTaskExecutor 핸들러 미등록 무한 재시도 수정 — 코드 리뷰 결과

관련 기획서: [141-common-scheduled-task-handler-missing.md](./141-common-scheduled-task-handler-missing.md)

## 리뷰 범위/방법
- 대상: `git diff dev...HEAD` (fix/#141-scheduled-task-handler-missing 브랜치가 dev에서
  분기된 이후 전체 변경, 4 files changed, 106 insertions, 5 deletions):
  - `docs/domain/common/141-common-scheduled-task-handler-missing.md` (신규 기획서)
  - `src/main/java/com/remake/gone/common/schedule/service/RetryPolicy.java`
  - `src/main/java/com/remake/gone/common/schedule/service/ScheduledTaskExecutor.java`
  - `src/test/java/com/remake/gone/common/schedule/service/ScheduledTaskExecutorTest.java`
- 방법: 구현 과정의 대화/추론은 전달받지 않고, 기획서와 diff만으로 독립적으로 점검했다.
  - 기획서에서 "승인된 범위"(handler 미등록 시 `RetryPolicy.NON_RETRYABLE`로 즉시 FAILED
    격리, 새 상태값·API 변경 없음)를 먼저 파악한 뒤 diff가 그 범위를 벗어나는지 확인.
  - `docs/rules/code-style.md`, `docs/rules/test-convention.md`와 대조해 컨벤션 준수 여부
    확인.
  - 로직 확인을 위해 `ScheduledTask.markFailed()`(`entity/ScheduledTask.java:182-201`),
    `ScheduledTaskExecutionStore.recordFailure/claim/executeAndRecordSuccess`,
    `ScheduledTaskRepository.findDueTaskIds`(`status = :status` 조건, PENDING만 조회)를
    함께 읽고 트랜잭션 경계·상태 전이를 직접 추적.
  - `RetryPolicy`를 참조하는 나머지 6개 파일(`ScheduledTaskHandler`,
    `ScheduledTaskExecutionStore`, `ScheduledTask`, 관련 테스트 3개)을 grep해 다른
    handler/도메인 코드가 `RetryPolicy`의 특정 상수 존재를 가정하고 있지 않은지 확인 —
    각 handler는 자신의 `retryPolicy()`만 오버라이드하므로 `NON_RETRYABLE` 추가가 기존
    handler 동작에 영향을 주지 않음을 확인.
  - `./gradlew checkstyleMain checkstyleTest --rerun-tasks` 실행 — BUILD SUCCESSFUL
    (경고 0건, `maxWarnings=0` 통과).
  - `./gradlew test --tests ScheduledTaskExecutorTest --rerun-tasks` 실행 — BUILD
    SUCCESSFUL (변경된 테스트 포함 전체 통과).

## 결과: Critical/High/Medium/Low 없음

기획서 범위, 컨벤션, 로직 세 축 모두에서 지적 사항을 찾지 못했다. 근거:

- **범위 일치**: 기획서 "영향받는 기존 코드/테스트" 절이 명시한 3개 파일
  (`RetryPolicy.java`, `ScheduledTaskExecutor.execute()`, `ScheduledTaskExecutorTest`)
  외에 다른 파일 변경이 없다. 새 HTTP 엔드포인트, DB 스키마, 응답 포맷 변경 없음 — 기획서의
  "API 계약이나 DB 스키마 변경이 없다" 서술과 일치.
- **컨벤션 준수**:
  - `RetryPolicy.NON_RETRYABLE`은 기존 `DEFAULT`와 동일한 패턴(정적 상수 + Javadoc)으로
    추가됐고, 생성자 검증(`maxFailureCount <= 0` 거부, `requirePositiveWholeSeconds`)을
    그대로 통과한다 — `new RetryPolicy(1, Duration.ofSeconds(30), Duration.ofSeconds(30))`은
    `maxFailureCount=1 > 0`, `baseBackoff == maxBackoff`(30초, `maxBackoff >= baseBackoff`
    조건 충족)라 예외 없이 생성된다.
  - `ScheduledTaskExecutor.execute()`의 수정은 기존 `catch` 블록의
    `executionStore.recordFailure(taskId, now, e.getMessage(), handler.retryPolicy())`
    호출 패턴을 그대로 재사용했다(`ScheduledTaskExecutor.java:60-62` vs `:75`) — 새로운
    코드 경로를 만들지 않고 기존 실패 기록 경로에 합류시킨다는 기획서 설계를 그대로
    구현했다.
  - 주석은 WHY 위주(예: "재시도로 해결되지 않는 구성 오류이므로 NON_RETRYABLE로 즉시
    FAILED 격리")로 `code-style.md`의 "WHAT은 지양" 규칙에 부합한다.
  - 테스트는 `test-convention.md`대로 `@Nested`(`EdgeCases`) + 한글
    `@DisplayName` 구조를 유지했고, `DisplayName`을 새 동작("handler를 호출하지 않고
    즉시 FAILED로 격리한다")에 맞게 갱신했다 — 이름과 검증 내용(`status`, `failureCount`,
    `lastError`, `verify(handler, never()).handle(...)`)이 일치한다.
  - checkstyle(`checkstyleMain`/`checkstyleTest`, `google_checks.xml`,
    `maxWarnings=0`) 통과 확인 완료.
- **로직 결함 없음**:
  - `RetryPolicy.NON_RETRYABLE` 도입이 다른 handler에 영향을 주지 않는다 — 각 handler는
    `retryPolicy()`를 개별적으로 오버라이드하거나 `DEFAULT`를 쓰며, `NON_RETRYABLE`은
    `ScheduledTaskExecutor.execute()`의 `handler == null` 분기에서만 참조된다
    (`ScheduledTaskExecutor.java:60-62`). `Map<String, ScheduledTaskHandler> handlers`
    조회/매핑 로직 자체는 변경되지 않았다.
  - `ScheduledTask.markFailed(now, msg, maxFailureCount=1, ...)`는
    `failureCount++` 후 `1 >= 1`이 참이 되어 `nextAttemptAt` 갱신 없이 곧장
    `status=FAILED`로 전이한다(`ScheduledTask.java:185-193`). `findDueTaskIds`는
    `status = :status`(PENDING) 조건으로만 조회하므로(`ScheduledTaskRepository.java:41`)
    FAILED 전이 이후 다음 폴링 틱부터 이 task가 더 이상 걸리지 않는다 — 기획서가 주장한
    "무한 재시도/로그 스팸 종료" 효과가 실제 코드 경로로 성립한다.
  - `recordFailure`는 기존과 동일하게 `@Transactional(REQUIRES_NEW)`인
    `ScheduledTaskExecutionStore.recordFailure`를 그대로 호출하므로
    (`ScheduledTaskExecutor.java:60-62`), 새로운 트랜잭션 경계가 생기지 않는다 —
    `execute()` 자신은 여전히 `@Transactional`이 아니고, `claim`/`recordFailure`는 각각
    독립된 REQUIRES_NEW 트랜잭션이라는 클래스 설계(`ScheduledTaskExecutor.java:14-24`
    Javadoc)가 그대로 유지된다.
  - 새 테스트 `marksFailedImmediatelyWhenHandlerMissing`(`ScheduledTaskExecutorTest.java:
    188-200`)은 실제로 새 동작을 검증한다: `status == FAILED`, `failureCount == 1`,
    `lastError`에 `taskType`("UNKNOWN_TYPE") 포함, `handler.handle()`이 호출되지 않음을
    모두 단언한다 — 이전 테스트가 `status == PENDING`을 확인하던 것과 정반대 방향으로
    뒤집혔고, 세 단언 모두 스텁이 아닌 실제 `ScheduledTaskExecutionStore`
    (mock 아님, mock `ScheduledTaskRepository` 위에서 실행) 경로를 거쳐 검증되므로
    회귀를 잡을 수 있다.
  - `./gradlew test --tests ScheduledTaskExecutorTest --rerun-tasks` 실행 결과
    BUILD SUCCESSFUL로, 변경된 테스트를 포함해 기존 `HandleResult`/`HandleFailure`/
    `EdgeCases` 전체가 통과함을 확인했다 — `NON_RETRYABLE` 추가나 분기 수정이 기존
    시나리오(기본 backoff, handler별 정책 오버라이드, claim 실패/예외 등)를 깨지 않았다.

## 참고: 독립 검증 에이전트 교차 확인
`docs/rules/code-review-isolation.md`에 따라 별도로 실행한 `code-review` 스킬 기반
독립 리뷰 에이전트도 동일한 3개 파일 + 주변 컨텍스트(엔티티/스토어/리포지토리/기획서)를
검토한 뒤 findings 없음(빈 목록)으로 결론 내려, 이 문서의 결론과 교차 확인됐다.
