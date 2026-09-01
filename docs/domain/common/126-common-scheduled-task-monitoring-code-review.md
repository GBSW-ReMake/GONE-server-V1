# #126 scheduled_task 모니터링/재시도 API — 코드 리뷰 결과

리뷰 범위: 브랜치 `feat/#126-scheduled-task-monitoring`(`dev` 기준). `common/schedule`
패키지에 추가된 컨트롤러/서비스/DTO/에러코드/엔티티 변경, 대응 단위 테스트. 컨텍스트가
격리된 별도 에이전트(`code-review` 스킬)에게 위임해 진행했다.

## 요약
- Critical: 없음
- High: 2건(#1, #2) — 반영 완료

**반영 내역(2026-09-01):** 둘 다 승인된 기획서의 계약(엔드포인트/스키마/정책)을 바꾸는
변경이 아니라 구현 세부사항의 결함 수정이라 별도 재승인 없이 즉시 반영했다.

---

### 1. 🟠 High — `delete()`의 `existsById`→`deleteById` 사이 레이스로 500이 날 수 있음

**문제**: `ScheduledTaskAdminService.delete()`(수정 전)는 `existsById(id)`로 존재를 확인한
뒤 `deleteById(id)`를 호출했다. 이 두 호출 사이에 다른 요청(예: 도메인 코드의
`ScheduledTaskService.cancel()`)이 같은 행을 먼저 지우고 커밋하면, `deleteById()`가 Spring
Data 표준 동작대로 `EmptyResultDataAccessException`을 던진다. `GlobalExceptionHandler`에는
이 예외 타입에 대한 전용 핸들러가 없어(`CustomException`/`MethodArgumentNotValidException`/
`DataIntegrityViolationException` 등만 처리) `Exception.class` catch-all로 떨어져, 관리자는
이 엔드포인트가 원래 의도한 404 `SCHEDULE_002` 대신 500을 받는다.

**해결 방안**:
1. `existsById` 사전 확인을 없애고 `deleteById()`를 바로 호출한 뒤,
   `EmptyResultDataAccessException`을 잡아 `TASK_NOT_FOUND`로 변환한다 — DB 왕복이 하나
   줄고, 레이스 자체를 "확인 후 행동"이 아니라 "행동 후 결과 해석"으로 바꿔 근본적으로
   막는다. **채택.**
2. `GlobalExceptionHandler`에 `EmptyResultDataAccessException` 전용 핸들러를 전역으로
   추가한다 — 이 문제가 다른 도메인의 유사 코드에도 잠재해 있을 수 있어 근본적이지만,
   이 이슈 범위를 넘어서는 전역 변경이라 별도 검토가 필요하다.

**반영**: 방안 1을 최초 적용했으나, **10단계 QA(실서버)에서 방안 1 자체가 이 프로젝트의
Spring Data JPA 버전에서는 작동하지 않는다는 게 드러났다** — `deleteById()`가 대상이 없어도
`EmptyResultDataAccessException`을 던지지 않고 조용히 성공해, 존재하지 않는 id를 삭제
요청해도 404가 아니라 200이 반환됐다(방안 1의 전제였던 "Spring Data 표준 동작"이 이
버전에는 해당하지 않았다). 최종적으로는 `findById`로 먼저 존재를 확인한 뒤 엔티티 기준으로
삭제하는 방식으로 되돌렸다 — `retry()`가 이미 쓰는 것과 같은 findById-then-mutate
패턴이라 일관성도 맞고, 확인과 삭제 사이의 좁은 레이스가 있어도 엔티티 기준 삭제는 대상이
이미 없으면 조용히 0행으로 끝나 안전하다(500으로 이어지지 않음). 상세는
[QA 결과 문서](./126-common-scheduled-task-monitoring-QA.md) 참고. 회귀 방지로 컨트롤러
테스트에 "존재하지 않는 id 삭제 → 404" 케이스를 추가했다.

---

### 2. 🟠 High — `/api/v1/scheduled-tasks/**`가 `SecurityConfig`의 인증 필요 경로에서 빠짐

**문제**: `SecurityConfig.securityFilterChain()`의 `authorizeHttpRequests`는 인증이 필요한
경로를 도메인별로 명시적으로 나열하는데(`/api/v1/outings/**`, `/api/v1/conduct-records/**`
등), `/api/v1/scheduled-tasks/**`가 이 목록에 없어 `anyRequest().permitAll()`로 떨어졌다.
그 결과 미인증 요청도 필터 체인은 통과하고(인증 없이도 컨트롤러까지 도달), 메서드 레벨의
`@PreAuthorize("hasRole('ADMIN')")`에서만 걸려 401 `COMMON_002`가 아니라 403
`COMMON_003`이 반환됐다 — 이 프로젝트의 다른 모든 인증 필요 엔드포인트와 다른 응답
계약이다. 실제 접근 차단 자체는 됐지만(`@PreAuthorize`가 방어선 역할을 함), 방어선이
두 겹이어야 할 곳이 한 겹만 작동한 상태였다 — `@PreAuthorize`를 실수로 빠뜨린 향후
엔드포인트가 이 패키지에 추가되면 완전히 뚫릴 수 있는 구조였다.

**해결 방안**:
1. `authorizeHttpRequests`에 `.requestMatchers("/api/v1/scheduled-tasks/**").authenticated()`
   한 줄을 추가한다 — 기존 컨벤션과 동일한 패턴, 최소 변경. **채택.**
2. `anyRequest()`의 기본값 자체를 `permitAll()`에서 `authenticated()`로 바꾸고 예외 경로만
   `permitAll`로 열거한다(화이트리스트→블랙리스트 전환) — 이 클래스의 근본적인 방어 심도
   문제를 한 번에 해결하지만, 기존 모든 엔드포인트의 인증 요구사항을 다시 감사해야 하는
   범위가 큰 변경이라 이번 이슈 범위를 넘어선다.

**반영**: 방안 1 적용(`SecurityConfig.java`). 새 컨트롤러 테스트(`returns401WithoutToken`)로
수정 전 실제로 403이 나는 것을 재현하고, 수정 후 401로 바뀌는 것을 확인했다.

## 확인한 항목 중 문제 없었던 것
- `retry()`/`getTasks()`/`getStats()`는 위와 같은 TOCTOU 문제가 없음 — `retry()`는
  `findById` 후 같은 트랜잭션 안에서 엔티티를 직접 수정(JPA 변경 감지)하지, 별도 존재
  확인 후 별개 쓰기 호출을 하지 않는다.
- `ScheduledTaskRepository.findWithFilters`/`countByStatus` 쿼리는 #120이 만든
  `idx_scheduled_task_due (status, next_attempt_at)` 인덱스를 그대로 활용.
- 4개 엔드포인트 모두 `@PreAuthorize("hasRole('ADMIN')")` 일관 적용 확인.
- 응답 DTO가 엔티티 컬럼을 그대로 노출하고 재해석하지 않는다는 기획서 원칙과 실제 구현
  일치.
