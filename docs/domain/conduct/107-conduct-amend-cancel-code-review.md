# 상/벌점 정정·취소 코드 리뷰 (이슈 #107)

> 격리 에이전트 리뷰 결과 (diff: `dev...feat/#107-conduct-amend-cancel`, 기획서: `107-conduct-amend-cancel.md`)

## 리뷰 결과

### 1. `amendConduct` — 양쪽 필드가 모두 null일 때 silent no-op
**심각도**: 🟠 High

**문제**: `ConductAmendRequest(null, null)` 전송 시 아무 필드도 갱신되지 않지만 `200 OK`를 반환한다. 클라이언트가 실수로 빈 바디를 보내도 오류가 없어 혼란을 준다.

**해결 방안 A**: 서비스 초입에 두 필드 모두 null이면 `INVALID_REQUEST` 예외를 던진다 ← **채택**
- 장점: 명시적 오류. 구현 단순.
- 단점: 새 에러 코드 없이 `COMMON_001` 재사용 — 메시지가 약간 일반적.

**해결 방안 B**: `ConductAmendRequest`에 커스텀 `@AssertTrue` 유효성 검사 추가
- 장점: 컨트롤러 레이어에서 400을 반환해 서비스 로직에 도달하기 전 차단.
- 단점: 커스텀 validator 클래스 추가로 복잡도 증가. 기존 `@Valid` 패턴과 다름.

→ **A 채택 후 수정 완료**. `amendConduct` 서비스 메서드 초입에 양 필드 null 검증 추가. 테스트 케이스도 추가.

---

### 2. Lazy Loading 우려 — `@Transactional` 내 접근
**심각도**: ℹ️ INFO

**에이전트 지적**: `record.getTeacher().getId()` 및 `ConductRecordResponse.from(record)` 내 LAZY 관계 접근이 LazyInitializationException을 일으킬 수 있다.

**검토 결과**: 오탐. `amendConduct`/`cancelConduct` 모두 `@Transactional`이므로 메서드 실행 전체가 하나의 Hibernate 세션 내에 있다. LAZY 관계는 세션이 열린 상태에서 접근하므로 정상 동작. 수정 불필요.

---

### 3. 정정 이력 미보존
**심각도**: ℹ️ INFO

**에이전트 지적**: 정정 시 이전 값이 `updated_at` 갱신 외에 남지 않는다.

**검토 결과**: 기획서("정정 이력을 별도로 남길지 — 아직 결정 안 된 것")에 명시된 향후 검토 항목. 이번 범위 밖. 수정 불필요.

---

### 4. principal null 가능성
**심각도**: ℹ️ INFO

**에이전트 지적**: `@AuthenticationPrincipal`이 null일 수 있어 NPE 위험.

**검토 결과**: `@PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")` 가 메서드 진입 전에 인증/인가를 검증하므로 실제로는 null이 전달되지 않는다. 수정 불필요.
