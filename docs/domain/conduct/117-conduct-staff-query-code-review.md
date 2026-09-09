# 교사·선도부·관리자 상/벌점 조회 코드 리뷰 (이슈 #117)

> 격리 에이전트 리뷰 결과 (diff: `dev...feat/#117-conduct-staff-query`, 기획서: `117-conduct-staff-query.md`)

## 리뷰 결과

지적 사항 없음. 검토 항목별 판정:

| 항목 | 판정 | 근거 |
|---|---|---|
| `studentNickname` 필드 — `user.getName()` 매핑 | ✅ 정상 | `getName()`이 실제 별명 반환, Javadoc과 일치 |
| 날짜 범위 검증 — `hasFrom != hasTo` | ✅ 정상 | 단방향 제공·역순 모두 차단 |
| `dateFrom.isAfter(dateTo)` 호출 시 NPE 위험 | ✅ 정상 | `hasFrom && hasTo` 조건 뒤에서만 호출, 비null 보장 |
| 페이지 크기 경계 — `size < 1 \|\| size > 100` | ✅ 정상 | [1, 100] 범위 정확히 검증 |
| `LEFT JOIN FETCH r.student` — nullable studentUserId | ✅ 정상 | `ManyToOne` fetch join, 행 수 팽창 없음 |
| 테스트 커버리지 | ✅ 정상 | `getStaffSummary` 3개(정상·CONDUCT_005·CONDUCT_006), `getRecords` 6개(전체·특정·페이지·크기·날짜 단방향·날짜 역순) |
