# #97 외출증 위치/동선 조회 + 위치 핑 전송 API — 기획서

관련 이슈: [#97 외출증 위치/동선 조회 + 위치 핑 전송 API](https://github.com/GBSW-ReMake/GONE-server-V1/issues/97)
마스터 기획서: [1_outing-domain.md](./1_outing-domain.md) (9/10번 엔드포인트)
선행 이슈: #43(출발/도착 보고, 완료) — `DEPARTED` 상태와 `departed_latitude/longitude` 등
시작점 데이터가 이미 존재함
선행 코드: [`OutingController`](../../../src/main/java/com/remake/gone/outing/controller/OutingController.java)/
[`OutingService`](../../../src/main/java/com/remake/gone/outing/service/OutingService.java)/
[`OutingLocationRequest`](../../../src/main/java/com/remake/gone/outing/dto/OutingLocationRequest.java)

## 개요/목적
학생 앱이 외출 중(`DEPARTED`) 주기적으로 위치를 전송하는 "쓰는 쪽"과, 담당 선생님/`DISCIPLINE`
이 실시간 위치와 종료 후 전체 동선을 보는 "읽는 쪽"을 한 이슈로 같이 다룬다. 읽는 쪽 응답은
**시간순으로 정렬된 좌표 배열**이어야 한다 — 클라이언트가 이 배열을 순서대로 이어 폴리곤(선)으로
렌더링한다(보스 확정, 2026-08-22). 지도 렌더링 자체는 서버 범위 밖이다.

## 마스터 기획서 재검토 (api-design.md "마스터 기획서 재검토" 원칙 적용)
- **응답 필드명 `outingId` → `code`로 정정**: 마스터 기획서의 9번 엔드포인트 응답 예시는
  `"outingId": 501`(내부 PK로 보이는 정수값)을 쓴다. 이는 #29/#30에서 "내부 PK를 노출하지
  않고 외부 식별자 `code`만 노출한다"는 정책이 확정되기 전에 쓰인 초안이다(기존
  `OutingResponse`/`OutingActiveResponse` 등 이후 만들어진 모든 응답 DTO가 전부 `code`만
  쓰고 내부 `id`를 노출하지 않는다). 이 정책을 그대로 이어받아 응답 필드명을 `outingId`가
  아니라 `code`로 바꾼다.
- **조회 권한에 `ADMIN` 포함**: 마스터 기획서는 "담당 선생님 본인, 또는 `DISCIPLINE` — 이
  둘만(전체 교사 아님)"이라고 적었다. 이 프로젝트의 일반 원칙(`ADMIN`은 공통 전제로 항상
  접근 가능, #96 기획서에서 재확인)에 따라 `ADMIN`도 포함한다 — 마스터 기획서의 "전체 교사
  아님"이라는 강조는 *담당 아닌 일반 TEACHER를 배제*하려는 의도였지 `ADMIN`을 배제하려는
  의도가 아니었다고 판단한다(같은 컨트롤러의 다른 형제 엔드포인트들이 전부 `ADMIN`을
  명시적으로 포함하는 것과의 일관성).
- **위치 핑 최소 간격 검증 — 도입하지 않는다(보스 확정)**: 이슈 초안이 제기한 질문("클라이언트가
  1분 주기를 지킨다는 보장이 없는데 서버가 막아야 하는가")에 대해, 지금 단계에서는 검증 없이
  전부 저장하기로 확정했다 — 마스터 기획서 자체가 이미 "지금 단계에서 미리 최적화하지 않는다
  (YAGNI)"는 원칙을 명시하고 있고, 학생 수·핑 빈도가 지금 규모에서 문제가 되지 않는다. 나중에
  실제로 문제가 되면 배치 전송/큐잉으로 최적화한다(마스터 기획서에 이미 명시된 대안).
- **위치 데이터 접근 로그 — 이번 범위에서 제외, 별도 이슈로 분리(보스 확정)**: "누가 언제 이
  학생의 동선을 조회했는지"는 개인정보 접근이라 감사 필요성이 있지만, 이를 위한
  `AuditLog`/`@Audited` 인프라(`docs/domain/admin/1_admin-domain.md`)가 아직 코드로 구현된
  적이 없어 #97 하나를 위해 처음부터 만들면 범위가 크게 늘어난다. 별도 이슈(#115)로 분리했다.
- **출발/도착 좌표를 동선의 시작/끝점으로 재사용(보스 확정)**: `#43`의
  `departed_latitude/longitude`/`returned_latitude/longitude`를 `path` 배열의 첫 점/마지막
  점으로 재사용한다. 별도 `OutingLocation` INSERT 없이 GET 응답 조립 시점에만 합성한다 —
  폴리곤의 첫/마지막 점이 항상 정확히 출발/도착 시각의 좌표가 되어 경로가 자연스럽게
  완결된다.

## 데이터 모델 변경

### `OutingLocation` 엔티티 (신규, `outing_location` 테이블)
| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGINT PK | |
| outing_id | BIGINT FK → outing.id | |
| latitude | DOUBLE | |
| longitude | DOUBLE | |
| recorded_at | DATETIME | 서버가 핑을 수신한 시각(클라이언트 시각 아님, 아래 참고) |

인덱스: `(outing_id, recorded_at)` — 조회가 항상 특정 `outing_id` 하나로 좁혀지고 그 안에서
시간순 정렬하므로, 마스터 기획서가 제시한 인덱스를 그대로 채택한다.

마이그레이션: `V{타임스탬프}__add_outing_location.sql`(버전 형식은
[migration-convention.md](./migration-convention.md) 참고, 커밋 시점 KST 타임스탬프로 확정)

## 엔드포인트

### 1. `POST /api/v1/outings/{code}/locations` — 위치 핑 전송
**권한**: 그 외출증의 신청 학생 본인(`@PreAuthorize("hasRole('STUDENT')")` + 서비스에서
소유권 확인, 기존 `depart`/`return`과 동일한 패턴)

**요청**
```http
POST /api/v1/outings/8A1zx9202n/locations
```
`OutingLocationRequest`(기존 DTO 재사용, #43의 `depart`/`return`과 완전히 동일한 스키마 —
새 DTO를 만들지 않는다):
```json
{
  "latitude": 36.1234,
  "longitude": 128.4321
}
```

**응답** (`200 OK`, 매 핑마다 무거운 응답 불필요 — 마스터 기획서 그대로)
```json
{
  "success": true,
  "data": null,
  "message": "위치가 저장되었습니다.",
  "code": null
}
```

**구현 로직** (`OutingService.recordLocationPing`)
1. `code`로 `Outing` 조회(`findByCode`), 없으면 404 `OUTING_006` `OUTING_NOT_FOUND`
2. 소유권 확인(기존 `validateOwnership` 재사용), 아니면 403 `OUTING_007` `ACCESS_DENIED`
3. `status == DEPARTED`가 아니면 409 `OUTING_016` `NOT_DEPARTED_STATUS`(신규 에러 코드 —
   "아직 처리 안 됨"을 뜻하는 기존 `ALREADY_PROCESSED`와 원인이 달라 코드를 분리한다.
   api-design.md 4번 원칙)
4. `latitude`/`longitude` 범위 검증은 `OutingLocationRequest`의 기존 Bean Validation이
   처리(400)
5. 학교 반경 검증은 하지 않는다 — `depart`/`return`과 달리 핑은 "외출 중" 상태를 계속
   기록하는 것이라, 학교 밖에 있는 게 정상이다
6. `OutingLocation` 저장, `recordedAt`은 서버가 수신한 `now`로 채운다(클라이언트 시각을
   신뢰하지 않는 이유는 마스터 기획서 참고 — 기기 시계 오차/조작 가능성)

**에러**
- 401 `COMMON_002` UNAUTHORIZED
- 403 `COMMON_003` FORBIDDEN — `STUDENT` 역할이 아님
- 403 `OUTING_007` `ACCESS_DENIED` — 본인 외출증이 아님(IDOR 방지)
- 404 `OUTING_006` `OUTING_NOT_FOUND`
- 409 `OUTING_016` `NOT_DEPARTED_STATUS` — 그 외출증이 `DEPARTED` 상태가 아님(아직 출발
  전이거나 이미 도착)
- 400 `COMMON_001` — 좌표 범위 밖(`-90~90`/`-180~180`)

### 2. `GET /api/v1/outings/{code}/locations` — 위치/동선 조회
**권한**: 그 외출증에 지정된 담당 선생님 본인, 또는 `DISCIPLINE`/`ADMIN`(전체 `TEACHER`
아님 — 위 "마스터 기획서 재검토" 참고). `@PreAuthorize("isAuthenticated()")` +
서비스에서 소유권/역할 확인(기존 `getOutingDetail`과 동일한 패턴).

**요청**
```http
GET /api/v1/outings/8A1zx9202n/locations
```

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": {
    "code": "8A1zx9202n",
    "status": "DEPARTED",
    "path": [
      { "latitude": 36.1234, "longitude": 128.4321, "recordedAt": "2026-08-14T12:31:05" },
      { "latitude": 36.1230, "longitude": 128.4325, "recordedAt": "2026-08-14T12:32:05" },
      { "latitude": 36.1229, "longitude": 128.4322, "recordedAt": "2026-08-14T12:33:10" }
    ]
  },
  "message": "위치 조회에 성공했습니다.",
  "code": null
}
```

**구현 로직** (`OutingService.getOutingLocations`)
1. `code`로 `Outing` 조회, 없으면 404 `OUTING_006` `OUTING_NOT_FOUND`
2. 접근 권한 확인: `principal.userId() == outing.teacher.id`이거나 보유 역할에
   `DISCIPLINE`/`ADMIN`이 있으면 통과, 아니면 403 `OUTING_007` `ACCESS_DENIED`
3. `outingLocationRepository.findByOutingIdOrderByRecordedAtAsc(outing.id)` 조회
4. `path` 배열 조립: `departedAt`/`departed_latitude/longitude`가 있으면 첫 점으로,
   `OutingLocation` 목록을 시간순으로, `returnedAt`/`returned_latitude/longitude`가
   있으면 마지막 점으로 합성(모두 `recordedAt` 오름차순 유지 — 출발 시각이 첫 핑보다
   항상 이르고 도착 시각이 마지막 핑보다 항상 늦으므로 정렬이 깨지지 않는다)
5. 페이지네이션 없음(마스터 기획서 판단 유지 — 1분 간격 기준 시계열 데이터량이 작고,
   `outing_id`로 항상 좁혀지는 조회라 무제한 누적 우려가 없다)

**에러**
- 401 `COMMON_002` UNAUTHORIZED
- 403 `OUTING_007` `ACCESS_DENIED` — 담당 선생님/`DISCIPLINE`/`ADMIN` 중 어느 것도 아님
- 404 `OUTING_006` `OUTING_NOT_FOUND`

## 신규 에러 코드
- `OUTING_016` `NOT_DEPARTED_STATUS`(409) — "그 외출증이 `DEPARTED` 상태가 아닙니다."

## 영향 받는 기존 코드/테스트
- 신규: `outing.entity.OutingLocation`, `outing.repository.OutingLocationRepository`,
  `outing.dto.OutingLocationPointResponse`, `outing.dto.OutingLocationsResponse`,
  `OutingController.recordLocationPing`/`getOutingLocations`,
  `OutingService.recordLocationPing`/`getOutingLocations`, 인덱스 포함 마이그레이션 1개,
  `OutingErrorCode.NOT_DEPARTED_STATUS`
- 재사용(변경 없음): `OutingLocationRequest`(#43), `OutingService.validateOwnership`,
  `Outing.departedAt/departedLatitude/departedLongitude/returnedAt/returnedLatitude/
  returnedLongitude`(#43)
- 변경 없음: `Outing` 엔티티(컬럼 추가 없음), 기존 다른 엔드포인트

## 리스크 및 고려사항
- **API 설계 6원칙**:
  1. 한 가지를 잘하기: "쓰는 쪽"(핑 전송)과 "읽는 쪽"(동선 조회)이 강하게 묶여 있어 마스터
     기획서와 동일하게 한 이슈로 다루지만, 엔드포인트 자체는 각각 하나의 목적만 수행 — 부합.
  4. 의미 있는 오류: `NOT_DEPARTED_STATUS`를 `ALREADY_PROCESSED`와 분리해 원인을 명확히
     구분.
  5. 확장성/성능: 핑 최소 간격 미검증 + 접근 로그 미도입은 위 "마스터 기획서 재검토"에서
     이유와 함께 명시. 인덱스는 반영.
  6. 하위 호환성: 새 엔드포인트라 기존 응답에 영향 없음.
- **위치 데이터는 개인정보이며 무기한 보관이 이미 확정된 상태**(마스터 기획서) — 삭제
  정책은 이번 이슈 범위 밖이며, 마스터 기획서도 동일하게 다루지 않는다고 명시했다.
- **접근 로그(#115)가 없는 채로 먼저 배포되는 데이터 노출 기간**: #115가 완료되기 전까지는
  "누가 조회했는지" 기록이 없다. 개인정보 특성상 이 공백 기간이 보스가 받아들일 수 있는
  수준인지 확인이 필요하다 — 이미 확정됐지만(위 질문 3), 재확인 차원에서 남긴다.

## 테스트
- `OutingServiceTest.RecordLocationPing`(신규 `@Nested`):
  - 정상 핑 저장(`DEPARTED` 상태, 소유권 일치) 시 `OutingLocation` 저장 확인
  - 본인 외출증이 아니면 `OUTING_007`
  - `DEPARTED`가 아닌 상태(`PENDING`/`APPROVED`/`RETURNED`)에서 시도 → `OUTING_016`
  - 존재하지 않는 code → `OUTING_006`
  - 최소 간격 검증 없이 연속 핑이 전부 저장되는지 확인(짧은 간격 두 번 호출 → 두 건 모두
    저장)
- `OutingServiceTest.GetOutingLocations`(신규 `@Nested`):
  - 담당 선생님 본인 조회 성공
  - `DISCIPLINE`/`ADMIN` 조회 성공(담당 아니어도)
  - 담당 아닌 일반 `TEACHER` 조회 시도 → `OUTING_007`
  - `path`가 `departedAt` 좌표 → `OutingLocation` 목록(시간순) → `returnedAt` 좌표 순으로
    조립되는지 확인
  - `returnedAt`이 아직 없으면(진행 중) 마지막 점이 최근 핑까지만 포함되는지 확인
  - 존재하지 않는 code → `OUTING_006`
- `OutingControllerTest.RecordLocationPing`/`GetOutingLocations`(신규 `@Nested`): 요청
  검증(좌표 범위 밖 400), principal·파라미터 전달 확인
- `OutingLocationOwnershipIntegrationTest`(신규, 실 DB 기반): 본인 아닌 학생이 핑 전송
  시도 → 403(기존 `OutingDepartReturnOwnershipIntegrationTest`와 동일한 패턴), 담당
  아닌 `TEACHER`가 조회 시도 → 403, `DISCIPLINE`은 조회 성공

## 완료 조건 (Definition of Done)
- 로컬 빌드/테스트 통과
- CI 통과
- Postman 컬렉션 반영
- (해당 시) Notion 기능정의서 반영
