# 상/벌점(Conduct) 도메인 — 기능 기획서 (마스터, 이슈 #58)

> 이 문서는 도메인 전체를 다루는 마스터 기획서다. `outing` 도메인의
> [1_outing-domain.md](../outing/1_outing-domain.md)와 같은 위치 — 이 문서 자체가 구현
> 대상은 아니고, 승인되면 여기 정의된 엔드포인트별로 개별 이슈(`outing`의 #29/#30/#31/#41처럼)를
> 새로 만들어 그때 실제 구현을 진행한다. **이번 이슈(#58)는 기획 + 검토 + Notion 기능
> 명세서 반영까지만 다룬다.**

## 개요/목적
학생의 생활 태도(지각/무단결석 등 규정 위반, 봉사활동/선행 등 모범 사례)를 점수로 기록하는
상/벌점 제도를 서버로 옮긴다. 지금은 종이/엑셀 등 수기로 관리되는 것으로 가정한다. 교사가
사전 정의된 사유(카테고리)를 골라 학생에게 상점 또는 벌점을 부여하면, 학생 본인은 누적
점수와 이력을 조회하고, 교사/선도부/관리자는 전체 학생의 현황을 조회한다.

## 목차
1. [용어 정리](#용어-정리)
2. [정책 가정](#정책-가정-확정-전--리뷰-시-조율-필요)
3. [권한 모델](#권한-모델)
4. [도메인 모델](#도메인-모델)
5. [엔드포인트](#엔드포인트)
6. [알림 트리거](#알림-트리거-푸시-37-선행-의존--이번-구현-범위-아님)
7. [API 설계 6원칙 체크](#api-설계-6원칙-체크)
8. [에러 코드](#에러-코드-conducterrorcode-신규-패키지-conduct)
9. [공통 구현 고려사항](#공통-구현-고려사항)
10. [아직 결정 안 된 것](#아직-결정-안-된-것-리뷰-필요)

## 용어 정리
- **상/벌점 기록(`ConductRecord`)**: 교사가 학생에게 부여한 상점 또는 벌점 한 건. 카테고리,
  점수, 부여 교사, 상세 사유(선택), 상태(활성/취소)를 담는다.
- **카테고리(`ConductCategory`)**: 부여 사유를 미리 등록해둔 목록. 각 카테고리는 종류
  (상점/벌점)와 고정 점수를 함께 가진다 — 교사는 점수를 직접 입력하지 않고 카테고리를
  고른다. 코드 상수(enum)가 아니라 **DB 테이블**로 관리해 나중에 `ADMIN`이 추가/삭제할 수
  있게 한다(아래 "정책 가정" 참고).
- **순 점수(net score)**: 한 학생의 활성 상태(`ACTIVE`) 기록만 합산한 점수(상점은 양수,
  벌점은 음수로 저장해 그대로 합산 — 아래 "도메인 모델" 참고).
- **정정**: 부여 후 카테고리/상세 사유를 고치는 것. 대상 학생은 바꿀 수 없다(학생을 바꾸는
  건 사실상 새 기록이라 정정 범위가 아니다 — 잘못된 학생에게 부여했다면 취소 후 재부여).
- **취소**: 기록을 활성 상태에서 제외하는 것(하드 삭제 아님, `CANCELED` 상태로 전환). 되돌릴
  수 없다 — outing의 `REJECTED`/`RETURNED`처럼 한번 취소되면 그 기록은 더 이상 바뀌지 않는다.

## 정책 가정 (확정 전 — 리뷰 시 조율 필요)
- **부여 권한은 `TEACHER` 역할 전체에게 준다(확정).** 이 프로젝트에는 아직 담임-학급 매핑이
  없어(`outing` 도메인에서 이미 확인된 제약, [1_outing-domain.md](../outing/1_outing-domain.md)
  "정책 가정" 참고) 담임으로 제한할 수 없다. **`DISCIPLINE`(선도부)은 이번 범위에서 직접
  부여 권한을 갖지 않고 조회 권한만 가진다** — 선도부가 발견한 사안은 담당 교사에게 전달해
  교사가 부여하는 흐름을 전제한다.
- **카테고리는 사전 정의된 목록 + 고정 점수**로 관리한다. 교사가 점수를 자유 입력하지 않고
  카테고리만 선택 — 통계/집계가 항상 일관되게 나온다. **코드 상수(enum)가 아니라 DB
  테이블(`ConductCategory`)로 관리한다** — 나중에 `ADMIN`이 화면에서 카테고리를
  추가/삭제할 수 있어야 하는데, enum은 코드 배포 없이 값을 바꿀 수 없기 때문이다. 이번
  마스터 기획/구현 범위에는 그 추가/삭제 화면(API)까지 포함하지 않지만, 나중에 다시
  스키마를 바꾸지 않아도 되도록 처음부터 테이블로 설계한다(아래 "도메인 모델" 참고). 초기
  값은 아래 표의 **가정값**을 시드 데이터로 넣고, 실제 학교 상/벌점 규정표와 대조해 확정해야
  한다(아래 "아직 결정 안 된 것" 참고).
- **정정/취소는 부여한 교사 본인 또는 `ADMIN`만 할 수 있다.** 다른 교사가 남의 부여 기록을
  고치거나 취소할 수 없다 — `outing`의 승인/거절 소유권 검증과 같은 원칙(아래 "권한 모델"
  참고).
- **누적 벌점 임계치는 "알림 발송"이 아니라 "조회 응답에 표시"까지만 다룬다.** 이 프로젝트에는
  아직 푸시 알림 인프라(FCM 등, #37 "알림 시스템 도입")가 없다 — `outing` 도메인의 복귀
  리마인더와 동일한 제약([1_outing-domain.md](../outing/1_outing-domain.md) "복귀 리마인더"
  절 참고). 이번 기획에서는 학생/교사 조회 응답에 "임계치 초과 여부" 불리언 필드만 내려주고,
  실제 푸시 알림 발송은 #37 완료 후 별도 이슈로 연동한다. 임계치 값(가정: 벌점 10점)은 아래
  "아직 결정 안 된 것" 참고.
- **이의신청(소명) 프로세스는 이번 마스터 기획 범위에서 제외한다.** 학생이 부여된 벌점에
  이의를 제기하고 교사/관리자가 승인·반려하는 흐름은 범위가 꽤 커서(승인 주체, 처리 기한,
  거절 시 재이의 가능 여부 등 별도 정책 필요) 별도 이슈 후보로만 남긴다. 지금은 정정/취소
  권한이 교사 본인에게 있으므로, 학생이 교사에게 직접 요청해 교사가 정정/취소하는 것으로
  대체한다(서버가 강제하는 공식 프로세스는 아님).
- **외부 식별자로 `code`를 따로 두지 않는다.** `outing`의 외출증은 "공적 문서라 프론트에 표시할
  고유 코드가 필요하다"는 이유로 `id`/`code`를 분리했지만([1_outing-domain.md](../outing/1_outing-domain.md)
  "외부 식별자 정책" 참고), 상/벌점 기록은 학생 본인/부여 교사/`DISCIPLINE`/`ADMIN`만 접근
  가능하고 별도로 노출할 "기록 번호"가 필요하다는 요구사항도 없다. `TimetableController`/
  `UserController`처럼 내부 `id`를 그대로 경로에 쓴다 — IDOR은 소유권/역할 검증으로 이미
  방어되므로 코드 분리로 얻는 추가 이득이 크지 않다고 판단했다(불필요한 복잡도 추가 지양).
- **목록 조회는 DB 레벨 페이지네이션(`Pageable`)을 쓴다.** `outing`의 #41은 응답 시점에
  실시간 계산되는 `MISSED` 상태 때문에 메모리 내 페이지네이션을 택했지만
  ([41-outing-query.md](../outing/41-outing-query.md) "페이지네이션" 절 참고), 이 도메인은
  그런 파생 상태가 없다 — DB `status` 컬럼 값을 그대로 필터링할 수 있으므로 Spring Data
  `Pageable`/`Page`를 그대로 써서 DB `LIMIT`/`OFFSET`으로 처리한다. "전체 학생 현황" 조회는
  기간이 길어지면 행 수가 outing보다 커질 수 있어(학년 전체 × 연간 누적) 메모리에 전부 올리는
  방식보다 이쪽이 더 안전하다.

## 권한 모델
- `@EnableMethodSecurity`는 `outing` 도메인(#30)에서 이미 도입되어 있어, 이 도메인은 별도
  선행 작업 없이 바로 `@PreAuthorize`를 쓸 수 있다.
- 엔드포인트별 필요 역할:
  1. 부여: `TEACHER`
  2. 정정/취소: `TEACHER` 또는 `ADMIN` + (`TEACHER`인 경우) 본인이 그 기록을 부여한 교사인지
     소유권 확인 — `ADMIN`은 소유권 무관하게 항상 가능(프로젝트 공통 전제)
  3. 카테고리 목록 조회: 인증된 사용자 전체(`isAuthenticated()`) — 학생도 부여 화면 없이
     "내 기록이 어떤 사유인지" 이해하는 데 필요할 수 있어 제한하지 않는다
  4. 본인 누적 점수 요약/이력 조회: `STUDENT`(본인 것만, `userId`는 토큰에서 추출)
  5. 특정 학생 누적 점수 요약/전체·특정 학생 이력 조회: `TEACHER`, `DISCIPLINE`, `ADMIN`
- 정정/취소의 소유권 체크는 `outing`의 승인/거절과 같은 이유로 `@PreAuthorize`의 SpEL이
  아니라 서비스 코드에서 명시적 `if`로 한다([1_outing-domain.md](../outing/1_outing-domain.md)
  "권한 모델" 트레이드오프 설명과 동일).

## 도메인 모델

### `ConductType` (enum)
- `MERIT` — 상점
- `DEMERIT` — 벌점

### `ConductCategory` 엔티티 (`conduct_category` 테이블, 신규 — 카테고리 + 고정 점수)
enum이 아니라 DB 테이블이다(위 "정책 가정" 참고 — `ADMIN`이 나중에 추가/삭제할 수 있어야
해서). 필드:
- `id` — 내부 PK, `ConductRecord.category_id`가 참조
- `label` — 표시명(한글, 예: "지각"), `UNIQUE`
- `type` — `ConductType`(`MERIT`/`DEMERIT`)
- `points` — 고정 점수(부호 포함, 상점은 양수·벌점은 음수)
- `active` — 사용 가능 여부(`boolean`, 기본 `true`) — "삭제"는 이 값을 `false`로 바꾸는
  **소프트 삭제**로 처리한다(하드 삭제 API는 제공하지 않는다). 4번 엔드포인트(카테고리 목록
  조회)는 `active = true`인 것만 보여주지만, 이미 이 카테고리를 참조 중인 과거
  `ConductRecord`는 행이 그대로 남아있는 `ConductCategory`를 계속 정상적으로 참조할 수
  있다 — 부여/정정 화면의 선택 목록에서만 빠질 뿐, 데이터 무결성이나 과거 기록 표시에는
  영향이 없다.
- `created_at`

**초기 시드 데이터**(가정값, 아래 "아직 결정 안 된 것" 참고 — 실제 구현 시 마이그레이션의
`INSERT`로 반영):

| 표시명(`label`) | 종류(`type`) | 점수(`points`) |
|---|---|---|
| 봉사활동 참여 | MERIT | +2 |
| 선행/미담 사례 | MERIT | +3 |
| 교내 대회 수상 등 학업 우수 | MERIT | +5 |
| 교내 행사 적극 참여 | MERIT | +1 |
| 지각 | DEMERIT | -1 |
| 무단조퇴 | DEMERIT | -3 |
| 무단결석 | DEMERIT | -5 |
| 복장 불량 | DEMERIT | -1 |
| 수업 중 휴대폰 무단 사용 | DEMERIT | -1 |
| 수업 방해 | DEMERIT | -2 |
| 흡연 | DEMERIT | -10 |
| 학교폭력/괴롭힘 | DEMERIT | -10 |

> 💡 카테고리 **추가/삭제** API(관리자 전용)는 이번 마스터 기획/구현 범위 밖이다(ADMIN 관련
> 기능은 별도 웹 관리자 페이지에서 다룬다는 프로젝트 공통 전제 — `outing-domain.md` 권한
> 모델 참고). 이번엔 위 표를 시드 데이터로 넣는 것까지만 다루고, 실제 추가/삭제 화면/API
> 설계는 후속 이슈로 분리한다(아래 "아직 결정 안 된 것" 참고). **다만 데이터 모델은 지금부터
> 테이블로 설계해둬서, 그 후속 이슈에서 스키마를 다시 바꾸지 않아도 되게 한다.**

### `ConductStatus` (enum)
- `ACTIVE` — 유효한 기록(집계에 포함)
- `CANCELED` — 취소됨(집계에서 제외, 이력에는 남아 표시)

### `ConductRecord` 엔티티 (`conduct_record` 테이블, 신규)
- `id` — 내부 PK(BIGINT, 자동증가), API 경로/응답에 그대로 사용(위 "외부 식별자" 정책 가정
  참고)
- `student_user_id` — 대상 학생(`User` FK)
- `teacher_user_id` — 부여한 교사(`User` FK, 정정/취소 소유권 판단 기준)
- `category_id` — 부여 당시 선택한 카테고리(`ConductCategory` FK) — 나중에 그 카테고리가
  `active = false`(소프트 삭제)로 바뀌어도 이 FK는 계속 유효하다(행이 지워지지 않으므로)
- `type` — `ConductType`(카테고리에서 파생되지만, 카테고리별 점수/타입 정의가 나중에 바뀌어도
  과거 기록의 의미가 바뀌지 않도록 저장 시점 값을 그대로 컬럼에 스냅샷)
- `points` — 부여 시점 카테고리의 고정 점수를 그대로 스냅샷(부호 포함) — 나중에
  `ConductCategory`의 점수가 바뀌어도(현재는 추가/삭제만 다루고 점수 수정 기능은 없지만,
  나중에 생기더라도) 이미 부여된 기록의 점수는 바뀌지 않는다(감사 가능성/일관성)
- `detail` — 카테고리 외 추가로 남기는 상세 사유(선택, `VARCHAR(500)`, nullable)
- `status` — `ConductStatus`(`ACTIVE`/`CANCELED`)
- `canceled_at` — 취소 시각(취소된 경우만)
- `canceled_by_user_id` — 취소를 실행한 사용자(`User` FK, 부여 교사 본인 또는 `ADMIN`,
  취소된 경우만)
- `cancel_reason` — 취소 사유(취소된 경우만 필수)
- `created_at`
- `updated_at` — 정정 시 갱신(`@UpdateTimestamp`)
- 인덱스: `(student_user_id, created_at)` — 학생별 이력 조회, `(teacher_user_id, created_at)`
  — 교사별 부여 이력 조회

> 💡 `points`를 카테고리 FK 조회 대신 스냅샷으로 저장하는 이유: `ConductCategory`가 이제
> DB 테이블이라 나중에 관리자가 값을 바꿀 수 있는데(이번 범위는 추가/삭제만이지만, 후속
> 이슈에서 점수 수정까지 열릴 수 있다), 이미 부여된 과거 기록까지 소급 적용되면 "그때는
> -1점이었는데 지금 조회하니 -2점"처럼 감사 로그로서의 신뢰가 깨진다. `outing`의
> `start_time`/`end_time`을 프리셋에서 서버가 채워 스냅샷하는 것과 같은 이유다.

### 정정 가능 범위
- 정정 가능한 필드: `categoryId`(변경 시 `type`/`points`도 새 카테고리 기준으로 다시
  스냅샷 — 대상 카테고리가 `active = false`면 정정에 쓸 수 없다, `CONDUCT_004`), `detail`
- 정정 불가능한 필드: `studentUserId`(대상 학생 변경은 새 기록으로 처리), `teacherUserId`(부여
  주체는 바뀌지 않는다)
- `CANCELED` 상태인 기록은 정정할 수 없다(취소된 기록을 살리려면 재부여)

## 엔드포인트

### 1. `POST /api/v1/conduct-records` — 상/벌점 부여
**권한**: `TEACHER` (`@PreAuthorize("hasRole('TEACHER')")`)

**요청**
```json
{
  "studentUserId": 101,
  "categoryId": 5,
  "detail": "3교시 10분 지각"
}
```
(`detail`은 선택 — 생략 시 `null`. `categoryId`는 4번 엔드포인트로 미리 받아둔 목록 중
하나)

**응답** (`201 Created`)
```json
{
  "success": true,
  "data": {
    "id": 501,
    "studentUserId": 101,
    "studentNickname": "길동이",
    "teacherUserId": 42,
    "teacherName": "김선생",
    "categoryId": 5,
    "categoryLabel": "지각",
    "type": "DEMERIT",
    "points": -1,
    "detail": "3교시 10분 지각",
    "status": "ACTIVE",
    "createdAt": "2026-08-12T09:15:00"
  },
  "message": "상/벌점이 부여되었습니다.",
  "code": null
}
```

**구현 로직**
1. `categoryId`로 `ConductCategory` 조회, 없거나 `active = false`면 `400` `CONDUCT_004`
2. `studentUserId`로 `User` 조회, 없으면 `404` `CONDUCT_005`
3. `userRoleRepository.findRoleCodesByUserId(studentUserId)`로 대상이 `STUDENT` 역할인지
   확인, 아니면 `400` `CONDUCT_006`(교사·관리자 계정에는 부여 불가)
4. `category.getType()`/`category.getPoints()`로 `type`/`points` 산출(스냅샷)
5. `ConductRecord` 저장(`status = ACTIVE`, `teacherUserId`는 `@AuthenticationPrincipal`에서
   추출)
6. 응답 DTO 변환(`studentNickname = student.getName()`, `teacherName =
   teacher.getGbsw().getName()` — `outing`과 동일하게 교사는 실명만 노출, `categoryLabel =
   category.getLabel()`)

**에러**
- `categoryId`가 존재하지 않거나 비활성화됨 → `400` `CONDUCT_004`
- 존재하지 않는 `studentUserId` → `404` `CONDUCT_005`
- 대상이 `STUDENT` 역할이 아님 → `400` `CONDUCT_006`

---

### 2. `PATCH /api/v1/conduct-records/{id}` — 정정
**권한**: `TEACHER` 또는 `ADMIN` (`@PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")`)
+ `TEACHER`인 경우 본인이 부여한 기록인지 소유권 확인(서비스 코드)

**요청** (`categoryId`/`detail` 둘 다 선택 — 보낸 필드만 갱신)
```json
{ "categoryId": 6, "detail": "3교시 이후 조퇴, 사유서 미제출" }
```

**응답** (`200 OK`) — 1번과 동일한 구조, 갱신된 `categoryId`/`categoryLabel`/`type`/
`points`/`detail` 반영

**구현 로직**
1. `id`로 `ConductRecord` 조회, 없으면 `404` `CONDUCT_001`
2. 호출자가 `ADMIN`이 아니고 `principal.userId() != record.getTeacherUserId()`면 `403`
   `CONDUCT_002`
3. `record.getStatus() == CANCELED`면 `409` `CONDUCT_003`
4. `categoryId`가 요청에 있으면 그 `ConductCategory`를 조회(없거나 `active = false`면
   `400` `CONDUCT_004`) 후 `type`/`points` 재계산해 갱신, `detail`이 있으면 갱신
5. 저장 후 응답 변환

**에러**
- 존재하지 않는 `id` → `404` `CONDUCT_001`
- 본인이 부여한 기록이 아님(`ADMIN` 아닌 경우) → `403` `CONDUCT_002`
- 이미 취소된 기록 → `409` `CONDUCT_003`
- `categoryId`가 존재하지 않거나 비활성화됨 → `400` `CONDUCT_004`

---

### 3. `PATCH /api/v1/conduct-records/{id}/cancel` — 취소
**권한**: 2번과 동일

**요청**
```json
{ "cancelReason": "학생 확인 결과 오인 부여로 확인됨" }
```

**응답** (`200 OK`) — `"status": "CANCELED"`, `canceledAt`/`cancelReason` 포함, 그 외 1번과
동일 구조

**구현 로직**: 2번의 1~3단계와 동일한 조회/소유권/상태 확인 후, `status = CANCELED`,
`canceledAt = now`, `canceledByUserId = principal.userId()`, `cancelReason` 저장

**에러**: 2번과 동일한 코드 체계(`CONDUCT_001`/`CONDUCT_002`/`CONDUCT_003`)

> 💡 취소는 되돌릴 수 없다(`outing`의 `REJECTED`/`RETURNED`와 동일한 원칙) — 잘못 취소했다면
> 새로 부여한다.

---

### 4. `GET /api/v1/conduct-records/categories` — 카테고리 목록 조회
**권한**: 인증된 사용자 전체 (`@PreAuthorize("isAuthenticated()")`)

**요청**: 파라미터 없음

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": [
    { "id": 1, "label": "봉사활동 참여", "type": "MERIT", "points": 2 },
    { "id": 5, "label": "지각", "type": "DEMERIT", "points": -1 }
  ],
  "message": "카테고리 목록을 조회했습니다.",
  "code": null
}
```

**구현 로직**: `conductCategoryRepository.findByActiveTrue()`로 활성 카테고리만 조회해 DTO로
변환 — 프론트가 부여 화면 드롭다운을 하드코딩하지 않고 이 목록(`id`를 그대로 부여/정정
요청의 `categoryId`로 사용)으로 구성한다. 관리자가 나중에 비활성화(삭제)한 카테고리는 이
목록에서 빠지지만, 그 카테고리를 이미 참조 중인 과거 기록은 영향받지 않는다(위 "도메인
모델" 참고).

---

### 5. `GET /api/v1/conduct-records/me/summary` — 본인 누적 점수 요약 (학생)
**권한**: `STUDENT` (`@PreAuthorize("hasRole('STUDENT')")`, 본인 것만 — `userId`는 토큰에서
추출, 요청 파라미터로 안 받음)

**요청**: 파라미터 없음(항상 전체 기간 기준 — 아래 "왜 요약과 이력을 나눴나" 참고)

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
  "message": "누적 점수를 조회했습니다.",
  "code": null
}
```

**구현 로직**
1. `conductRecordRepository`의 집계 쿼리(`SUM(points) WHERE studentUserId = ? AND status =
   ACTIVE AND type = ?`, JPQL)로 `totalMeritPoints`/`totalDemeritPoints` 계산(항상 전체
   기간, 필터 없음)
2. `netScore = totalMeritPoints + totalDemeritPoints`(`totalDemeritPoints`는 음수로
   저장/합산되므로 그대로 더한다)
3. `overDemeritThreshold = Math.abs(totalDemeritPoints) >= conductProperties.demeritThreshold()`

**에러**: 없음(인증/역할 오류는 공통 인프라)

---

### 6. `GET /api/v1/conduct-records/me` — 본인 이력 상세 조회 (학생)
**권한**: 5번과 동일(`STUDENT`, 본인 것만)

**요청** (쿼리, 전부 선택)
- `type` — `MERIT`/`DEMERIT` (생략 시 둘 다)
- `dateFrom`/`dateTo` — `yyyyMMdd`(둘 다 생략하면 전체 기간, 하나만 오면 `400` `CONDUCT_008`)
- `page`(기본 `0`)/`size`(기본 `20`, `1~100`)

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 501,
        "teacherUserId": 42,
        "teacherName": "김선생",
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

**구현 로직**
1. `page`/`size` 검증(`page < 0` 또는 `size` 범위 밖 → `400` `CONDUCT_007`)
2. `dateFrom`/`dateTo` 중 하나만 온 경우 → `400` `CONDUCT_008`(엄격 모드 — `outing`의
   `period` 파라미터 검증과 같은 원칙, `outing/41-outing-query.md` "조회 기간 파라미터
   설계" 참고)
3. `Pageable`로 `type`/`dateFrom`/`dateTo` 필터를 적용한 `Page<ConductRecord>` 조회(DB
   레벨 페이지네이션, 위 "정책 가정" 참고), `PageResponse`로 변환해 반환(`CANCELED` 기록도
   그대로 포함 — 이력이라 취소된 것도 보여야 한다, `status` 필드로 구분)

**에러**
- `dateFrom`/`dateTo` 중 하나만 옴 → `400` `CONDUCT_008`
- `page`/`size` 범위 밖 → `400` `CONDUCT_007`

> 💡 **왜 요약(5번)과 이력(6번)을 나눴나**: 처음엔 한 응답에 `{ summary, records }`로 같이
> 담는 안이었는데, 두 데이터의 "조회 기간" 성격이 서로 다르다 — 누적 점수는 개념상 항상
> 전체 기간 기준이어야 자연스럽고(`dateFrom`/`dateTo`로 좁혀 보면 "이번 달 벌점만 봤더니
> 순위가 이상해짐" 같은 혼란이 생긴다), 이력 목록은 반대로 기간을 좁혀 보는 게 자연스러운
> 용도다. 하나의 엔드포인트에 두 필터 의미가 섞이면 "이 `dateFrom`이 요약에도 적용되나?"
> 하는 모호함이 생기고, 화면도 보통 "이번 학기 총점수" 카드와 "이력 리스트"가 분리돼 있어
> 클라이언트도 대부분 둘을 따로 호출한다 — 원칙 1(한 가지를 잘하기)에 맞춰 분리했다.

---

### 7. `GET /api/v1/conduct-records/summary` — 특정 학생 누적 점수 요약 (교사·선도부·관리자)
**권한**: `TEACHER`, `DISCIPLINE`, `ADMIN`
(`@PreAuthorize("hasRole('TEACHER') or hasRole('DISCIPLINE') or hasRole('ADMIN')")`)

**요청**: 쿼리 파라미터 `studentUserId`(**필수**)

**응답** (`200 OK`) — 5번과 완전히 동일한 구조(대상 학생 기준)

**구현 로직**: 5번과 동일 로직을 `studentUserId`로 지정된 학생 기준으로 수행

**에러**
- `studentUserId`가 없음 → `400`(필수 파라미터 누락, 공통 처리)
- 존재하지 않는 `studentUserId` → `404` `CONDUCT_005`
- 대상이 `STUDENT` 역할이 아님 → `400` `CONDUCT_006`

> 💡 "합산 요약"은 항상 특정 학생 한 명을 기준으로만 의미가 있다(여러 학생을 합친 순 점수는
> 누구 기준인지 불명확해 의미가 없다) — 그래서 8번(이력 목록)과 달리 `studentUserId`를
> 선택이 아니라 필수로 둔다.

---

### 8. `GET /api/v1/conduct-records` — 이력 상세 목록 조회 (교사·선도부·관리자)
**권한**: 7번과 동일(`TEACHER`, `DISCIPLINE`, `ADMIN`)

**요청** (쿼리, 전부 선택)
- `studentUserId` — 특정 학생만 조회(생략 시 전체 학생 대상)
- `type`/`dateFrom`/`dateTo`/`page`/`size` — 6번과 동일 규칙

**응답** (`200 OK`) — 6번과 동일한 `PageResponse<ConductRecordResponse>` 구조. 각 항목에
`studentUserId`/`studentNickname`을 포함해, `studentUserId`를 생략한 "전체 학생" 조회에서도
어느 학생의 기록인지 구분할 수 있게 한다.

**구현 로직**
1. `page`/`size`, `dateFrom`/`dateTo` 검증은 6번과 동일
2. `studentUserId`가 있으면 그 학생으로, 없으면 전체 학생 대상으로 `type`/`dateFrom`/
   `dateTo` 필터를 적용한 `Page<ConductRecord>` 조회(`Pageable`, DB 레벨 페이지네이션)

**에러**: 6번과 동일한 코드 체계(`CONDUCT_007`/`CONDUCT_008`)

## 알림 트리거 (푸시, #37 선행 의존 — 이번 구현 범위 아님)
> `outing` 도메인의 "복귀 리마인더" 절과 같은 이유로, 실제 발송 로직은 이번 마스터 기획 및
> 후속 구현 이슈에 포함하지 않는다 — 이 프로젝트엔 아직 푸시 알림 인프라(FCM 등)가 없다
> (#37, 아직 `OPEN`). 다만 나중에 놓치지 않도록, #37이 끝난 뒤 이 도메인이 무엇을 보내야
> 하는지 지금 기록해 둔다(Notion 기능정의서에도 "구현 예정" 항목으로 남긴다).

필요한 알림 트리거:
1. **부여 시** — 대상 학생에게 즉시 알림(예: "지각(-1점)이 부여되었습니다: 3교시 10분
   지각")
2. **벌점 임계치 도달 시** — 누적 벌점이 `demeritThreshold`를 처음 넘는 순간, 대상 학생 +
   `DISCIPLINE`(선도부) 전원에게 알림(`outing`의 시간 초과 알림이 학생 + 담당 교사/선도부에
   동시에 가는 것과 같은 패턴). "처음 넘는 순간"에만 발송하고 그 이후 벌점이 추가될 때마다
   반복 발송하지는 않는다(가정 — 스팸 방지, 아래 "아직 결정 안 된 것" 참고)
3. **정정/취소 시** — 대상 학생에게 알림(우선순위 낮음, 1/2번과 달리 생략해도 큰 문제는
   없다고 판단 — 최종 확정은 #37 구현 시점에 재검토)

이번 구현(#37 이전) 범위에서는 실제 발송 대신, 5/7번(누적 점수 요약) 응답의
`overDemeritThreshold` 필드로 "기준 초과 여부"만 화면에 보여준다(위 "정책 가정" 참고).

> 💡 서비스 레이어(`ConductService`)의 부여/취소 메서드가 실제 알림 발송 지점이 될
> 것이므로, #37 이전에도 그 지점에 TODO 주석 정도는 남겨두면 나중에 알림 서비스 연동이
> 매끄럽다 — 다만 실제 인터페이스는 #37이 알림 공통 모듈을 어떻게 설계하는지에 따라
> 달라지므로 지금 미리 만들지는 않는다(YAGNI).

## API 설계 6원칙 체크
1. **한 가지를 잘하기**: 부여/정정/취소/카테고리 조회/본인 요약/본인 이력/특정 학생 요약/
   이력 목록 8개로 역할·용도별 유스케이스를 분리했다. 특히 "요약"과 "이력"을 처음엔 한
   응답으로 합쳤다가, 두 데이터의 조회 기간 성격이 달라(요약은 항상 전체 기간, 이력은 기간
   필터가 자연스러움) 분리했다(위 6번 엔드포인트 설명 참고). 본인 조회는 항상 자기 자신이
   대상이라 파라미터가 없어야 하고(다른 학생 조회 시도 자체를 구조적으로 막는 `outing`의
   `/me` 원칙), 특정 학생 대상 조회는 대상이 선택/필수로 갈리는 별도 엔드포인트라 구조가
   달라 분리 유지가 더 명확하다.
2. **빠른 시작**: 모든 엔드포인트에 요청/응답 예시 포함. 카테고리 목록 조회 엔드포인트(4번)
   덕분에 클라이언트가 유효한 `categoryId` 값을 하드코딩하지 않고 바로 받아 쓸 수 있다.
3. **일관성**: 쿼리 파라미터 이름(`dateFrom`/`dateTo`/`page`/`size`)과 `PageResponse` 응답
   포맷은 `outing`(#41)이 확립한 패턴을 그대로 재사용. 역할 검사는 `@PreAuthorize` 선언적
   방식, 소유권 검사는 서비스 코드 — `outing`과 동일한 원칙. 본인용(5/6번)과 교사·관리자용
   (7/8번)이 "요약 엔드포인트 + 이력 엔드포인트" 쌍으로 대칭 구조를 이뤄, 하나의 패턴만
   익히면 둘 다 쓸 수 있다.
4. **의미 있는 오류**: `CONDUCT_002`(소유권 없음)와 `CONDUCT_003`(이미 취소됨)을 분리해
   "권한이 없어서"와 "상태가 안 맞아서"를 구분한다. `CONDUCT_005`(대상 학생 없음)와
   `CONDUCT_006`(학생 역할 아님)도 원인이 달라 분리했다.
5. **확장성/성능**: 이력 목록 조회 2개(6/8번) 모두 DB 레벨 페이지네이션 적용(위 "정책 가정"
   참고). "전체 학생" 조회(8번, `studentUserId` 생략)는 상한 없이 전체 기간을 조회할 수
   있는데, 학년 전체 × 연간 누적이면 outing보다 행 수가 커질 수 있어 **`dateFrom`/`dateTo`
   없이 호출하면 사실상 전체 기간 스캔이 된다** — 아래 "아직 결정 안 된 것"에 실제 운영 시
   기본 기간 강제 필요성을 남긴다. 요약 엔드포인트(5/7번)는 집계 쿼리 한 번뿐이라 목록보다
   훨씬 가볍다.
6. **하위 호환성**: 신규 도메인이라 해당 없음(기존 응답 변경 없음).

## 에러 코드 (`ConductErrorCode`, 신규 패키지 `conduct`)
> `outing`과 동일하게, 아래 번호는 실제 구현 시점에 그 시점의 `ConductErrorCode`에 이미
> 채워진 다음 빈 번호로 확정된다([api-design.md](../../rules/api-design.md) "마스터 기획서
> 재검토" 참고). 이 표는 작성 시점 기준 제안일 뿐이다.
1. `CONDUCT_001` (404) — 상/벌점 기록을 찾을 수 없습니다
2. `CONDUCT_002` (403) — 본인이 부여한 기록만 처리할 수 있습니다
3. `CONDUCT_003` (409) — 이미 취소된 기록입니다
4. `CONDUCT_004` (400) — 존재하지 않거나 비활성화된 카테고리입니다
5. `CONDUCT_005` (404) — 대상 학생을 찾을 수 없습니다
6. `CONDUCT_006` (400) — 대상 사용자가 학생 역할이 아닙니다
7. `CONDUCT_007` (400) — 페이지 파라미터가 유효하지 않습니다
8. `CONDUCT_008` (400) — 날짜 범위 파라미터가 유효하지 않습니다(`dateFrom`/`dateTo` 중
   하나만 옴)

## 공통 구현 고려사항
- **소유권 체크 누락이 가장 위험한 실수 지점**이다(2, 3번) — `outing`과 동일하게, 다른
  교사가 남의 부여 기록을 정정/취소하는 IDOR을 막는 유닛 테스트가 반드시 필요하다.
- **점수는 스냅샷이다** — `ConductCategory` 테이블의 값이 나중에 바뀌어도(이번 범위는
  추가/삭제만이지만) 이미 부여된 기록의 `points`는 바뀌지 않는다(위 "도메인 모델" 참고).
  집계 쿼리는 항상 `ConductRecord.points` 컬럼을 합산하고, `ConductCategory`를 다시 조회해
  곱하지 않는다.
- **카테고리 삭제는 소프트 삭제(`active = false`)뿐이다** — 하드 삭제 API는 제공하지
  않는다. `ConductRecord.category_id`가 그 행을 계속 정상적으로 참조하므로 FK 무결성이
  깨지지 않는다(위 "도메인 모델" 참고).
- **시간 관련 로직은 KST 고정**(`ZoneId.of("Asia/Seoul")`) — `outing`/`meal`/`timetable`과
  동일한 기존 패턴 재사용.
- **`ConductProperties`(`@ConfigurationProperties(prefix = "conduct")`, 신규)**: 누적 벌점
  임계치 설정값. `OutingProperties`(학교 좌표)와 같은 패턴 — `demeritThreshold`(가정: `10`,
  아래 "아직 결정 안 된 것" 참고).
- **동시성**: 이 도메인은 `outing`의 "겹침 확인" 같은 check-then-act 레이스가 없다(부여는
  단순 삽입, 정정/취소는 각각 자신의 `id` 기준 단건 갱신이라 서로 다른 기록끼리는 애초에
  충돌 여지가 없다). 같은 기록에 대한 동시 정정/취소 이중 클릭 정도는 낙관적 락
  (`@Version`, `outing`이 #8에서 이미 도입한 패턴, `V8__add_outing_version.sql` 참고)으로
  충분하다 — 새로운 락 전략을 도입할 필요 없음.

## 아직 결정 안 된 것 (리뷰 필요)
- **카테고리 초기 시드 목록/점수(위 "도메인 모델" 표)는 전부 가정값이다.** 실제 학교 상/벌점
  규정표와 대조해 항목 추가/삭제, 점수 조정이 필요할 가능성이 높다. (부여 권한을 `ADMIN`
  화면에서 직접 관리하는 기능 자체는 확정 — 아래 항목 참고 — 다만 그 화면이 만들어지기
  전까지는 이 표를 시드 데이터로 그대로 쓴다.)
- **카테고리 추가/삭제(관리자 전용) API/화면의 세부 설계** — "DB 테이블로 관리하고 나중에
  관리자가 추가/삭제한다"는 방향은 확정됐지만(위 "정책 가정" 참고), 실제 엔드포인트
  경로/권한/화면은 별도 웹 관리자 페이지 이슈에서 다룬다(이번 마스터 기획 범위 밖).
- **누적 벌점 임계치 값(가정: 10점)** — 실제 운영 기준 확인 필요. 상점에도 임계치(예: 특정
  점수 이상 시 표창 후보 안내)가 필요한지도 이번엔 다루지 않았다.
- **벌점 임계치 알림을 "처음 초과하는 순간에만" 보낼지** — 위 "알림 트리거" 절의 가정이다.
  추가 벌점마다 매번 재발송할지, 일정 간격으로 재알림할지는 실제 알림 인프라(#37) 설계
  시점에 다시 정해야 한다.
- **이의신청(소명) 프로세스** — 위 "정책 가정"대로 이번 범위에서 제외했다. 필요하면 별도
  이슈로 분리한다.
- **"전체 학생" 조회(8번, `studentUserId` 생략)에 기본 기간 강제가 필요한지** — 위 "API 설계
  6원칙 체크" 5번 참고. 데이터가 실제로 쌓인 뒤 성능이 문제 되면 `outing`의 `/outings?date&status`
  처럼 기본값을 "오늘"/"이번 달"로 강제하는 방향을 재검토한다.
- **정정 이력(누가 언제 무엇을 고쳤는지)을 별도로 남길지** — 지금은 `updated_at`만 갱신되고
  이전 값은 남지 않는다. 감사 요구가 커지면 별도 이력 테이블을 후속 이슈로 고려할 수 있다.
- **푸시 알림(#37) 연동 시점** — #37이 아직 `OPEN`(진행 전) 상태라, 이 도메인의 임계치
  알림은 그 이슈가 끝난 뒤에야 실제 발송으로 이어질 수 있다.
