# 상/벌점 학생 본인 조회 — 기능 기획서 (이슈 #111)

## 개요/목적
상/벌점(Conduct) 도메인 네 번째 단계. 학생이 본인의 누적 상/벌점 요약과 이력을 조회하는 API를 만든다.

- **요약 조회** (`GET /api/v1/conduct-records/me/summary`): 전체 기간 기준 총 상점·벌점·순 점수, 벌점 임계치 초과 여부
- **이력 조회** (`GET /api/v1/conduct-records/me`): `type`·기간 필터 + 페이지네이션을 지원하는 이력 목록

두 엔드포인트 모두 `STUDENT` 전용이며, 본인 데이터만 노출한다. `userId`는 Access Token에서 추출하므로 요청 파라미터로 받지 않는다.

- **관련 마스터 기획서**: [`docs/domain/conduct/1_conduct-domain.md`](./1_conduct-domain.md) — "5. `GET /me/summary`" / "6. `GET /me`" 절
- **선행 이슈**: #107 (`amendConduct`, `cancelConduct` 구현)

---

## 에러 코드 추가 (`ConductErrorCode`)

현재 마지막 코드는 `CONDUCT_006`. 아래 2개를 추가한다.

| 코드 | HTTP | 메시지 |
|---|---|---|
| `CONDUCT_007` | 400 | 페이지 파라미터가 유효하지 않습니다. |
| `CONDUCT_008` | 400 | 날짜 범위 파라미터가 유효하지 않습니다. |

---

## 신규 컴포넌트

### `ConductProperties` (`@ConfigurationProperties(prefix = "conduct")`)

`OutingProperties`와 동일한 패턴. 누적 벌점 임계치를 설정으로 관리한다.

```java
@ConfigurationProperties(prefix = "conduct")
@Validated
public record ConductProperties(
    @NotNull @Positive Integer demeritThreshold
) {}
```

`application-dev.yml`에 `conduct.demerit-threshold: 10` 추가. `GoneServerV1Application`에 `@ConfigurationPropertiesScan`이 이미 선언되어 있으므로 별도 등록 코드 없이 자동으로 빈 등록된다.

### `ConductSummaryResponse` DTO

```java
public record ConductSummaryResponse(
    int totalMeritPoints,
    int totalDemeritPoints,
    int netScore,
    int demeritThreshold,
    boolean overDemeritThreshold
) {}
```

### `ConductStudentRecordResponse` DTO

학생 본인 조회 전용 응답. `ConductRecordResponse`(부여·정정·취소용)에서 `studentUserId`·`studentNickname`을 제외한 축약형이다 — 학생 본인 이력에서 자기 userId를 항목마다 반복 노출할 필요가 없다.

```java
public record ConductStudentRecordResponse(
    Long id,
    Long teacherUserId,
    String teacherNickname,
    Long categoryId,
    String categoryLabel,
    ConductType type,
    int points,
    String detail,
    ConductStatus status,
    LocalDateTime createdAt
) {
  public static ConductStudentRecordResponse from(ConductRecord record) { ... }
}
```

---

## 엔드포인트 1: `GET /api/v1/conduct-records/me/summary` — 요약

**권한**: `STUDENT` (`@PreAuthorize("hasRole('STUDENT')")`)

**요청**: 쿼리 파라미터 없음. 항상 전체 기간 기준이다.

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": {
    "totalMeritPoints": 6,
    "totalDemeritPoints": -4,
    "netScore": 2,
    "demeritThreshold": 10,
    "overDemeritThreshold": false
  },
  "message": "누적 점수를 조회했습니다."
}
```

**구현 로직**
1. `conductRecordRepository.sumPointsByStudentAndType(studentUserId, MERIT, ACTIVE)`로 `totalMeritPoints` 집계(결과 없으면 `0`)
2. `conductRecordRepository.sumPointsByStudentAndType(studentUserId, DEMERIT, ACTIVE)`로 `totalDemeritPoints` 집계(결과 없으면 `0`)
3. `netScore = totalMeritPoints + totalDemeritPoints`(`totalDemeritPoints`는 음수로 저장되므로 그대로 더한다)
4. `overDemeritThreshold = Math.abs(totalDemeritPoints) >= conductProperties.demeritThreshold()`

**에러**: 없음. 인증·역할 오류는 공통 인프라가 처리한다.

---

## 엔드포인트 2: `GET /api/v1/conduct-records/me` — 이력

**권한**: 1번과 동일 (`STUDENT`, 본인 것만)

**요청** (쿼리, 전부 선택)

| 파라미터 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `type` | `MERIT` / `DEMERIT` | 생략 시 둘 다 | 상점 또는 벌점만 필터 |
| `dateFrom` | `yyyyMMdd` | 없으면 전체 기간 | 조회 시작일(포함). `dateTo`와 반드시 함께 써야 한다 |
| `dateTo` | `yyyyMMdd` | 없으면 전체 기간 | 조회 종료일(포함). `dateFrom`과 반드시 함께 써야 한다 |
| `page` | 정수 | `0` | 페이지 번호(0부터 시작) |
| `size` | 정수 | `20` | 페이지 크기(1~100) |

**날짜 파라미터 규칙 (엄격 모드)**
- `dateFrom`·`dateTo` 모두 생략 → 전체 기간 조회
- `dateFrom`·`dateTo` 모두 제공 → 해당 기간으로 필터
- 하나만 제공 → `400` `CONDUCT_008`
- `dateFrom > dateTo` → `400` `CONDUCT_008`

**페이지 파라미터 규칙**
- `page < 0` 또는 `size < 1` 또는 `size > 100` → `400` `CONDUCT_007`

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 501,
        "teacherUserId": 42,
        "teacherNickname": "김선생",
        "categoryId": 5,
        "categoryLabel": "지각",
        "type": "DEMERIT",
        "points": -1,
        "detail": "3교시 10분 지각",
        "status": "ACTIVE",
        "createdAt": "2026-08-12T09:15:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false
  },
  "message": "상/벌점 이력을 조회했습니다."
}
```

**구현 로직**
1. `page`/`size` 검증 — 범위 밖이면 `400` `CONDUCT_007`
2. `dateFrom`/`dateTo` 검증 — 하나만 오거나 `dateFrom > dateTo`이면 `400` `CONDUCT_008`
3. `ConductRecordRepository`에 커스텀 JPQL 쿼리 추가:
   - `studentUserId`, `type`(nullable), `dateFrom`(nullable), `dateTo`(nullable), `Pageable` 파라미터
   - `WHERE student.id = :studentUserId AND (:type IS NULL OR r.type = :type) AND (:dateFrom IS NULL OR DATE(r.createdAt) >= :dateFrom) AND (:dateTo IS NULL OR DATE(r.createdAt) <= :dateTo)`
4. `Page<ConductRecord>`를 `PageResponse<ConductStudentRecordResponse>`로 변환해 반환
5. 취소된 기록(`CANCELED`)도 포함한다 — 학생이 과거 이력을 볼 때 취소된 기록도 보여야 한다(`status` 필드로 구분)

**에러**

| 조건 | HTTP | 코드 |
|---|---|---|
| `page < 0` / `size` 범위 밖 | 400 | `CONDUCT_007` |
| `dateFrom`·`dateTo` 중 하나만 | 400 | `CONDUCT_008` |
| `dateFrom > dateTo` | 400 | `CONDUCT_008` |

---

## 데이터 모델 변경

### `ConductRecordRepository` 추가 메서드

```java
// 요약용 집계 쿼리
@Query("SELECT COALESCE(SUM(r.points), 0) FROM ConductRecord r "
    + "WHERE r.student.id = :studentUserId AND r.type = :type AND r.status = :status")
int sumPointsByStudentAndType(
    @Param("studentUserId") Long studentUserId,
    @Param("type") ConductType type,
    @Param("status") ConductStatus status);

// 이력 페이지 조회 쿼리
@Query("SELECT r FROM ConductRecord r "
    + "WHERE r.student.id = :studentUserId "
    + "AND (:type IS NULL OR r.type = :type) "
    + "AND (:dateFrom IS NULL OR CAST(r.createdAt AS LocalDate) >= :dateFrom) "
    + "AND (:dateTo IS NULL OR CAST(r.createdAt AS LocalDate) <= :dateTo) "
    + "ORDER BY r.createdAt DESC")
Page<ConductRecord> findByStudentWithFilters(
    @Param("studentUserId") Long studentUserId,
    @Param("type") ConductType type,
    @Param("dateFrom") LocalDate dateFrom,
    @Param("dateTo") LocalDate dateTo,
    Pageable pageable);
```

### `application-dev.yml` 추가

```yaml
conduct:
  demerit-threshold: 10
```

### Flyway 마이그레이션

없음. 기존 `conduct_record` 테이블 변경 없음.

---

## 영향 받는 기존 코드

- `ConductErrorCode`: `CONDUCT_007`, `CONDUCT_008` 추가
- `ConductRecordRepository`: 집계·페이지 조회 쿼리 메서드 추가
- `ConductService`: `getStudentSummary`, `getStudentRecords` 메서드 추가
- `ConductController`: `GET /me/summary`, `GET /me` 엔드포인트 추가
- 신규 DTO: `ConductSummaryResponse`, `ConductStudentRecordResponse`
- 신규 config: `ConductProperties`

---

## API 설계 6원칙 체크

1. **한 가지를 잘하기**: 요약(집계)과 이력(목록)을 분리한 엔드포인트로 구현. 집계에 날짜 필터를 넣으면 "이 기간의 순 점수"와 "전체 누적 순 점수"가 혼재되어 의미가 흐려지므로 분리가 적합하다.
2. **빠른 시작**: 요청·응답 예시 포함.
3. **일관성**: 경로 `/api/v1/conduct-records/me/*`, `ApiResponse<T>` 래퍼, `CONDUCT_NNN` 에러 코드 네이밍, `PageResponse<T>` 페이지 포맷 — outing 도메인(#41) 및 기존 conduct 엔드포인트 패턴 그대로.
4. **의미 있는 오류**: 페이지 범위 위반(`CONDUCT_007`)과 날짜 범위 모순(`CONDUCT_008`)을 분리. `dateFrom > dateTo`와 "하나만 옴"을 같은 코드로 묶었으나 메시지로 구분한다.
5. **확장성·성능**: `ConductRecordRepository.findByStudentWithFilters`는 DB 레벨 페이지네이션(`Pageable`). 학생 한 명의 기록은 학기 단위로 수십~수백 건 예상 — 인덱스(`student_user_id`, `created_at`) 존재 여부를 구현 중 확인한다.
6. **하위 호환성**: 기존 `ConductRecordResponse` 변경 없음. 신규 DTO만 추가.

---

## 리스크 및 고려사항

- **`ConductStudentRecordResponse` vs `ConductRecordResponse` 재사용**: 학생 본인 이력에 `studentUserId`/`studentNickname`을 포함하면 기존 DTO를 그대로 쓸 수 있지만, 마스터 기획서 응답 스펙(해당 필드 없음)과 어긋난다. 신규 DTO를 만들면 유지 비용이 생기지만 스펙에 정확히 맞다 — **신규 DTO 채택.**
- **날짜 필터 기준**: `createdAt`(부여 일시)의 날짜 부분을 KST 기준으로 비교해야 한다. JPQL의 `CAST(r.createdAt AS LocalDate)`가 MySQL 시간대를 따르므로, DB 서버 시간대가 KST(`Asia/Seoul`)로 설정되어 있는지 확인해야 한다(dev 환경 JDBC URL에 `serverTimezone=Asia/Seoul` 있음 — 호환).
- **`COALESCE(SUM(...), 0)`**: 기록이 없으면 `SUM`이 `null`을 반환한다. `COALESCE`로 `0`으로 처리한다.
- **인덱스**: V16 마이그레이션에 `idx_conduct_record_student_created (student_user_id, created_at)` 복합 인덱스가 이미 존재한다. 신규 마이그레이션 불필요.
