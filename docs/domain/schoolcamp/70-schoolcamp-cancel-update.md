# #70 스쿨캠핑 신청 취소/수정 API — 기획서

관련 이슈: [#70 스쿨캠핑 신청 취소/수정 API 구현](https://github.com/GBSW-ReMake/GONE-server-V1/issues/70)
마스터 기획서: [1_schoolcamp-domain.md](./1_schoolcamp-domain.md)의 "엔드포인트 4.
`DELETE /api/v1/school-camps/applications/{id}`", "엔드포인트 5.
`PATCH /api/v1/school-camps/applications/{id}`"
선행 이슈: [#68](./68-schoolcamp-application.md)(신청 API, 완료·머지됨 — 이 이슈가 의존하는
`SchoolCampApplication`/`SchoolCampMember` 엔티티와 원자적 점유/반환 패턴을 그대로 재사용한다)

## 개요/목적
본인이 신청한 스쿨캠핑을 취소하거나(세션을 다시 빈 날짜로 되돌림), 담당 선생님/팀원 구성을
수정하는 2개 엔드포인트를 구현한다.
1. `DELETE /api/v1/school-camps/applications/{id}` — 본인 신청 취소
2. `PATCH /api/v1/school-camps/applications/{id}` — 담당 선생님/팀원 정보 수정

**에러 코드 정정(검토 요청)**: 마스터 기획서는 두 엔드포인트의 "존재하지 않거나 이미
취소/삭제된 신청"을 `SCHOOLCAMP_001`로 응답하도록 적어놨는데, `SCHOOLCAMP_001`의 실제
기본 메시지는 "해당 날짜에 스쿨캠핑 일정이 없습니다"(#68에서 `SchoolCampSession` 조회 실패에
사용, 대상이 세션이다)이다. 이 이슈의 대상은 세션이 아니라 **`SchoolCampApplication`**이라
같은 코드를 재사용하면 "일정이 없다"는 메시지가 "그런 신청이 없다"는 실제 상황과 어긋난다.
그래서 신규 코드 `SCHOOLCAMP_010`("해당 신청을 찾을 수 없습니다")을 추가해 신청 대상
not-found를 세션 대상 not-found와 분리했다 — API 설계 원칙 4(의미 있는 오류)를 따른 결정.
`010`은 마스터 기획서에서 원래 "month 형식 오류"용으로 예약됐었지만 #67에서 그 코드를 만들지
않기로 확정해(기존 `COMMON_001` 재사용) 비어 있던 번호다.

## 엔드포인트

### 1. `DELETE /api/v1/school-camps/applications/{id}` — 본인 신청 취소
**권한**: 그 신청의 신청자 본인(소유권은 서비스에서 확인, 컨트롤러는 `isAuthenticated()`만)

**요청**: 바디 없음, 경로 변수 `id`(`SchoolCampApplication.id`)

**응답** (`200 OK`)
```json
{ "success": true, "data": null, "message": "신청이 취소되었습니다.", "code": null }
```

**구현 로직** (마스터 기획서 엔드포인트 4 그대로)
1. `id`로 `SchoolCampApplication` 조회 — `applicationRepository.findByIdAndCancelledAtIsNull(id)`
   (취소된 신청은 이미 없는 것처럼 취급), 없으면 `404` `SCHOOLCAMP_010`
2. `principal.userId() == application.getApplicant().getId()` 확인, 아니면 `403`
   `SCHOOLCAMP_007`
3. **당일 취소 금지**: `application.getSession().getCampDate()`가 오늘(`LocalDate.now(KST)`,
   컨트롤러가 파라미터로 전달)이면 `400` `SCHOOLCAMP_009`
4. `application.setCancelledAt(now)` 후 저장
5. **세션 원자적 반환**: `SchoolCampSessionRepository.release(id)`를 그대로 재사용한다 —
   `#68`이 claim의 반환 방향으로 이미 추가해둔 그 메서드다(새 메서드 추가 아님).

   **`SchoolCampSessionClaimService.release`(REQUIRES_NEW)를 거치지 않고,
   `sessionRepository.release(id)`를 이 메서드(`cancelApplication`)의 트랜잭션 안에서 직접
   호출한다(확정)** — `#68`의 claim/release가 REQUIRES_NEW로 분리된 이유는 "한 세션에 수백
   건이 동시에 몰리는 경합을 짧게 끊어내기" 위해서였는데, 취소는 그 신청의 유일한 소유자만
   호출할 수 있어(2번 소유권 확인이 이미 그 사실을 보장) 애초에 경합 상대가 없다 — REQUIRES_NEW의
   이점이 적용될 상황 자체가 아니다. 오히려 `cancelledAt` 갱신과 `taken_at` 반환을 **같은
   트랜잭션으로 묶어야**, 이후 어떤 이유로든 커밋이 실패했을 때 두 변경이 함께 롤백돼 "신청은
   취소됐는데 세션은 여전히 잠긴" 또는 "세션은 열렸는데 신청은 취소되지 않은" 불일치가 생기지
   않는다 — `#68`의 claim/release가 REQUIRES_NEW라서 안았던 "release 실패 시 유령 점유"
   잔여 리스크가 이 엔드포인트에는 애초에 없다.

**에러**
- 본인 신청이 아님 → `403` `SCHOOLCAMP_007`
- 존재하지 않거나 이미 취소된 신청 → `404` `SCHOOLCAMP_010`
- 캠핑 당일 취소 시도 → `400` `SCHOOLCAMP_009`

---

### 2. `PATCH /api/v1/school-camps/applications/{id}` — 담당 선생님/팀원 정보 수정
**권한**: 1번과 동일(본인 신청)

**요청** — #68 신청 엔드포인트와 같은 형식. **전체 교체 방식**이다(부분 patch 아님) — 대표
신청자 본인을 제외한, 바뀌지 않은 팀원도 포함해 원하는 최종 팀원 목록 전체를 보낸다.
```json
{
  "teacherUserId": 55,
  "teacherName": null,
  "additionalMembers": [
    { "studentUserId": 55, "guestName": null },
    { "studentUserId": null, "guestName": "김철수(옆반 아님, 외부인)" }
  ]
}
```

**응답** (`200 OK`) — #68 신청 응답과 동일한 `SchoolCampApplicationResponse` 재사용
```json
{
  "success": true,
  "data": {
    "id": 301,
    "campDate": "20260403",
    "teacherDisplayName": "박선생",
    "members": [
      { "studentRealName": "홍길동", "studentGrade": 3, "studentClassNo": 4, "guestName": null, "isApplicant": true },
      { "studentRealName": null, "studentGrade": null, "studentClassNo": null, "guestName": "김철수(옆반 아님, 외부인)", "isApplicant": false }
    ],
    "appliedAt": "2026-03-20T09:12:00"
  },
  "message": "신청 정보가 수정되었습니다.",
  "code": null
}
```

**구현 로직** (마스터 기획서 엔드포인트 5 그대로)
1. `id`로 `SchoolCampApplication` 조회(`findByIdAndCancelledAtIsNull`), 없으면 `404`
   `SCHOOLCAMP_010`
2. 소유권 확인(1번과 동일), 아니면 `403` `SCHOOLCAMP_007`
3. `teacherUserId`/`teacherName`, `additionalMembers` 각 항목 형식 검증 + `teacherUserId`가
   `TEACHER` 역할인지 확인(#68의 신청 검증 로직 그대로 재사용) → 위반 시 `400`
   `SCHOOLCAMP_004`
4. `newMemberCount = 1(본인) + additionalMembers.size()`, `> 8`이면 `400` `SCHOOLCAMP_004`
5. `additionalMembers`의 `studentUserId` 존재/중복(자기 자신 포함) 확인(#68과 동일, 배치
   조회) → `400` `SCHOOLCAMP_008`
6. **기존 팀원과 diff 계산** — `memberRepository.findByApplicationId(id)`로 기존 팀원 전체
   조회 후 대표 신청자 행 제외:
   - 기존 가입 학생들의 `studentUserId` 집합(`existingStudentIds`)과 요청받은
     `additionalMembers`의 `studentUserId` 집합(`newStudentIds`)을 비교
   - `addedStudentIds = newStudentIds - existingStudentIds` — **새로 추가되는 학생만** 이번
     달 중복 참여를 재확인(#68 6번 로직 재사용) → 걸리면 `409` `SCHOOLCAMP_003`. 이미 이
     신청에 속해 있던 학생은 재검사하지 않는다 — 재검사하면 "이번 달 참여 중"인 자기 자신의
     현재 신청과 항상 충돌해 무조건 실패하게 되므로(마스터 기획서에 명시된 이유)
   - `removedStudentIds = existingStudentIds - newStudentIds` — 이 학생들의 `SchoolCampMember`
     행을 삭제
   - `keptStudentIds`(교집합)는 그대로 둔다(삭제도 재삽입도 하지 않음)
   - **"기타"(자유 입력) 팀원은 식별자가 없어 diff 대상이 아니다** — 기존 guest 행은 전부
     삭제하고, 요청받은 `guestName` 목록으로 전부 새로 삽입한다(이름이 우연히 같아도 별개
     레코드로 취급, 이전 대비 정책 변화 없음 — 애초에 guest 항목엔 안정적인 식별자가 없어
     "같은 사람인지" 판단할 근거 자체가 없다)
7. `teacher_user_id`/`teacher_name` 갱신. 6번에서 계산한 `removedStudentIds` 행 + 기존 guest
   행 전부 삭제, `addedStudentIds`(월 중복 통과분) + 요청받은 guest 목록 전부 삽입. 대표
   신청자 행(`applicant=true`)은 절대 건드리지 않는다. **세션 점유(`taken_at`)는 건드리지
   않는다** — 팀 인원이 늘거나 줄어도 그 세션은 이미 이 신청 하나가 통째로 차지한 상태라
   별도 정원 조작이 필요 없다
8. `addedStudentIds`에 대해 #68과 동일한 초대 알림 발송(`NotificationService.send(...,
   NotificationType.SCHOOLCAMP)`)
9. **관리자 변경 알림은 TODO 주석만 남긴다** — 발송 대상(전체 관리자 vs 담당자)·문구·채널
   미정(마스터 기획서 그대로, `admin`(#72) 쪽 결정 이후 별도 이슈로 실제 발송 구현)
10. 응답 DTO 변환(#68과 동일한 `SchoolCampApplicationResponse`, 갱신된 최신 팀원 목록으로)

**에러**
- 소유권 없음 → `403` `SCHOOLCAMP_007`
- 존재하지 않거나 취소된 신청 → `404` `SCHOOLCAMP_010`
- 형식 오류/8명 초과/유효하지 않은 `teacherUserId` → `400` `SCHOOLCAMP_004`
- 존재하지 않는 `studentUserId`/중복 → `400` `SCHOOLCAMP_008`
- 새로 추가한 팀원 중 이번 달 이미 참여한 사람 있음 → `409` `SCHOOLCAMP_003`

> 💡 이 엔드포인트는 세션의 `taken_at`에 영향을 주지 않는다 — 그 날짜는 이미 이 신청 하나가
> 차지한 상태이므로, 팀원을 추가/제거해도(최대 8명 제한 안에서) 다른 신청과 경합할 여지가
> 없다. 그래서 1번(취소)과 달리 세션 원자적 반환이 필요 없다.

## 영향 받는 기존 코드
- 신규: `SchoolCampErrorCode.APPLICATION_NOT_FOUND`(`SCHOOLCAMP_010`),
  `.NOT_APPLICATION_OWNER`(`SCHOOLCAMP_007`), `.CANCEL_NOT_ALLOWED_ON_CAMP_DAY`
  (`SCHOOLCAMP_009`)
- 수정: `SchoolCampApplicationRepository`(`findByIdAndCancelledAtIsNull` 추가),
  `SchoolCampMemberRepository`(`findByApplicationId`를 이 이슈에서 신규 추가, 추가로
  `deleteAllByIdIn` 또는 개별 `delete` 필요), `SchoolCampService`(`cancelApplication`/
  `updateApplication` 추가 — `#68`의 `validateApplicationFormat`/`findValidTeacher`/
  `findExistingStudents`는 그대로 재사용하되, `validateNoDuplicateThisMonth`는 호출 시
  대표 신청자를 후보 집합에 자동으로 넣는 현재 시그니처가 "새로 추가되는 학생만" 검사하는
  이 이슈의 요구와 맞지 않아 그대로 재사용할 수 없다 — 구현 시 후보 집합을 호출부에서 직접
  구성해 넘기도록 시그니처를 조정해야 한다), `SchoolCampController`(`DELETE`/`PATCH`
  `/applications/{id}` 추가)
- `SchoolCampSessionRepository.release`는 이미 `#68`에 있어 그대로 재사용한다(수정 없음)
- 신규 마이그레이션 없음(기존 테이블만 사용)
- `SecurityConfig`(수정 없음): `/api/v1/school-camps/**`가 이미 인증 요구로 등록됨

## 리스크 및 고려사항
- **API 설계 6원칙 체크**:
  1. 한 가지를 잘하기 — 취소/수정 2개 엔드포인트로 범위가 좁다. 준수.
  2. 빠르게 시작 — 요청/응답 예시 포함. 준수.
  3. 직관적 일관성 — `SCHOOLCAMP_NNN` 코드 컨벤션, `ApiResponse<T>`, #68의
     `SchoolCampApplicationResponse` 재사용(PATCH가 새 응답 타입을 만들지 않음).
  4. 의미 있는 오류 — 위 "에러 코드 정정" 참고(세션 not-found와 신청 not-found를 분리).
  5. 확장성/성능 — diff 계산은 팀 최대 8명 기준 집합 연산이라 무시할 수준. 월 중복 재확인도
     새로 추가되는 인원만 대상이라 #68보다 오히려 쿼리 대상이 줄어들 수 있음.
  6. 하위 호환성 — 신규 엔드포인트 2개, 해당 없음.
- **취소는 `#68`의 "release 실패 시 유령 점유" 잔여 리스크에서 자유롭다**: `cancelledAt`
  갱신과 `taken_at` 반환을 같은 트랜잭션으로 묶기 때문에(위 엔드포인트 1의 5번 참고), 취소
  처리 도중 어떤 이유로 커밋이 실패해도 두 변경이 함께 롤백된다 — `#68`의 claim/release가
  REQUIRES_NEW라서 "release만 따로 실패해 세션이 영구히 잠긴 채로 남는" 잔여 리스크를 안았던
  것과 다른 지점이다.
- **취소와 수정 사이의 이론적 레이스**: 같은 신청에 대해 취소(1번)와 수정(2번)이 동시에
  들어오면, 취소가 먼저 커밋되면 이후 수정은 `findByIdAndCancelledAtIsNull`에서 이미
  걸러져 `404`로 자연스럽게 처리된다 — 별도 락 없이 "취소 여부" 컬럼 자체가 가드 역할을
  한다.
- **diff 삭제/삽입이 하나의 트랜잭션**: 6~7번 전체가 `@Transactional`로 묶여, 삭제는 됐는데
  삽입만 실패하는 식의 부분 반영이 나지 않는다.
- **"전체 교체" 계약이 프론트에 실수 여지를 준다** — 바뀌지 않은 기존 팀원을 실수로
  `additionalMembers`에서 빼먹으면 그 팀원이 삭제된다. 이건 마스터 기획서가 이미 확정한
  계약이라(부분 patch가 아니라 스냅샷) 이 이슈에서 바꾸지 않지만, 프론트 구현 시 "현재 팀원
  목록을 먼저 불러와 채워둔 상태로 수정 폼을 시작"하는 게 사실상 필수라는 점을 인지 목적으로
  남겨둔다.

## 테스트
- `DELETE /api/v1/school-camps/applications/{id}`:
  - 정상 취소(세션이 다시 `OPEN`으로 조회되는지까지 확인)
  - 본인 신청 아님 → `403`
  - 존재하지 않는 `id`/이미 취소된 신청 → `404`
  - 캠핑 당일 취소 시도 → `400`
- `PATCH /api/v1/school-camps/applications/{id}`:
  - 담당 선생님만 변경(팀원 그대로)
  - 팀원 추가(가입 학생 + "기타" 혼합), 팀원 제거, 팀원 유지 — 세 경우 모두 diff가 올바르게
    적용되는지(삭제/유지/신규삽입 각각)
  - 기존에 있던 팀원은 이번 달 중복 검사에서 제외되고, 새로 추가된 팀원만 검사되는지
  - 새로 추가한 팀원이 이번 달 이미 다른 세션에 참여 중 → `409`
  - 소유권 없음 → `403`
  - 존재하지 않거나 취소된 신청 → `404`
  - 8명 초과, 존재하지 않는 `studentUserId` → `400`
  - 수정해도 세션 `taken_at`이 그대로 유지되는지(반환되지 않는지) 확인
  - 새로 추가된 가입 팀원에게 알림이 발송되는지
- 회귀: #68에서 만든 검증 로직(형식/역할/존재/중복)이 리팩터링 후에도 그대로 동작하는지
