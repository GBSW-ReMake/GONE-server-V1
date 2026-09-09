# 코드 리뷰 결과 — 이슈 #94 (상/벌점 부여)

- **리뷰어**: 격리 에이전트 (`caveman:cavecrew-reviewer`)
- **대상 브랜치**: `feat/#94-conduct-grant` vs `dev`

## 결과 요약

| 심각도 | 건수 |
|---|---|
| Critical | 0 |
| High | 1 (수정 완료) |
| Medium | 1 (의도적 설계 유지) |
| Low | 1 (의도적 설계 유지) |

---

## 발견 사항

### 1. 🟠 High — `V16__add_conduct_record.sql`: `version` 컬럼 타입 불일치

**문제**: `src/main/resources/db/migration/V16__add_conduct_record.sql`의 `version` 컬럼이
`INT`로 정의되었으나 `ConductRecord.version`은 `Long` 타입이다. Hibernate `@Version` 어노테이션은
`BIGINT`를 기대하므로 `INT` 범위(2^31)를 초과하는 버전 번호에서 산술 오버플로가 발생한다.
수천 번 동시 업데이트가 누적된 레코드에서 실제로 재현 가능하다.

**해결 방안**:
1. SQL에서 `version INT` → `version BIGINT`로 변경한다 — 엔티티 타입과 일치하며 추가 코드
   변경이 없다. 이미 마이그레이션을 적용한 환경이 없으므로 파일 수정만으로 충분하다. **이
   방안을 채택해 커밋 완료.**
2. 엔티티 `version` 필드를 `int`(primitive)로 다운캐스트한다 — SQL 변경이 없지만 `@Version`
   필드를 `Long`에서 `int`로 바꾸면 `null` 안전성이 떨어지고 컨벤션(`Long` 식별자 계열 통일)
   과 어긋난다. 기각.

**판정**: **수정 완료** — `BIGINT`으로 변경 후 커밋.

---

### 2. 🟡 Medium — `ConductService`: 교사 미발견 시 `CommonErrorCode.NOT_FOUND` 사용

**문제**: `src/main/java/com/remake/gone/conduct/service/ConductService.java`에서 교사 조회
실패 시 전용 도메인 에러 코드 대신 `CommonErrorCode.NOT_FOUND`를 사용한다. 리뷰어는 `CONDUCT_NNN`
전용 에러 코드 추가를 권장했다.

**해결 방안**:
1. `TEACHER_NOT_FOUND` 도메인 에러 코드를 `ConductErrorCode`에 추가한다 — 에러 코드의 출처가
   명확해지지만, 교사는 `@AuthenticationPrincipal`에서 추출한 인증된 사용자이므로 DB에 없는
   경우 자체가 사실상 불가능하다. 방어 코드에만 쓰이는 에러 코드를 별도로 추가하면 `api-design.md`
   원칙 1("불필요한 도메인 에러 코드 추가 금지")에 위배된다.
2. `CommonErrorCode.NOT_FOUND`를 그대로 유지한다 — 범용 코드이지만 해당 경로가 실질적으로
   도달 불가능한 방어 코드임을 주석 없이 설계 원칙으로 일관되게 처리한다.

**판정**: **의도적 설계 유지** — 방안 2 채택. 도달 불가능한 방어 경로에 전용 에러 코드를
추가하지 않는다는 `api-design.md` 원칙 1 준수.

---

### 3. 🟢 Low — 교사 미발견 음수 테스트 케이스 부재

**문제**: `src/test/java/com/remake/gone/conduct/service/ConductServiceTest.java`의 `GrantConduct`
클래스에 교사 조회 실패 시나리오 테스트가 없다. 리뷰어는 해당 케이스 추가를 권장했다.

**해결 방안**:
1. `throwsWhenTeacherNotFound` 테스트를 추가한다 — 커버리지는 높아지지만 2번 항목과 동일하게
   해당 코드 경로는 실제로 도달 불가능하므로 테스트 가치가 없고, 허위 안정감을 줄 수 있다.
2. 테스트를 추가하지 않는다 — 도달 불가능한 방어 경로 테스트는 유지 비용만 늘린다.

**판정**: **의도적 설계 유지** — 방안 2 채택. 2번 항목 판단과 동일 이유.

---

### [INFO] `@param teacherNickname` Javadoc 중복 (diff 아티팩트)

리뷰어가 diff 전달 중 발생한 아티팩트를 실제 문제로 오인했다. 실제 파일
(`ConductRecordResponse.java`) 확인 결과 중복 없음. 조치 불필요.
