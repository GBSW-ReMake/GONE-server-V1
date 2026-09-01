# #120 범용 이벤트 스케줄링 인프라 — QA 결과

관련 기획서: [120-common-scheduled-task-infra.md](./120-common-scheduled-task-infra.md)
코드 리뷰 결과: [120-common-scheduled-task-infra-code-review.md](./120-common-scheduled-task-infra-code-review.md)

## 검증 환경
- 로컬 MySQL(Windows `MySQL80` 서비스, `gone` 스키마) + WSL Ubuntu에서 `redis-server
  --daemonize yes`로 기동한 로컬 Redis
- `application-dev.yml`(gitignore 대상, 로컬 전용 파일)에 `conduct.demerit-threshold: 10`이
  빠져 있어 컨텍스트 로딩 자체가 실패하는 문제를 발견 — #117/#118(conduct 도메인) 병합 후
  로컬 파일이 갱신되지 않아서였다. #120 범위 밖의 로컬 환경 문제라 저장소 코드는 건드리지
  않고 로컬 파일에만 값을 추가해 우회했다.
- `./gradlew bootRun`으로 실서버 기동(포트 9090)

## 로컬 빌드/테스트 결과
- `./gradlew build`(compileJava/Test, test, checkstyleMain/Test 포함) 통과
- 전체 테스트 **509개 통과**(#120이 추가한 16개 포함), 실패 0건
- CI는 PR 생성 후 확인 예정(이 저장소 CI는 `pull_request`/`push` 대상이 `main`/`dev`/
  `staging`뿐이라 feature 브랜치 push 시점에는 실행되지 않음)

## 실서버 검증

이 이슈는 새 HTTP 엔드포인트가 없는 백그라운드 인프라라, 엔드포인트별 표 대신 실제
폴링 루프/등록·취소 로직이 실 MySQL/실 Spring 컨텍스트에서 동작하는지를 확인했다.

### 1. Flyway 마이그레이션 실적용 확인
서버 기동 로그에서 `scheduled_task` 테이블이 실제로 생성되고 스키마 버전이 최신임을 확인.
```text
Current version of schema `gone`: 20260901104142
Schema `gone` is up to date. No migration necessary.
```
`DESCRIBE scheduled_task`로 컬럼 구성이 마이그레이션 SQL과 정확히 일치함을 직접 확인.

### 2. 폴링 루프 실동작 확인
핸들러가 등록되지 않은 임의의 `task_type`(`QA_SMOKE_TEST`)으로 이미 기한이 지난
(`next_attempt_at` = 1분 전) `PENDING` 행을 직접 INSERT한 뒤 서버 로그를 관찰.
- 약 6초 후 `ScheduledTaskExecutor`가 이 행을 집어가 "등록된 ScheduledTaskHandler가
  없습니다(taskType=QA_SMOKE_TEST)" 경고를 남김 → **✅** (Runner→Executor 연결, 실제
  DB 조회, 핸들러 매핑 조회까지 실제 컨텍스트에서 정상 동작)
- 이후 10초 간격으로 동일한 경고가 반복(`status`가 `PENDING`으로 남아 매 틱 다시 집힘) →
  **✅** (fixedDelay=10_000 설계대로, 처리되지 않은 task가 무한정 스킵되지 않고 계속
  재시도 대상에 남음)
- 이 과정에서 다른 스케줄러(#42 `OutingMissedScheduler`, schoolcamp 리마인더 등)나
  나머지 애플리케이션 로직에 예외/영향 없음 → **✅** (기존 스케줄러와 공존 확인)

### 3. `schedule()` DONE 재등록 경로(코드 리뷰 High #1 대응) — 실 DB로 수정 결과 검증
코드 리뷰에서 지적된 "DONE으로 끝난 task를 같은 (taskType, referenceId)로 재등록하면
유니크 제약 위반이 날 수 있다" 문제의 수정(`delete()` 뒤 `flush()` 추가)이 실제로
동작하는지 실 DB 기반 통합 테스트(`ScheduledTaskServiceIntegrationTest`, 실 MySQL 대상
`@SpringBootTest`)로 검증했다. (`flush()` 수정은 코드 리뷰 단계에서 이미 적용한 뒤
이 테스트를 작성했기 때문에, "수정 전에는 정말 실패했는지"는 별도로 재현하지 않았다 —
Hibernate의 IDENTITY 즉시 INSERT/지연 DELETE 순서 문제는 잘 알려진 동작이라 코드
리뷰의 분석 자체는 신뢰하고, 여기서는 수정된 코드가 실제로 의도대로 동작하는지만
확인했다.)
- 첫 실행 시 아래 "발견된 문제" 항목(`created_at` NOT NULL 위반)에 걸려 실패 — `flush()`
  수정과는 무관한 별개의 버그였다.
- `@CreationTimestamp` 추가로 그 문제를 고친 뒤 재실행 → 유니크 제약 위반 없이 재등록
  성공, 새 행이 다른 PK로 생성되고 `status=PENDING` 확인 → **✅**

## 발견된 문제

QA 과정에서 코드 리뷰 단계까지 잡히지 않았던 새 결함 1건을 추가로 발견해 즉시 수정했다.

- **High**: `ScheduledTask.createdAt`에 `@CreationTimestamp`가 빠져 있었다. 마이그레이션은
  `created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`로 DB 기본값을 정의했지만,
  Hibernate는 값이 없는 필드도 INSERT 컬럼 목록에 명시적으로 포함시켜 `NULL`을 보내므로
  DB의 `DEFAULT`가 적용되지 않고 `Column 'created_at' cannot be null`로 INSERT 자체가
  실패했다. 유닛 테스트는 리포지토리를 mock하므로 이 문제를 잡지 못했고, 위 "3. `schedule()`
  DONE 재등록 경로" 실 DB 테스트를 처음 돌렸을 때 이 형태로 드러났다. `Outing` 엔티티는
  이미 `@CreationTimestamp`로 동일한 문제를 피하고 있었는데 이 엔티티에서는 누락했다.
  **반영 완료**(`ScheduledTask.java`에 `@CreationTimestamp` 추가, 실 DB 재검증 통과).

## 남은 절차
- 이 이슈는 신규 HTTP 엔드포인트가 없어 15단계(Postman)/17단계(Notion) 반영 대상이 아니다
  (기획서 "API 설계 6원칙 체크" 절에 이미 명시됨).
- `RetryPolicy.DEFAULT`의 실제 소비자 검증(성공/실패/백오프/FAILED 전이가 실제 알림 발송과
  맞물려 동작하는지)은 이 이슈에 실제 핸들러 구현체가 없어 확인할 수 없다 — #99가
  `OutingTimeoutScheduledTaskHandler`를 구현하는 시점에 그 QA에서 이어서 검증해야 한다.
- 16단계: 보스 최종 확인 후 PR 생성
