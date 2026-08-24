# 코드 리뷰 결과 — 이슈 #94 (상/벌점 부여)

- **리뷰어**: 격리 에이전트 (`caveman:cavecrew-reviewer`)
- **대상 브랜치**: `feat/#94-conduct-grant` vs `dev`

## 결과 요약

| 심각도 | 건수 |
|---|---|
| BLOCKER | 0 |
| MAJOR | 1 (수정 완료) + 1 (의도적 설계 유지) |
| MINOR | 1 (의도적 설계 유지) |

---

## 발견 사항

### [MAJOR — 수정 완료] `V16__add_conduct_record.sql`: `version` 컬럼 타입 불일치
- **파일**: `src/main/resources/db/migration/V16__add_conduct_record.sql`
- **문제**: `version INT`로 정의했으나 `ConductRecord.version`은 `Long` 타입. Hibernate `@Version`이 `BIGINT`을 기대함.
- **수정**: `INT` → `BIGINT`으로 변경. 커밋 `fix(conduct)` 반영.

### [MAJOR — 의도적 설계 유지] `ConductService`: 교사 미발견 시 `CommonErrorCode.NOT_FOUND` 사용
- **파일**: `src/main/java/com/remake/gone/conduct/service/ConductService.java`
- **리뷰어 의견**: `CONDUCT_NNN` 전용 에러 코드 추가 권장.
- **판단**: 교사는 인증된 사용자(`@AuthenticationPrincipal`에서 추출한 `userId`)이므로 DB에 없는 경우는 사실상 불가능한 방어 코드. `CommonErrorCode.NOT_FOUND`로 유지 — 도메인 에러 코드를 불필요하게 추가하지 않는다는 원칙(`api-design.md` 원칙 1) 준수.

### [MINOR — 의도적 설계 유지] 교사 미발견 음수 테스트 케이스 부재
- **파일**: `src/test/java/com/remake/gone/conduct/service/ConductServiceTest.java`
- **리뷰어 의견**: 교사 조회 실패 시나리오 테스트 추가 권장.
- **판단**: 위 MAJOR 판단과 동일 이유. 발생 불가능한 경로의 테스트는 추가하지 않음.

### [INFO] `@param teacherNickname` Javadoc 중복 (diff 아티팩트)
- 리뷰어가 diff 전달 중 발생한 아티팩트를 실제 문제로 오인. 실제 파일(`ConductRecordResponse.java`) 확인 결과 중복 없음. 조치 불필요.
