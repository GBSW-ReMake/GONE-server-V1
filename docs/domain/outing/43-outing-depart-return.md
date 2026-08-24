# #43 외출증 출발/도착 보고 API — 기획서

관련 이슈: [#43 외출증 출발/도착 보고 API + 진행중/완료 외출 관리 화면](https://github.com/GBSW-ReMake/GONE-server-V1/issues/43)
마스터 기획서: [1_outing-domain.md](./1_outing-domain.md) (4/5번 엔드포인트)
선행 코드: [`OutingController`](../../../src/main/java/com/remake/gone/outing/controller/OutingController.java)/
[`OutingService`](../../../src/main/java/com/remake/gone/outing/service/OutingService.java)/
[`Outing`](../../../src/main/java/com/remake/gone/outing/entity/Outing.java)/
[`NeisProperties`](../../../src/main/java/com/remake/gone/neis/config/NeisProperties.java)(설정값 전용
`*.config` 패키지 전례)

## 개요/목적
`Outing` 엔티티는 #29에서 이미 `departed_at`/`returned_at` 컬럼을 만들어뒀지만, 실제로 그 상태로
전이시키는 엔드포인트가 아직 없다. 학생이 "출발"/"도착" 버튼을 눌러 본인 외출증을
`APPROVED → DEPARTED → RETURNED`로 직접 보고하는 엔드포인트 2개를 추가한다.

**이번 이슈 범위(보스 확정)**: 마스터 기획서 11개 엔드포인트 중 4/5번(출발/도착 보고)만
다룬다. 8/9/10번(실시간 목록/위치·동선 조회/위치 핑 전송)은 범위 밖 — 별도 이슈로 분리한다
(아래 "리스크 및 고려사항" 참고). 지도 API 연동도 이번 범위에 없다 — 학교 반경 판정은 서버가
좌표 두 점 사이 거리를 계산하는 것뿐이라 지도 SDK/API 연동 자체가 필요 없다(지도 렌더링은
나중에 위치조회 화면을 만들 안드로이드/iOS 앱의 몫이며 이 서버 레포 범위가 아니다).

## 확정된 논의 사항
- **학교 반경**: `200m`로 확정(기획서 초안의 가정값 그대로 유지). **실제 학교 좌표
  (`schoolLatitude`/`schoolLongitude`)는 아직 미정** — 지도 서비스로 정확히 측정 후
  배포 전 환경변수로 채워 넣는다(아래 "환경변수/시크릿" 참고, `NEIS_*`/`ALIGO_*`와 동일하게
  로컬 `application-dev.yml`(git 미포함)엔 더미 값만 둔다).
- **위치 핑 주기**: 1분으로 확정. 다만 이번 이슈 범위에는 핑 전송 엔드포인트(10번) 자체가
  없어서 지금 당장 쓰이진 않는다 — 후속 위치조회 이슈에서 실제로 적용될 값을 미리
  기록해둔다.
- **위치 데이터 보존 기간**: 무기한 보관으로 확정(자동 삭제 스케줄러 없음).
- **이슈 범위**: 출발/도착 보고 API만 먼저 좁힌다. 위치조회(8/9/10번)는 별도 이슈.
- **출발/도착 보고 흐름 자체는 초안대로 유지**: 학생이 직접 버튼을 눌러 자가보고하고
  서버가 GPS 좌표로 학교 반경만 검증하는 현재 방식을 그대로 쓴다(보스 재검토 후 확정,
  2026-08-24). 위치 조작(GPS 모킹) 가능성은 있으나 아래 "리스크 및 고려사항"에 이미 인지된
  한계로 남겨두고, 선생님 승인 단계 추가나 위치 핑 기반 자동 감지 같은 대안은 채택하지
  않는다 — 전자는 자가보고 취지와 마찰이 크고, 후자는 이미 범위 밖으로 뺀 8/9/10번을 다시
  끌어들이는 것이라 기각.
- **시간대(startTime~endTime) 밖 출발/도착 보고는 차단하지 않고 허용한다**(보스 확정,
  2026-08-24). 예정 시간과 무관하게 실제로 출발/도착이 일어날 수 있어(조퇴·지각 등) 막을
  이유가 없다고 판단. 다만 응답에 `offSchedule` 플래그와 별도 안내 메시지를 포함해 시간대를
  벗어났음을 알린다(아래 엔드포인트 절 참고).
- **학교 운영시간 게이트를 별도로 추가한다**(보스 확정, 2026-08-24). 위 `offSchedule`은
  "이 외출증 자체의 시간대"를 벗어난 경우를 허용하는 것이고, 이것과 별개로 **운영시간 자체를
  벗어나면 "외출"이라는 개념이 성립하지 않으므로 호출 자체를 차단한다.** 범위는 새 상수를
  만들지 않고 기존 `OutingTimeSlot.CUSTOM_WINDOW_START`(08:40)~`CUSTOM_WINDOW_END`(20:30)를
  그대로 재사용한다 — 이 범위가 이미 "이 학교에서 외출이 가능한 시간의 최대 범위"라는 의미로
  #29에서 정의돼 있어, 새 개념을 또 만들 필요가 없다. 벗어나면 `400` `OUTING_010`.
- **`OutingTimeSlot.DINNER` 종료 시각 버그 수정을 이번 이슈에 포함한다**(보스 확정,
  2026-08-24). 현재 코드는 `DINNER(18:10, 21:10)`(3시간)로 되어 있으나 실제 의도는
  `18:10~19:10`(1시간, `LUNCH`의 1시간10분과 비슷한 길이)이다. #29 기획 당시 모호했던 원문
  ("점심시간은 6:10~9:10까지야")을 "저녁 자율학습 시간대(18~21시)"로 잘못 해석해 21:10을
  넣었고, 문서에 "혹시 다른 의도였다면 알려달라"는 확인 요청이 남아있었지만 확인 없이 그대로
  구현/머지됐다(#29). 위 운영시간 게이트를 설계하던 중 `DINNER` 종료 시각(21:10)이 운영시간
  상한(20:30)을 넘는 모순을 발견하면서 드러났다. `endTime`을 `19:10`으로 수정하면 모든
  프리셋(`LUNCH`/`DINNER`)과 `CUSTOM` 허용 범위가 08:40~20:30 안에 완전히 들어와 운영시간
  게이트와 충돌하지 않는다. **전환기 확인(보스 확인, 2026-08-24)**: 현재 옛 규칙(종료
  21:10)으로 신청된 `PENDING`/`APPROVED` 상태의 `DINNER` 외출증이 존재하지 않아, 이미
  승인된 외출증이 새 운영시간 게이트에 막히는 전환기 문제 자체가 없다 — 별도 마이그레이션/
  데이터 백필/구조적 예외 처리 없이 그대로 배포한다. 새로 신청되는 건부터 `19:10`이 적용된다.

## 마스터 기획서 재검토 (api-design.md 참고)
- **권한 체크 위치**: 마스터 기획서는 "아직 `@EnableMethodSecurity` 인프라가 없어 서비스
  코드에서 직접 역할을 검사한다"고 가정했지만, 이후 #30에서 이미 도입되어 형제 엔드포인트
  (`approveOuting`/`getMyRequests`/`getReceivedOutings`)가 전부 컨트롤러의
  `@PreAuthorize("hasRole(...)")`를 쓰고 있다. 이번 엔드포인트도 같은 패턴을 따른다
  (`@PreAuthorize("hasRole('STUDENT')")`) — 서비스 코드에서 역할을 직접 검사하지 않는다.
  소유권(본인 외출증인지)만 기존 원칙대로 서비스에서 `if`로 검사한다.
- **응답 DTO**: 마스터 기획서는 4/5번에 `code`/`status`/`departedAt`만 담은 최소 응답을
  제시했지만, 실제 구현된 승인(#30)/거절(#31)은 신청(#29)과 같은 풀 `OutingResponse`를
  그대로 재사용한다. 이번에도 같은 패턴을 따른다 — 새 응답 타입을 만들지 않는다(일관성).
  다만 #43 이슈 본문에 명시된 "완료된 외출은 출발~도착 시각을 명시해야 한다"는 확정
  요구사항을 만족시키려면 `OutingResponse`에 `departedAt`/`returnedAt` 필드가 필요해
  **추가한다**(API 설계 6원칙 6번 "하위 호환성" — 필드 추가는 항상 안전한 확장). 같은 이유로
  시간대 밖 보고 여부를 프론트가 프로그래밍적으로 판단할 수 있도록 `offSchedule`(불리언)
  필드도 함께 추가한다.
- **에러 코드**: 마스터 기획서는 "본인 외출증 아님"에 별도 `OUTING_007`을 새로 쓰는 것처럼
  보이지만, 실제로 `OUTING_007`은 이미 #41에서 "이 외출증에 접근할 권한이 없습니다"(단건
  조회 접근 거부)로 채워져 있다. 의미가 동일(IDOR 방지, 관계없는 사용자의 접근 거부)하므로
  그대로 재사용한다 — 새 코드를 만들지 않는다.

## 엔드포인트

### 1. `POST /api/v1/outings/{code}/depart` — 출발 보고 (학생 본인)
**권한**: `STUDENT` (`@PreAuthorize`) + 본인이 그 외출증의 `student`인지 서비스에서 소유권 확인

**요청**
```json
{ "latitude": 36.1234, "longitude": 128.4321 }
```

**응답** (`200 OK`) — 기존 `OutingResponse`와 동일 구조, `"status": "DEPARTED"`,
`departedAt`에 값이 채워짐(`returnedAt`은 여전히 `null`). 예정된 시간대(`startTime`~
`endTime`) 밖에서 보고했으면 `offSchedule: true`와 함께 안내 메시지가 담긴다(차단하지는
않음 — 아래 "시간대 밖 출발" 예시 참고)
```json
{
  "success": true,
  "data": {
    "code": "8A1zx9202",
    "studentNickname": "길동이",
    "studentProfileImageUrl": "https://.../profile/1/abc.jpg?X-Amz-...",
    "studentRealName": "홍길동",
    "studentGrade": 3,
    "studentClassNo": 4,
    "teacherName": "김선생",
    "reason": "치과 진료",
    "outingDate": "20260814",
    "timeSlot": "LUNCH",
    "startTime": "12:30",
    "endTime": "13:40",
    "status": "DEPARTED",
    "rejectedReason": null,
    "departedAt": "2026-08-14T12:31:05",
    "returnedAt": null,
    "offSchedule": false
  },
  "message": "출발이 기록되었습니다.",
  "code": null
}
```

**시간대 밖 출발 예시** — 위와 동일 요청이지만 `departedAt`이 `endTime`(13:40) 이후일 때
```json
{
  "success": true,
  "data": { "...": "...", "status": "DEPARTED", "departedAt": "2026-08-14T14:10:00", "offSchedule": true },
  "message": "예정된 시간 외에 출발이 기록되었습니다.",
  "code": null
}
```

**구현 로직** (`OutingService.departOuting`)
1. `code`로 `Outing` 조회(`findByCode`), 없으면 `404` `OUTING_006`
2. `outing.getStudent().getId().equals(studentUserId)` 확인, 아니면 `403` `OUTING_007`
3. **운영시간 검증**: 현재 시각이 `OutingTimeSlot.CUSTOM_WINDOW_START`(08:40)~
   `CUSTOM_WINDOW_END`(20:30) 밖이면 `400` `OUTING_010`(외출증 상태와 무관하게 가장 먼저
   차단 — 운영시간 밖에서는 "외출" 개념 자체가 성립하지 않으므로). **경계값은 포함
   (inclusive)** — 정확히 `08:40:00`/`20:30:00`이면 허용, `08:39:59`/`20:30:01`부터 차단
4. `outing.getStatus() == APPROVED` 확인, 아니면 `409` `OUTING_005`
5. **위치 검증**: `GeoUtils.distanceMeters(request.latitude(), request.longitude(),
   outingProperties.schoolLatitude(), outingProperties.schoolLongitude())`가
   `outingProperties.schoolRadiusMeters()`를 초과하면 `400` `OUTING_009`
6. `status = DEPARTED`, `departedAt = now`, `departureLatitude`/`departureLongitude` 저장
7. **시간대 판정**: `departedAt`이 `outingDate`+`startTime`~`outingDate`+`endTime` 범위 밖이면
   `offSchedule = true`(차단하지 않고 기록만 함) — 이 값은 저장하지 않고 `toResponse` 변환
   시점에 매번 계산한다(별도 컬럼 불필요, 항상 `startTime`/`endTime`/`departedAt`으로
   재계산 가능). **경계값은 운영시간과 동일하게 포함(inclusive)** — 정확히 `startTime`/
   `endTime` 그 순간이면 `offSchedule = false`. 3단계 운영시간 게이트를 이미 통과한
   뒤이므로, `offSchedule`이 발생하는 경우는 항상 "운영시간 안이지만 이 외출증 자체의
   시간대는 벗어난" 경우로 한정된다(예: 점심 외출인데 저녁 시간대까지 안 돌아와 19시에
   도착 보고)
8. `toResponse(...)`로 변환해 반환(`offSchedule`도 함께 채움). 컨트롤러는 `offSchedule` 값에
   따라 `ApiResponse.success` 메시지를 "출발이 기록되었습니다." 또는 "예정된 시간 외에
   출발이 기록되었습니다."로 분기한다

**에러**
- 존재하지 않는 `code` → `404` `OUTING_006`
- 본인 외출증이 아님 → `403` `OUTING_007`
- 학교 운영시간(08:40~20:30) 밖에서 호출 → `400` `OUTING_010`
  ("학교 운영시간(08:40~20:30) 외에는 출발/도착을 보고할 수 없습니다.")
- `APPROVED` 상태가 아님(아직 승인 전이거나 이미 출발/도착/거절 처리됨) → `409` `OUTING_005`
- 학교 반경 밖에서 시도 → `400` `OUTING_009`
  ("학교 반경을 벗어난 위치에서는 출발/도착 처리를 할 수 없습니다.")

---

### 2. `POST /api/v1/outings/{code}/return` — 도착 보고 (학생 본인)
**권한**: 1번과 동일

**요청/응답**: 1번과 동일 구조, `"status": "RETURNED"`, `returnedAt`에 값 채워짐.
`offSchedule` 판정도 1번과 동일하게 적용(도착이 `endTime` 이후거나 `startTime` 이전이면
`true`), 메시지는 "도착이 기록되었습니다." / "예정된 시간 외에 도착이 기록되었습니다."로
분기

**구현 로직**: 1번과 동일하되(운영시간 게이트 포함) 4단계 상태 조건이 `DEPARTED`, 6단계가
`status = RETURNED`, `returnedAt = now`, `returnLatitude`/`returnLongitude` 저장

**에러**: 1번과 동일한 코드 체계(운영시간 `OUTING_010` 포함), 상태 조건만 `DEPARTED`가
아니면 `409` `OUTING_005`

---

## "진행중/완료 외출 관리 화면"은 새 엔드포인트가 필요 없음
#43 이슈 본문의 "선생님이 진행 중/완료된 외출을 조회"는 **이미 #41에서 만든
`GET /outings/me/received?status=`로 충분**하다 — 새 엔드포인트를 만들지 않는다. 다만 지금은
`OutingQueryStatus`(조회 `status` 필터 전용 enum)에 `DEPARTED`/`RETURNED`가 빠져 있다
(#41 구현 당시 "그 상태에 도달할 방법이 없어 죽은 옵션을 안 만든다"고 의도적으로 제외했고,
그 문서 주석에 "나중에 그 엔드포인트가 생겼을 때 추가한다"고 이미 예고되어 있음 —
`OutingQueryStatus.java` 상단 Javadoc 참고). 이번 이슈에서 그 "나중"이 왔으므로
`DEPARTED`/`RETURNED` 두 값을 추가한다. 이 값들이 추가되면:
- `GET /outings/me/received?status=DEPARTED` → 선생님이 "진행 중인 외출" 조회
- `GET /outings/me/received?status=RETURNED` → 선생님이 "완료된 외출" 조회(`departedAt`~
  `returnedAt` 시각이 응답에 포함됨 — 위 "마스터 기획서 재검토" 참고)

## 데이터 모델 변경
### `Outing` 엔티티에 컬럼 4개 추가 (`db/migration/V17__add_outing_depart_return_location.sql`)
(애초 `V16`으로 작성했으나, PR 생성 후 CI에서 `dev`에 먼저 병합된 다른 PR(#94 상/벌점
부여)이 이미 `V16__add_conduct_record.sql`을 쓰고 있어 버전이 겹치는 것을 발견해
`V17`로 재번호했다 — 초안의 `V9`도 이미 `V9__add_notification.sql`이 쓰고 있어 번호가
겹쳤던 것과 같은 종류의 문제다.)
`departed_at`/`returned_at` 명명 규칙과 동일하게 맞춘다.
```sql
ALTER TABLE outing
    ADD COLUMN departed_latitude DOUBLE NULL,
    ADD COLUMN departed_longitude DOUBLE NULL,
    ADD COLUMN returned_latitude DOUBLE NULL,
    ADD COLUMN returned_longitude DOUBLE NULL;
```
- 출발/도착 그 순간의 좌표를 증거로 남긴다. 지금 당장 이 값을 응답으로 내려주거나 조회하는
  엔드포인트는 없다(8/9/10번은 별도 이슈) — 그래도 저장은 지금부터 시작한다. 나중에
  위치조회 이슈가 왔을 때 이 두 점(출발/도착)을 동선의 시작/끝점으로 재사용할 수 있다.
- **`OutingLocation`(시계열 위치 테이블)은 이번 이슈에서 만들지 않는다** — 지금 아무도
  시계열 데이터를 읽지 않는데 미리 테이블/엔티티/리포지토리를 만드는 건 YAGNI 위반이다.
  10번(위치 핑) 엔드포인트가 생기는 후속 이슈에서 그 테이블을 새로 만들고, 필요하면 그때
  이 4개 컬럼과의 관계도 같이 정리한다.

### `OutingProperties` (`outing.config.OutingProperties`, 신규)
`NeisProperties`와 동일한 패턴(설정값만 담는 레코드, Bean이 필요 없어 짝이 되는 `*Config`
클래스는 없음).
```java
package com.remake.gone.outing.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "outing")
@Validated
public record OutingProperties(
    @NotNull Double schoolLatitude,
    @NotNull Double schoolLongitude,
    @NotNull @Positive Integer schoolRadiusMeters
) {}
```
`@ConfigurationPropertiesScan`이 프로필과 무관하게 항상 이 레코드를 등록하므로(기존
`NeisProperties`/`AligoProperties`와 동일), `dev`/CI에서도 유효성 검증을 통과할 더미 값이
필요하다 — 아래 "환경변수/시크릿" 참고.

## 환경변수/시크릿
- `OUTING_SCHOOL_LATITUDE` → `outing.school-latitude`
- `OUTING_SCHOOL_LONGITUDE` → `outing.school-longitude`
- `OUTING_SCHOOL_RADIUS_METERS` → `outing.school-radius-meters` (확정값: `200`)

실제 좌표값은 아직 없음 — **배포 전(staging 배포 시점) 정확한 좌표를 지도 서비스로 측정해
GitHub Environment "STAGING"에 시크릿으로 등록해야 한다.** 로컬 `application-dev.yml`(git
미포함)엔 임의의 더미 좌표(예: 학교 근처 대략값 또는 `0.0`)를 넣어 로컬 기동/단위테스트만
통과시킨다 — 실제 반경 검증 동작 확인(QA)은 정확한 좌표가 있어야 의미 있다(아래 "리스크"
참고).

## 에러 코드 정의 (`OutingErrorCode`에 추가)
```java
/** 요청한 좌표가 학교 반경(schoolRadiusMeters) 밖입니다. */
OUT_OF_SCHOOL_RADIUS(
    HttpStatus.BAD_REQUEST, "OUTING_009", "학교 반경을 벗어난 위치에서는 출발/도착 처리를 할 수 없습니다."),

/** 현재 시각이 학교 운영시간(08:40~20:30, OutingTimeSlot.CUSTOM_WINDOW_START~END) 밖입니다. */
OUTSIDE_OPERATING_HOURS(
    HttpStatus.BAD_REQUEST, "OUTING_010", "학교 운영시간(08:40~20:30) 외에는 출발/도착을 보고할 수 없습니다."),
```

## 영향 받는 기존 코드/테스트
- 신규: `outing.OutingLocationRequest`(요청 DTO), `outing.config.OutingProperties`,
  `outing.utils.GeoUtils`(하버사인 거리 계산 순수 함수)
- 수정: `Outing`(엔티티, 컬럼 4개 추가), `OutingResponse`(`departedAt`/`returnedAt`/
  `offSchedule` 필드 추가), `OutingService`(`departOuting`/`returnOuting` 메서드 추가,
  `toResponse` 시그니처에 새 필드 반영, 운영시간 게이트 + 시간대 판정 로직 추가),
  `OutingController`(엔드포인트 2개 추가, `offSchedule`에 따른 메시지 분기),
  `OutingErrorCode`(`OUTING_009`/`OUTING_010` 추가), `OutingQueryStatus`(`DEPARTED`/
  `RETURNED` 추가), **`OutingTimeSlot`(버그 수정: `DINNER` 종료 시각 `21:10` → `19:10`,
  #29에서 잘못 반영된 값 — 위 "확정된 논의 사항" 참고)**
- **기존 테스트 중 깨지는 것**: `OutingQueryStatusTest.doesNotIncludeUnreachableStatuses()`는
  현재 "`DEPARTED`/`RETURNED`가 필터 값에 없어야 한다"를 검증한다. 이번 이슈로 그 두 값을
  추가하면 이 단언이 더 이상 성립하지 않으므로, 이 테스트를 수정하거나 삭제한다(아래 "테스트"
  절 참고).
- 변경 없음: `Outing` 기존 컬럼/로직, `applyOuting`/`approveOuting`/`rejectOuting`,
  기존 조회 엔드포인트의 URL/기본 동작(필터 가능한 `status` 값만 늘어남 — 하위 호환)

## 리스크 및 고려사항
- **API 설계 6원칙**:
  - 1번(한 가지를 잘하기): 출발/도착을 각각 별도 엔드포인트로 분리, 위치조회는 별도 이슈로
    분리 — 원칙에 부합.
  - 6번(하위 호환성): `OutingResponse`/`OutingQueryStatus`에 필드/값만 추가하고 기존 필드/값의
    의미를 바꾸지 않음 — 기존 클라이언트(있다면) 영향 없음.
  - 나머지 원칙은 신규 컬렉션 응답이 없어 해당 없음.
- **정확한 학교 좌표 미확정**: 위 "환경변수/시크릿" 참고. 이 값이 틀리면 실제로는 정문 앞인데
  반경 밖으로 판정되는 문제가 생길 수 있다 — staging 배포 전 정확히 측정해야 한다(보스 소관,
  이 이슈의 구현/단위테스트는 좌표 값과 무관하게 진행 가능).
- **GPS 위치 조작 가능성**: 마스터 기획서에 이미 기록된 한계 그대로다 — 위치 모킹 앱으로
  우회 가능하지만, 일반적인 오사용/실수를 거르는 수준으로 충분하다고 판단(마스터 기획서
  "정책 가정" 참고, 이번 이슈에서 재검토 결과 동일 결론).
- **반경 경계 부근 GPS 오차**: 마스터 기획서의 엣지케이스 3번과 동일한 한계 — 이번 이슈
  범위에서는 `accuracy` 값을 별도로 받지 않는다(YAGNI, 실제 QA에서 문제가 확인되면 후속
  이슈로 분리).
- **위치 데이터 무기한 보관 확정**: 개인정보 보존 정책 관점에서는 이례적인 결정이지만
  보스가 명시적으로 확정했다 — 추후 정책 필요 시 별도 이슈로 삭제 스케줄러를 추가할 수
  있다(지금은 컬럼 4개뿐이라 삭제 스케줄러 자체가 간단해질 것).
- **8/9/10번(실시간 목록/위치·동선 조회/위치 핑)은 명시적으로 이번 범위 밖**이다. 이
  이슈만으로는 "출발/도착은 기록되지만 실시간으로 어디 있는지는 아직 아무도 볼 수 없는"
  상태가 된다 — 사용자(선생님/선도부) 입장에서는 기능이 반쪽처럼 느껴질 수 있음을
  인지하고 있다. 다음 이슈로 바로 이어서 위치조회를 붙일 것을 제안한다.

## 테스트
- `outing.utils.GeoUtilsTest`(신규): 같은 지점(거리 0), 알려진 두 좌표 사이의 실제 거리(오차
  허용 범위 내), 반경 경계값(정확히 반경 위/아래) 케이스
- `outing.enums.OutingTimeSlotTest`(신규): `DINNER.getEndTime()`이 `19:10`인지 확인(버그
  회귀 방지 — 21:10으로 되돌아가지 않도록), `LUNCH`/`CUSTOM_WINDOW_START`/
  `CUSTOM_WINDOW_END` 기존 값도 함께 고정
- `OutingServiceTest`에 `DepartOuting`/`ReturnOuting` `@Nested` 클래스 추가:
  - 정상 출발/도착 → 상태 전이 + 좌표 저장 확인
  - 본인 외출증이 아님 → `403 OUTING_007`
  - 학교 운영시간(08:40~20:30) 밖에서 호출 → `400 OUTING_010`(경계값 08:39/08:40/20:30/20:31
    포함)
  - 상태 조건 위반(출발: `APPROVED` 아님 / 도착: `DEPARTED` 아님) → `409 OUTING_005`
  - 학교 반경 밖 → `400 OUTING_009`
  - 존재하지 않는 `code` → `404 OUTING_006`
  - 시간대(`startTime`~`endTime`) 안에서 보고 → `offSchedule: false`, 정상 메시지
  - 운영시간 안이지만 이 외출증 자체의 시간대 밖(이전/이후 둘 다)에서 보고 → 차단되지 않고
    정상 처리되되 `offSchedule: true`, 안내 메시지로 분기(예: `DINNER` 외출인데 운영시간
    안인 20:00에 도착 보고 — 게이트는 통과하지만 자기 시간대(19:10)는 지난 상태)
- 기존 `OutingServiceTest`: 변경 없음(새 메서드 추가일 뿐 기존 메서드 로직 불변)
- `OutingControllerTest`에 `DepartOuting`/`ReturnOuting` `@Nested` 클래스 추가 — 기존
  `ApplyOuting`/`ApproveOuting`/`RejectOuting`/`GetReceivedOutings`/`GetOutingDetail`과 동일한
  패턴(정상 요청/유효성 검증 실패 케이스)을 따른다.
- 신규 `OutingDepartAuthorizationTest`/`OutingReturnAuthorizationTest` 추가 — 기존
  `OutingApproveAuthorizationTest`/`OutingRejectAuthorizationTest`/
  `OutingDetailAuthorizationTest`와 동일한 패턴으로, `@PreAuthorize("hasRole('STUDENT')")`가
  TEACHER/미인증 요청을 거부하는지만 전담 검증한다.
- 기존 `OutingQueryStatusTest.doesNotIncludeUnreachableStatuses()` 수정 — `DEPARTED`/
  `RETURNED`가 "존재하지 않는 값"이 아니라 "존재하되 이제 `toOutingStatus()`로 정상
  변환되는 값"이 되므로, 이 테스트를 삭제하고 `convertsToOutingStatusWithSameName()`에
  `DEPARTED`/`RETURNED` 케이스를 추가한다.

## 완료 조건 (Definition of Done)
- 로컬 빌드/테스트 통과
- CI 통과
- Postman 컬렉션 반영
- (해당 시) Notion 기능정의서 반영
- staging 배포 전 `OUTING_SCHOOL_*` 실제 좌표 시크릿 등록 확인(후속 조치로 이슈에 남김)
