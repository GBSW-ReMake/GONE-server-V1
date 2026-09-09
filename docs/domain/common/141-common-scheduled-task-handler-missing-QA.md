# #141 ScheduledTaskExecutor 핸들러 미등록 시 즉시 FAILED 격리 — QA 결과

관련 기획서: [141-common-scheduled-task-handler-missing.md](./141-common-scheduled-task-handler-missing.md)
관련 코드 리뷰: [141-common-scheduled-task-handler-missing-code-review.md](./141-common-scheduled-task-handler-missing-code-review.md)
(9단계 코드 리뷰 지적 사항 없음 — 이 문서는 QA에서 새로 확인한 내용만 다룬다)

## 검증 방법/범위
이 이슈는 새 HTTP 엔드포인트가 없는 내부 로직 수정이라(기획서 "개요/목적" 참고),
엔드포인트별 정상/에러 케이스 검증 대신 아래 순서로 확인했다.

1. `./gradlew checkstyleMain checkstyleTest` — 통과.
2. `./gradlew test --tests ScheduledTaskExecutorTest` — 변경된 테스트 포함 4건 전부 통과.
   (PR #145 CodeRabbit 지적 반영 후) `./gradlew test --tests
   ScheduledTaskExecutorIntegrationTest` — 신규 폴링 제외 테스트 포함 4건 전부 통과.
3. `./gradlew build`(checkstyle + 전체 테스트 607건) — 1건 실패
   (`OutingLocationReminderConcurrencyIntegrationTest.onlyOneNotificationSentWhenConcurrentPingsWithinSchoolRadius`,
   `RedisConnectionFailureException: Unable to connect to Redis`). 이번 변경과 무관한
   기존 테스트다 — 스택트레이스에 `schedule` 패키지 코드가 전혀 없고, 원인은 로컬
   환경에 Redis가 떠 있지 않아서다(outing 위치 알림 동시성 테스트가 Redis에 의존).
   `common/schedule` 관련 테스트 전부(단위+통합)는 이 실패와 무관하게 전부 통과했다.
4. **실제 DB/트랜잭션 검증**: `@SpringBootTest` 컨텍스트에서 `ScheduledTaskService.schedule()`로
   존재하지 않는 taskType(`QA_141_UNKNOWN_TYPE`)의 task를 실제로 등록한 뒤
   `ScheduledTaskExecutor.execute()`를 직접 호출해, `ScheduledTaskExecutionStore`의 실제
   `REQUIRES_NEW` 트랜잭션 경로를 통해 DB에 반영되는지 확인했다.
   - 실행 직후 재조회 결과: `status=FAILED`, `failureCount=1`,
     `lastError`에 taskType 포함(`"등록된 ScheduledTaskHandler가 없습니다:
     taskType=QA_141_UNKNOWN_TYPE"`).
   - `ScheduledTaskRepository.findDueTaskIds(PENDING, now+10분)` 조회 결과에 해당
     task id가 더 이상 포함되지 않음 — 다음 폴링 틱부터 재claim되지 않는다는 기획서
     주장이 실제 DB 조회로 확인됨.
   - (PR #145 CodeRabbit 지적 반영) 이 검증은 최초에는 QA 목적의 임시 테스트 파일로
     실행 후 삭제했었으나, "폴링 제외"라는 핵심 동작이 커밋된 테스트로 고정돼 있지
     않다는 지적에 따라 `ScheduledTaskExecutorIntegrationTest`에
     `excludesTaskFromPollingAfterMarkedFailedForMissingHandler()`로 정식 반영했다 —
     handler 미등록 task를 실제로 등록·실행한 뒤 `findDueTaskIds` 결과에서 빠지는지까지
     같은 테스트에서 검증한다.
5. **CI(GitHub Actions) 확인은 이번 QA에서 생략했다.** 이 저장소 CI는 `dev`/`main`/`staging`
   대상 `pull_request` 또는 그 브랜치로의 `push`에서만 트리거되어, feature 브랜치 push만으로는
   확인할 수 없다. CI 확인용 draft PR을 열지 여부를 보스에게 확인했고, 보스가 명시적으로
   거부했다(불필요하다는 판단) — 16단계에서 실제 PR을 생성하면 그때 CI 결과가 자연히
   확인된다.

## 발견 사항
- **Critical/High**: 없음.
- **Medium**: 없음 (위 3번 Redis 테스트 실패는 이번 변경과 무관한 기존 환경 제약이라
  이 이슈의 발견 사항으로 집계하지 않는다).
- **Low**: 없음.

## 결론
handler 미등록 시 즉시 `FAILED`로 격리되고 이후 폴링에서 빠진다는 기획서의 핵심 동작을
실제 DB/트랜잭션 경로로 재현·확인했다. 코드 리뷰(9단계)와 관련 테스트(체크스타일,
`ScheduledTaskExecutorTest`, `common/schedule` 패키지 전체)는 통과했다.

다만 이 이슈의 완료 조건(Definition of Done: 로컬 빌드 전체 통과, CI 통과)은 아직
**충족되지 않았다** — `./gradlew build`가 1건 실패했고(위 3번, 이번 변경과 무관하다고
판단은 했으나 전체 빌드 자체는 실패), CI 확인은 이번 QA에서 생략했다. 두 항목 모두
16단계 PR 생성 후 실제 CI 결과로 확인되기 전까지는 이 이슈를 **완료로 처리하지 않고
보류(pending) 상태로 둔다.**
