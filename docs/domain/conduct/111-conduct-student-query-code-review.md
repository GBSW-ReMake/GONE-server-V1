# 상/벌점 학생 본인 조회 코드 리뷰 (이슈 #111)

> 격리 에이전트 리뷰 결과 (diff: `dev...feat/#111-conduct-student-query`, 기획서: `111-conduct-student-query.md`)

## 리뷰 결과

### 1. N+1 쿼리 — `getStudentRecords` 페이지 조회 시 teacher·category 지연 로딩
**심각도**: 🔴 Critical

**문제**: `ConductRecordRepository.findByStudentWithFilters`가 반환하는 `Page<ConductRecord>`에서 `teacher`·`category` 관계가 `FetchType.LAZY`로 선언되어 있다. `ConductStudentRecordResponse.from(record)` 변환 시 `record.getTeacher().getId()`·`record.getCategory().getId()`를 호출하면, 항목당 teacher 1건·category 1건 추가 쿼리가 발생한다. `size=100` 요청 시 1(목록) + 100(teacher) + 100(category) = 최대 201개 쿼리가 발생하며, 기획서의 "DB 레벨 페이지네이션" 의도와 역배된다.

**해결 방안 A**: JPQL에 `LEFT JOIN FETCH` 추가 ← **채택**
- 장점: 단일 쿼리로 해결. `ManyToOne`이므로 행 수 팽창 없어 `Pageable`과 충돌 없음.
- 단점: 쿼리 문자열이 길어진다.

**해결 방안 B**: `@EntityGraph(attributePaths = {"teacher", "category"})` 추가
- 장점: 선언적 방식으로 간결.
- 단점: `@Query`와 함께 쓰는 경우 Spring Data 버전에 따라 동작 차이 있음. 커스텀 JPQL과 함께 사용 시 검증이 더 필요하다.

→ **A 채택 후 수정 완료**. 쿼리에 `LEFT JOIN FETCH r.teacher LEFT JOIN FETCH r.category` 추가.

---

### 2. LazyInitializationException 위험 — 트랜잭션 내 DTO 변환
**심각도**: ℹ️ INFO

**에이전트 지적**: Controller에서 변환 시도 시 트랜잭션 외에서 Lazy 로딩 예외가 발생할 수 있다.

**검토 결과**: 오탐. `getStudentRecords`가 `@Transactional(readOnly = true)` 범위 내에서 완전히 변환된 `PageResponse<ConductStudentRecordResponse>`를 반환한다. Controller는 변환 로직 없이 결과를 그대로 `ApiResponse.success`에 넘긴다. 수정 불필요.

---

### 3. 날짜 형식 오류 응답
**심각도**: ℹ️ INFO

**에이전트 지적**: `dateFrom`에 `2026-08-01`처럼 형식 오류 값이 오면 `CONDUCT_008`이 아니라 공통 `DateTimeParseException` 400으로 처리된다.

**검토 결과**: 기획서에서 형식 오류와 범위 오류를 구분하지 않으며, 두 경우 모두 `400`을 반환한다는 점에서 동작은 올바르다. 메시지 차이가 필요하면 별도 이슈에서 `@InitBinder` 커스텀 처리를 검토한다. 이번 범위 밖. 수정 불필요.
