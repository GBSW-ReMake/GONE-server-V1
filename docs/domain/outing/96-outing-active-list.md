# #96 외출증 실시간 목록 조회 API — 기획서

관련 이슈: [#96 외출증 실시간 목록 조회 API (지금 외출 중인 학생)](https://github.com/GBSW-ReMake/GONE-server-V1/issues/96)
마스터 기획서: [1_outing-domain.md](./1_outing-domain.md) (8번 엔드포인트)
선행 코드: [`OutingController`](../../../src/main/java/com/remake/gone/outing/controller/OutingController.java)/
[`OutingService`](../../../src/main/java/com/remake/gone/outing/service/OutingService.java)/
[`OutingRepository`](../../../src/main/java/com/remake/gone/outing/repository/OutingRepository.java)

## 개요/목적
`DEPARTED` 상태는 #43에서 실제로 생기기 시작했지만, "지금 외출 중인 학생이 누구인지"를
한눈에 보여주는 목록 엔드포인트가 아직 없다. 예전에 선도부가 카톡방으로 수동 공유하던
정보를 대체하는 이 도메인의 핵심 "가시성" 기능이다.

## 마스터 기획서 재검토 (api-design.md "마스터 기획서 재검토" 원칙 적용)
- **권한 체크 방식**: 마스터 기획서 작성 당시엔 서비스 코드에서 역할을 직접 검사하는
  방식을 가정했을 수 있으나, #30 이후 이 컨트롤러의 모든 형제 엔드포인트가
  `@PreAuthorize`를 쓰고 있다. 이번에도 동일하게 `@PreAuthorize("hasAnyRole('DISCIPLINE',
  'TEACHER', 'ADMIN')")`를 컨트롤러에 붙인다. `ADMIN`은 "공통 전제로 항상 접근 가능"이라는
  이 프로젝트의 일반 원칙이 있지만, 실제로 이미 구현된 형제 엔드포인트(`SchoolCampController`
  의 `hasAnyRole('STUDENT', 'TEACHER')` 등)를 확인해보면 `hasAnyRole`에 명시적으로 나열되지
  않은 역할은 자동으로 통과되지 않는다 — 그래서 `ADMIN`을 반드시 목록에 명시적으로 포함한다.
- **페이지네이션 — 마스터 기획서와 다르게 간다(추가한다)**: 마스터 기획서는 페이지네이션
  없이 단순 배열로 응답하도록 가정했다("시계열 데이터량이 크지 않다"는 9번 엔드포인트의
  근거를 8번에도 암묵적으로 적용한 것으로 보인다). 하지만 재검토 결과 이 가정은 근거가
  약하다:
  1. 마스터 기획서 자체가 "`DEPARTED` 상태가 자정을 넘어도 자동으로 정리되지 않는다"는
     미해결 리스크를 명시하고 있다(8번 절 하단 콜아웃). 도착 보고를 안 하고 방치되는
     외출증을 자동으로 정리하는 스케줄러가 아직 없으므로, 이 목록이 이론상 무제한으로
     누적될 수 있다.
  2. #41 이후 이 도메인의 다른 목록 엔드포인트(`GET /me/requests`, `GET /me/received`)는
     전부 `PageResponse<T>` 페이지네이션을 쓰고 있어, 이 엔드포인트만 단순 배열로 응답하면
     같은 컨트롤러 안에서 패턴이 어긋난다(api-design.md "직관적 일관성" 원칙).

  따라서 `GET /me/received`와 동일한 `page`/`size` 파라미터 + `PageResponse<T>` 응답으로
  구현한다. "위 DEPARTED 무제한 누적 리스크 자체"는 이 이슈에서 해결하지 않는다(스케줄러
  신설은 별도 이슈 — 아래 "리스크 및 고려사항" 참고) — 페이지네이션은 그 리스크가 실제로
  터졌을 때 응답이 무한정 커지는 것만 막아주는 방어선이다.
- **응답 DTO**: 마스터 기획서는 `OutingResponse`를 재사용하지 않고 전용의 더 좁은 필드
  집합(`code`/`studentNickname`/`studentProfileImageUrl`/`studentRealName`/`studentGrade`/
  `studentClassNo`/`reason`/`timeSlot`/`departedAt`/`endTime`)을 제시했다 — `teacherName`,
  `startTime`, `outingDate`, `status`, `rejectedReason`, `returnedAt`, `offSchedule`은
  뺀다. 이 판단을 그대로 따른다: 이 목록은 "지금 누가 밖에 있고 언제까지 들어와야 하는지"만
  빠르게 훑어보는 화면이라 필요 이상의 필드를 넣지 않는 게 api-design.md "한 가지를 잘하기"
  원칙에 맞는다. 새 응답 타입 `OutingActiveResponse`를 만든다(기존 `OutingResponse`는
  변경하지 않음 — 하위 호환성 영향 없음).

## 엔드포인트

### `GET /api/v1/outings/active` — 지금 외출 중인 학생 목록
**권한**: `DISCIPLINE`, `TEACHER`, `ADMIN` 중 하나(`@PreAuthorize`)

**요청**
```
GET /api/v1/outings/active?page=0&size=20
```
- `page`: 선택, 기본값 `0`
- `size`: 선택, 기본값 `20`, `1~100`

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "code": "8A1zx9202n",
        "studentNickname": "길동이",
        "studentProfileImageUrl": "https://.../profile/1/abc.jpg?X-Amz-...",
        "studentRealName": "홍길동",
        "studentGrade": 3,
        "studentClassNo": 4,
        "reason": "치과 진료",
        "timeSlot": "LUNCH",
        "departedAt": "2026-08-14T12:31:05",
        "endTime": "13:40"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false
  },
  "message": "현재 외출 중인 학생 목록입니다.",
  "code": null
}
```

**구현 로직** (`OutingService.getActiveOutings`)
1. `page`/`size` 검증 — 기존 `validatePageParams`(`OutingErrorCode.INVALID_PAGE_PARAMS`,
   `OUTING_015`) 그대로 재사용
2. `outingRepository.findByStatusOrderByDepartedAtAsc(OutingStatus.DEPARTED)` 조회
   (신규 메서드 — 아래 참고)
3. `departedAt` 오름차순 정렬(가장 오래 나가 있는 학생이 먼저 보이도록 — 선도부가 우선
   확인해야 할 대상을 목록 위쪽에 두기 위함). 리포지토리가 정렬까지 처리하므로 서비스
   코드에서 별도 정렬은 하지 않는다.
4. `OutingActiveResponse` 리스트로 변환(`R2FileService.generateDownloadUrl`로
   프로필 이미지 URL 생성, 기존 `toResponse`와 동일한 패턴)
5. `PageResponse.of(...)`로 페이지네이션 적용해 반환

**에러**
- 401 `COMMON_002` UNAUTHORIZED — 인증 안 됨
- 403 `COMMON_003` FORBIDDEN — `DISCIPLINE`/`TEACHER`/`ADMIN` 중 어느 것도 아님
- 400 `OUTING_015` INVALID_PAGE_PARAMS — `page` 음수 또는 `size`가 `1~100` 범위 밖

## 데이터 모델 변경
없음(엔티티/마이그레이션 불필요). `OutingRepository`에 파생 쿼리 메서드
`findByStatusOrderByDepartedAtAsc(OutingStatus status)`만 새로 추가한다 — 기존
`findByStatus`(#42, `OutingMissedScheduler`가 `PENDING` 조회에 사용)는 건드리지 않고
그대로 둔다.

## 영향 받는 기존 코드/테스트
- 신규: `outing.dto.OutingActiveResponse`, `OutingController.getActiveOutings`,
  `OutingService.getActiveOutings`, `OutingRepository.findByStatusOrderByDepartedAtAsc`
- 변경 없음: `Outing` 엔티티, 기존 `OutingResponse`, `OutingRepository.findByStatus`,
  기존 다른 엔드포인트

## 리스크 및 고려사항
- **API 설계 6원칙**:
  1. 한 가지를 잘하기: 좁은 전용 응답 DTO, 위치 좌표 제외(#97에서 별도 권한으로 제공) —
     원칙에 부합.
  4. 의미 있는 오류: 기존 `OUTING_015`/`COMMON_002`/`COMMON_003` 재사용, 새 에러 코드
     불필요.
  5. 확장성/성능: 위 "마스터 기획서 재검토" 절에서 페이지네이션 추가로 이미 반영.
  6. 하위 호환성: 새 엔드포인트 + 새 DTO라 기존 응답에 영향 없음.
- **`DEPARTED` 무제한 누적 리스크(마스터 기획서 기존 리스크, 이번 이슈에서 해결 안 함)**:
  도착 보고 없이 방치된 외출증을 자동으로 `MISSED`(또는 별도 상태)로 정리하는 스케줄러가
  아직 없다. 페이지네이션으로 응답 크기 폭증은 막지만, "며칠 전에 나간 걸로 표시된
  학생"이 계속 이 목록에 남아있는 근본 문제는 그대로다 — #102(승인됐지만 미출발 마감
  처리)와 같은 성격의 상태 모델 빈틈이므로, 이번 구현 완료 후 별도 백로그 이슈로
  분리 제안한다.
- **정렬 기준**: `departedAt` 오름차순(가장 오래 나가 있는 학생 우선)으로 확정했다 —
  마스터 기획서에는 정렬 기준이 명시돼 있지 않아 이번에 새로 정한다.

## 테스트
- `OutingServiceTest.GetActiveOutings`(신규 `@Nested`):
  - `DEPARTED` 외출증이 여러 건일 때 `departedAt` 오름차순으로 반환
  - `DEPARTED`가 아닌 외출증(`PENDING`/`APPROVED`/`RETURNED`/`REJECTED`/`MISSED`)은
    제외
  - 결과 없을 때 `content: []`(200, `null` 아님)
  - `page`/`size` 페이지네이션 동작(#41의 기존 테스트 패턴 재사용)
  - `page`가 음수이거나 `size`가 범위 밖이면 `OUTING_015`
- `OutingControllerTest.GetActiveOutings`(신규 `@Nested`): 정상 요청 위임 확인
- `OutingActiveListAuthorizationTest`(신규): `DISCIPLINE`/`TEACHER`/`ADMIN` 각각 200,
  `STUDENT`로 요청 시 403, 인증 없이 요청 시 401(기존
  `OutingApproveAuthorizationTest` 등과 동일한 패턴)

## 완료 조건 (Definition of Done)
- 로컬 빌드/테스트 통과
- CI 통과
- Postman 컬렉션 반영
