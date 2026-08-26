# 교사·선도부·관리자 상/벌점 조회 기획서 (이슈 #117)

> 관련 마스터 기획서: [1_conduct-domain.md](./1_conduct-domain.md) — 엔드포인트 7·8번

## 개요/목적

교사·선도부·관리자가 특정 학생의 누적 점수 요약과 전체·특정 학생의 이력을 조회한다.
학생 본인 조회(#111, 엔드포인트 5·6번)의 교사·관리자 버전이며, 조회 대상을 파라미터로
지정한다는 점과 `studentUserId`/`studentNickname`을 응답에 포함한다는 점에서 차이가 있다.

---

## 엔드포인트

### 7. `GET /api/v1/conduct-records/summary` — 특정 학생 누적 점수 요약 (교사·선도부·관리자)

**권한**: `TEACHER`, `DISCIPLINE`, `ADMIN`
(`@PreAuthorize("hasRole('TEACHER') or hasRole('DISCIPLINE') or hasRole('ADMIN')")`)

**요청** (쿼리 파라미터)

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `studentUserId` | `Long` | ✅ | 조회할 학생의 사용자 ID |

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": {
    "studentUserId": 101,
    "studentNickname": "길동이",
    "totalMeritPoints": 6,
    "totalDemeritPoints": -4,
    "netScore": 2,
    "demeritThreshold": 10,
    "overDemeritThreshold": false
  },
  "message": "누적 점수를 조회했습니다.",
  "code": null
}
```

**구현 로직**
1. `studentUserId`로 `User` 조회 — 없으면 `404` `CONDUCT_005`
2. 대상이 `STUDENT` 역할인지 확인 — 아니면 `400` `CONDUCT_006`
3. `conductRecordRepository.sumPointsByStudentAndType`으로 `totalMeritPoints`·
   `totalDemeritPoints` 집계(전체 기간, `ACTIVE` 기록만)
4. `netScore`, `overDemeritThreshold` 계산 — `#111` `getStudentSummary`와 동일 공식
5. 응답에 `studentUserId`·`studentNickname` 포함 — 학생 본인 조회(5번)와의 차이

**에러**
- 존재하지 않는 `studentUserId` → `404` `CONDUCT_005`
- 대상이 `STUDENT` 역할이 아님 → `400` `CONDUCT_006`
- `studentUserId` 미전달 → `400` (공통 `MissingServletRequestParameterException`)

---

### 8. `GET /api/v1/conduct-records` — 이력 상세 목록 조회 (교사·선도부·관리자)

**권한**: 7번과 동일

**요청** (쿼리 파라미터, 전부 선택)

| 파라미터 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `studentUserId` | `Long` | — | 특정 학생만 조회(생략 시 전체 학생 대상) |
| `type` | `MERIT`/`DEMERIT` | — | 종류 필터(생략 시 전체) |
| `dateFrom` | `yyyyMMdd` | — | 조회 시작일(`dateTo`와 함께 써야 함) |
| `dateTo` | `yyyyMMdd` | — | 조회 종료일(`dateFrom`과 함께 써야 함) |
| `page` | `int` | `0` | 페이지 번호(0부터 시작) |
| `size` | `int` | `20` | 페이지 크기(1~100) |

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 501,
        "studentUserId": 101,
        "studentNickname": "길동이",
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
  "message": "상/벌점 이력을 조회했습니다.",
  "code": null
}
```

> 응답 항목은 기존 `ConductRecordResponse` DTO를 그대로 사용한다 — `studentUserId`·
> `studentNickname`을 이미 포함하고 있어 `studentUserId` 생략(전체 학생) 조회에서도 각
> 기록의 학생을 식별할 수 있다.

**구현 로직**
1. `page`/`size` 검증 — 범위 밖이면 `400` `CONDUCT_007`
2. `dateFrom`/`dateTo` 검증 — 한 쪽만 오거나 역순이면 `400` `CONDUCT_008`
3. `studentUserId`가 있으면 해당 학생만, 없으면 전체 학생 대상으로 `type`·기간 필터를
   적용한 `Page<ConductRecord>` 조회(DB 레벨 페이지네이션)
4. `ConductRecordResponse`로 변환해 `PageResponse`로 반환 — 취소 기록 포함

**에러**
- `page`/`size` 범위 밖 → `400` `CONDUCT_007`
- `dateFrom`/`dateTo` 중 하나만 옴 또는 역순 → `400` `CONDUCT_008`

---

## 데이터 모델 변경

### 신규 응답 DTO

**`ConductStaffSummaryResponse`** (신규)

| 필드 | 타입 | 설명 |
|---|---|---|
| `studentUserId` | `Long` | 조회 대상 학생 ID |
| `studentNickname` | `String` | 조회 대상 학생 별명 |
| `totalMeritPoints` | `int` | 전체 기간 상점 합계(양수) |
| `totalDemeritPoints` | `int` | 전체 기간 벌점 합계(음수) |
| `netScore` | `int` | 순 점수 |
| `demeritThreshold` | `int` | 벌점 임계치 |
| `overDemeritThreshold` | `boolean` | 임계치 초과 여부 |

> `ConductSummaryResponse`(#111 학생 본인용)에 `studentUserId`·`studentNickname` 두 필드만
> 추가한 구조다. 기존 DTO를 상속하지 않고 별도 record로 만들어, 두 DTO의 필드 진화가
> 서로를 깨트리지 않게 한다.

### 기존 DTO 재사용

**`ConductRecordResponse`** — 8번 엔드포인트 응답에 그대로 사용. `studentUserId`·
`studentNickname` 필드가 이미 있어 별도 DTO가 필요 없다.

### 신규 Repository 메서드

**`findWithFilters`** — `studentUserId`가 nullable한 필터 확장판.
`findByStudentWithFilters`(#111)와 유사하지만 `studentUserId`가 `null`이면 전체 학생을
조회하는 JPQL 조건을 추가한다.

```jpql
SELECT r FROM ConductRecord r
LEFT JOIN FETCH r.teacher
LEFT JOIN FETCH r.category
LEFT JOIN FETCH r.student
WHERE (:studentUserId IS NULL OR r.student.id = :studentUserId)
AND (:type IS NULL OR r.type = :type)
AND (:dateFrom IS NULL OR CAST(r.createdAt AS LocalDate) >= :dateFrom)
AND (:dateTo IS NULL OR CAST(r.createdAt AS LocalDate) <= :dateTo)
ORDER BY r.createdAt DESC, r.id DESC
```

> `r.student`도 `LEFT JOIN FETCH`로 가져온다 — `ConductRecordResponse.from(record)`에서
> `record.getStudent().getId()`·`record.getStudent().getName()`을 접근하기 때문이다.

### Flyway 마이그레이션

불필요. 신규 테이블/컬럼 없음.

---

## 영향 받는 기존 코드

| 파일 | 변경 내용 |
|---|---|
| `ConductRecordRepository` | `findWithFilters` 메서드 추가 |
| `ConductService` | `getStaffSummary`, `getRecords` 메서드 추가 |
| `ConductController` | `GET /summary`, `GET /` 두 엔드포인트 추가 |
| `ConductServiceTest` | `GetStaffSummary`, `GetRecords` 중첩 클래스 추가 |

---

## 리스크 및 고려사항

### API 설계 6원칙 체크

1. **한 가지를 잘하기**: 요약(7번)과 이력(8번)을 분리해 단일 책임. 마스터 기획서와 동일한
   판단 유지.
2. **빠른 시작**: 요청/응답 예시 포함.
3. **일관성**:
   - `GET /me/summary`(학생) ↔ `GET /summary`(교사·관리자) 대칭 구조 유지.
   - `GET /me`(학생) ↔ `GET /`(교사·관리자) 대칭 구조 유지.
   - `page`/`size`/`dateFrom`/`dateTo` 파라미터 이름·검증 로직은 `#111`과 동일.
4. **의미 있는 오류**: `CONDUCT_005`(학생 없음)·`CONDUCT_006`(학생 역할 아님)·
   `CONDUCT_007`(페이지 오류)·`CONDUCT_008`(날짜 오류) 모두 기존 코드 재사용 — 새 에러
   코드 없음.
5. **확장성·성능**:
   - 8번 `studentUserId` 생략 시 전체 학생 대상 조회 — 기간 필터 없이 호출하면 전체 기간
     스캔이 된다. 마스터 기획서(#58)에서 "데이터가 실제로 쌓인 뒤 성능이 문제 되면 기본
     기간 강제를 재검토한다"고 명시했으므로 이번 범위에선 제한 없이 구현하고, 인덱스
     `(student_user_id, created_at)`·`(teacher_user_id, created_at)`(V16 마이그레이션에 이미
     존재)으로 성능을 보완한다.
   - `ManyToOne`(`teacher`·`category`·`student`) 세 관계 모두 `LEFT JOIN FETCH`로 N+1 방지.
6. **하위 호환성**: 신규 엔드포인트 추가만이므로 기존 API에 영향 없음.

### 라우트 충돌 확인

- `GET /api/v1/conduct-records/me/summary` — 학생용(#111)
- `GET /api/v1/conduct-records/me` — 학생용(#111)
- `GET /api/v1/conduct-records/summary` — 교사·관리자용(이번)
- `GET /api/v1/conduct-records/categories` — 카테고리 목록(#92)
- `GET /api/v1/conduct-records` — 교사·관리자용(이번)

`/me`는 고정 문자열이라 `/{id}`와 충돌하지 않는다. `GET /` 자체는 Spring MVC에서
`@RequestMapping("/api/v1/conduct-records")` 레벨에 `@GetMapping`을 붙이면 된다.
`/summary`도 고정 문자열이라 `GET /`와 구분된다.

### `student` fetch join과 페이지네이션

`student`는 `ManyToOne`이라 `LEFT JOIN FETCH`를 추가해도 행 수가 팽창하지 않는다 —
`ManyToMany`나 `OneToMany` fetch join + `Pageable` 조합에서 발생하는
"HHH90003004 in-memory pagination" 경고와 무관하다. #111에서 `teacher`·`category`에
동일하게 적용해 검증한 패턴이다.

### `ConductStaffSummaryResponse` vs `ConductSummaryResponse` 재사용

`ConductSummaryResponse`에 `studentUserId`/`studentNickname`을 추가해 재사용하는 방법도
있지만, 그렇게 하면 학생 본인 조회(5번) 응답에 의미 없는 null 필드가 생긴다. 학생 본인은
"내 기록을 보고 있음"을 이미 알고 있어 응답에 `studentUserId`를 넣을 이유가 없다 — 별도
record로 분리하는 것이 더 명확하다.
