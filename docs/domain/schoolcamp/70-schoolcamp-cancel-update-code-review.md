# #70 스쿨캠핑 신청 취소/수정 API — 코드 리뷰 결과

관련 기획서: [70-schoolcamp-cancel-update.md](./70-schoolcamp-cancel-update.md)
리뷰 대상: `git diff dev...HEAD`(브랜치 `feat/#70-schoolcamp-cancel-update`), 총 9개 파일 —
구현 코드/테스트/기획서 문서 변경 전체.

> **반영 결과 (2026-08-20)**: Medium 2건 모두 반영했다.
> - 1번(동시 PATCH 중복 삽입): 리뷰의 방안 1(DB 유니크 제약)을 채택 —
>   `V13__add_schoolcamp_member_unique_constraint.sql`로 `(application_id, student_user_id)`
>   유니크 제약을 추가하고, `updateApplication`이 그 위반(`DataIntegrityViolationException`)을
>   잡아 신규 `SCHOOLCAMP_011`(409, `CONCURRENT_UPDATE_CONFLICT`)로 변환하도록 해 방안 1이
>   지적한 "원인 불명확한 500" 단점까지 같이 해소했다. 방안 2(비관적 락)는 이 프로젝트에
>   선례가 없는 패턴을 새로 들이는 비용이 커 채택하지 않았다.
> - 2번(diff 조합/게스트뿐 케이스 테스트 누락): 방안 1 그대로 채택 — 조합 테스트
>   (`keepsAddsAndRemovesMembersInSameRequest`)와 게스트뿐 테스트
>   (`addsOnlyGuestMembersSkipsMonthlyDuplicateCheck`)를 추가했다.

## 리뷰 범위/방법
- 기획서 「엔드포인트 1·2」의 구현 로직·에러 코드·검증 순서·"세션 원자적 반환을 같은
  트랜잭션에서 직접 호출한다"는 결정과 실제 diff를 한 줄씩 대조했다.
- `SchoolCampService.cancelApplication`/`updateApplication`/`computeMemberDiff`/
  `buildNewMembers`, `SchoolCampController`의 신규 엔드포인트 2개, `SchoolCampErrorCode`
  신규 코드 3개(007/009/010), `SchoolCampApplicationRepository.findByIdAndCancelledAtIsNull`,
  `SchoolCampMemberRepository.findByApplicationId`, 관련 테스트 3개 파일 전체를 읽었다.
- `SchoolCampMember`/`SchoolCampApplication` 엔티티와 `V12__add_schoolcamp_application.sql`
  마이그레이션을 확인해 DB 제약(유니크/락) 유무를 점검했다.
- 컨벤션 비교 대상으로 `OutingService`(소유권 확인 패턴 `!x.getId().equals(y)`)와, 이 이슈가
  재사용을 명시한 `#68`의 `applyToCamp`/`completeApplication`/`validateNoDuplicateThisMonth`를
  대조했다.
- `docs/domain/schoolcamp/68-schoolcamp-application-code-review.md`(선행 이슈 코드 리뷰)를
  참고해 이미 다뤄진 패턴(REQUIRES_NEW 커넥션 경합, release 실패 시 예외 유실 등)이 이번
  변경에 그대로 재사용됐는지, 새로 도입된 코드에 유사한 결함이 없는지 확인했다.
- 소스 코드는 수정하지 않았다(리뷰 전용).

## 발견 사항

### 1. 🟡 Medium — `updateApplication`에 동시성 가드가 없어, 같은 신청에 대한 동시 PATCH 두 건이 같은 학생의 팀원 행을 중복 생성할 수 있음

**문제**: `SchoolCampService.updateApplication`
(`src/main/java/com/remake/gone/schoolcamp/service/SchoolCampService.java:283-330`)은
`memberRepository.findByApplicationId(applicationId)`로 기존 팀원을 읽어 diff를 계산한 뒤,
`deleteAllById`(삭제) → `saveAll`(삽입) 순으로 반영한다. 이 경로 전체에 비관적 락이나
버전 컬럼(`@Version`)이 전혀 없고, `school_camp_member` 테이블에도
`(application_id, student_user_id)` 유니크 제약이 없다
(`src/main/resources/db/migration/V12__add_schoolcamp_application.sql:18-29`).

같은 사용자가 같은 신청에 대해 거의 동시에 PATCH를 두 번 보내면(더블 클릭, 프론트 재시도
로직, 네트워크 재전송 등 — 취소와 달리 이 이슈의 기획서도 "취소와 수정 사이의 레이스"만
다루고 "수정과 수정 사이의 레이스"는 언급하지 않는다, `70-schoolcamp-cancel-update.md:181-184`
참고) 두 트랜잭션 모두 커밋 전 시점에 같은 `existingMembers`를 읽어 같은 `addedStudentIds`를
계산하고, 각자 그 학생에 대한 새 `SchoolCampMember` 행을 `saveAll`로 삽입한다. 유니크 제약이
없어 두 삽입 모두 성공하고, 결과적으로 같은 학생이 팀원 목록에 두 번 나타나는 중복 행이
DB에 영구히 남는다 — `computeMemberDiff`(`SchoolCampService.java:346-377`)의 diff 계산 자체는
집합 연산이라 논리적으로 맞지만, "한 학생은 한 신청에 행 하나"라는 전제가 동시 요청 앞에서는
DB 레벨로 보장되지 않는다. 그 결과 팀 인원이 요청 시점 검증(`MAX_TEAM_SIZE = 8`,
`validateApplicationFormat`)을 통과했더라도 저장된 행 수는 8명을 넘어설 수 있고, 이후 캘린더/
응답 DTO에서 같은 학생이 중복 표시된다. `#68`의 claim(`REQUIRES_NEW` + `WHERE taken_at IS NULL`
가드)이 정확히 이런 종류의 동시 쓰기 레이스를 DB 원자적 UPDATE로 막았던 것과 대조적으로,
이 메서드는 "취소와 달리 유일한 소유자만 호출하니 경합 상대가 없다"는 기획서의 근거(엔드포인트
1에 대한 근거,`70-schoolcamp-cancel-update.md:49-58`)를 검증 없이 수정(엔드포인트 2)에도 그대로
확장 적용한 것으로 보이는데, 취소는 `release`가 조건 없는(멱등) UPDATE라 두 번 호출해도
안전한 반면, 수정의 삭제+삽입은 멱등하지 않다는 차이가 있다.

**해결 방안**:
1. `school_camp_member`에 `(application_id, student_user_id)` 부분 유니크 제약을 추가하는
   마이그레이션을 만든다(MySQL은 `student_user_id`가 NULL인 게스트 행끼리는 유니크 제약이
   충돌하지 않으므로 게스트 중복 방지 효과는 없지만, 이 이슈가 실제로 문제 삼는 "같은 학생
   중복"은 정확히 막는다). 장점: DB가 최종 방어선이 되어 애플리케이션 코드의 실수와
   무관하게 중복이 물리적으로 불가능해진다. 단점: 새 마이그레이션이 필요하고, 제약을
   위반하는 두 번째 트랜잭션은 `DataIntegrityViolationException` → `GlobalExceptionHandler`의
   범용 500으로 응답돼(#68 리뷰 4번이 다뤘던 것과 같은 종류의 "원인 불명확한 500" 문제)
   원인이 사용자에게 명확히 전달되지 않는다 — 이 예외를 잡아 의미 있는 코드로 변환하는
   추가 작업이 없다면 여전히 UX가 거칠다.
2. `updateApplication` 진입 시 `SchoolCampApplication`을 비관적 쓰기 락
   (`@Lock(LockModeType.PESSIMISTIC_WRITE)` 조회 메서드 추가)으로 가져와, 같은 신청에 대한
   동시 PATCH를 DB 행 잠금으로 직렬화한다. 장점: 애플리케이션 레벨에서 이 이슈가 우려하는
   레이스를 정확히 막고, 부수적으로 취소(`cancelApplication`)와의 레이스도 더 확실하게
   방어된다(현재는 `cancelledAt` 컬럼 가드에만 의존). 단점: 이 프로젝트에 비관적 락 사용
   선례가 없어 새 패턴을 들이는 학습 비용이 들고, 락 대기 중인 요청은 타임아웃/지연이
   생길 수 있다 — `#68`이 "짧은 락 유지 시간"을 위해 일부러 REQUIRES_NEW로 커넥션을
   짧게 끊어낸 설계 철학과는 다른 방향이라, 왜 이 메서드만 락을 쓰는지 주석으로 남겨야
   다음 작성자가 혼란스럽지 않다.
3. 현재 상태를 유지한다 — 트리거 조건이 "같은 사용자가 같은 신청을 짧은 시간 안에 중복
   제출"하는 드문 상황이고, 발생해도 서비스 전체 장애가 아니라 그 신청 하나의 팀원 목록만
   영향받는다고 보고 수용한다. 장점: 코드 변경 없음. 단점: 기획서의 "리스크 및 고려사항"
   절이 이미 취소-수정 레이스는 다뤘으면서 수정-수정 레이스는 다루지 않은 채로 남아,
   이 잔여 리스크가 문서화되지 않는다 — 이 방안을 택한다면 최소한 기획서에 이 레이스를
   기록해두는 것을 권장한다(방안 3을 택했던 #68 리뷰 2번의 선례와 같은 이유).

### 2. 🟡 Medium — `updateApplication`의 diff 로직 테스트가 추가/제거/유지를 항상 단독 케이스로만 검증하고, 한 요청 안에서 섞이는 조합과 "전원 게스트" 케이스를 다루지 않음

**문제**: 기획서 "테스트" 절은 "팀원 추가(가입 학생 + '기타' 혼합), 팀원 제거, 팀원 유지 —
**세 경우 모두** diff가 올바르게 적용되는지(삭제/유지/신규삽입 각각)"를 요구한다
(`70-schoolcamp-cancel-update.md:200-202`). 그런데 `SchoolCampServiceTest.UpdateApplication`
(`src/test/java/com/remake/gone/schoolcamp/service/SchoolCampServiceTest.java`)에 추가된
세 테스트 — `changesTeacherOnlyKeepsExistingMembers`(유지만), `addsRegisteredAndGuestMembers`
(추가만, 기존 팀원 없음), `removesMemberNotInRequest`(제거만, 결과가 대표 신청자 1명으로
줄어듦) — 는 각각 유지/추가/제거를 서로 다른 요청에서 **단독으로만** 검증한다. "기존 팀원
A는 유지하고, 기존 팀원 B는 제거하고, 새 팀원 C(가입 학생)와 D(기타)를 동시에 추가하는" 한
번의 PATCH 요청은 어떤 테스트에서도 실행되지 않는다. `computeMemberDiff`
(`SchoolCampService.java:346-377`)는 `existingStudentIds`/`newStudentIds` 두 집합의 차집합·
교집합 연산이라 원리상 조합에 안전해 보이지만, 이런 집합 연산 로직이야말로 "각 연산은
따로 맞는데 셋을 같은 입력에 동시에 적용했을 때"의 경계 조건(예: `addedStudentIds`와
`memberIdsToDelete`가 겹치는 스트림 필터 순서 실수)에서 조용히 틀리기 쉬운 종류의 코드이고,
지금 테스트 스위트는 이 조합을 리팩터링 중 깨져도 잡아내지 못한다. 또한 "additionalMembers
전체가 게스트뿐인" 케이스(가입 학생이 하나도 없어 `addedStudentIds`가 항상 빈 집합이 되고
월 중복 재확인이 아예 스킵되는 경로)도 별도로 검증되지 않는다 — `addsRegisteredAndGuestMembers`
가 게스트를 포함하긴 하지만 가입 학생과 섞여 있어 "게스트만"의 경로(`validateNoDuplicateThisMonth`
호출 자체가 스킵되는지)를 특정해서 보여주지 않는다.

**해결 방안**:
1. `SchoolCampServiceTest.UpdateApplication`에 `keepsAddsAndRemovesMembersInSameRequest`
   테스트를 추가한다 — 기존 팀원 2명(유지 대상 1, 제거 대상 1)을 `stubApplicationWithMembers`로
   세팅하고, 요청의 `additionalMembers`에 "유지할 기존 학생 ID + 새 가입 학생 ID + 새 게스트"만
   담아(제거 대상 ID는 요청에서 뺀 채) 호출한 뒤 `memberRepository.deleteAllById`가 제거
   대상 ID만 담긴 리스트로, `memberRepository.saveAll`이 새 가입 학생+게스트 2건만 담긴
   리스트로 각각 호출됐는지 검증한다. 같은 방식으로 "additionalMembers가 게스트만인" 케이스도
   하나 추가해 `findParticipatedStudentIdsInMonth`가 호출되지 않는지(`never()`) 확인한다.
   장점: 기존 `stubApplicationWithMembers` 픽스처 패턴을 그대로 재사용할 수 있어 비용이
   낮고, 기획서가 명시적으로 요구한 조합 케이스를 정확히 채운다. 단점 없음(새 인프라
   불필요).
2. 현재 상태를 유지하고 QA 단계에서 실서버로 조합 시나리오를 수동 검증해 QA 문서에만
   남긴다. 장점: 코드 리뷰 단계에서 추가 작업이 없다. 단점: 이후 `computeMemberDiff`가
   리팩터링되며 조합 케이스에서만 드러나는 회귀가 생겨도 자동으로 잡히지 않고, 매 QA
   사이클마다 수동 재현 비용이 반복된다.

## Critical/High 없음

취소/수정 두 엔드포인트의 소유권 확인, 에러 코드 분기, 세션 반환 트랜잭션 경계를 검토한
범위에서 서비스 전체 장애나 즉각적인 데이터 유실로 이어지는 Critical/High 등급 결함은
발견하지 못했다. 1번(Medium)이 데이터 정합성 문제이긴 하나, 트리거 조건이 "같은 사용자의
거의 동시 중복 제출"이라는 좁은 창에 의존하고 영향 범위도 그 신청 한 건에 국한돼(#68 리뷰
1번처럼 무관한 요청 전체를 500으로 무너뜨리는 종류가 아님) High로 올리지 않았다.

## 보안 관련 확인
- `DELETE`/`PATCH .../applications/{id}`는 컨트롤러에서 `@PreAuthorize("isAuthenticated()")`
  만 확인하고, 소유권(신청자 본인 여부)은 서비스에서
  `application.getApplicant().getId().equals(applicantUserId)`로 확인한다
  (`SchoolCampService.java:257`, `290`) — 기획서가 확정한 설계 그대로다. 이 비교는
  `principal.userId()`(Access Token에서 추출)만 사용하고 요청 바디의 어떤 값도 신뢰하지
  않으므로, 다른 사용자의 `applicationId`를 추측해 취소/수정을 시도해도 소유자가 아니면
  전부 `403 SCHOOLCAMP_007`로 막힌다 — IDOR 경로 없음.
- 존재하지 않는 `applicationId`는 `404`, 존재하지만 타인 소유면 `403`으로 응답이 갈려
  타인에게 "그 ID의 신청이 존재했는지"가 간접적으로 드러나지만, 이는 기획서가 명시적으로
  확정한 조회 순서(1. not-found 404 → 2. 소유권 403)이자 `OutingService`의 기존 패턴과도
  같은 종류의 트레이드오프라 이번 변경이 새로 도입한 문제는 아니다.
- `teacherUserId`/`additionalMembers[].studentUserId`는 #68과 동일하게 임의의 유저 ID를
  조회하지만 "다른 사람을 팀에 초대/지정"하는 기능의 정상 동작이며, #68 코드 리뷰가 이미
  같은 패턴을 검토해 별도 인가 문제로 보지 않기로 했다 — 이번 변경도 동일 결론.
- 신규 리포지토리 메서드(`findByIdAndCancelledAtIsNull`, `findByApplicationId`)는 모두
  파라미터 바인딩(JPQL 파생 쿼리/`@Param`)을 쓰고 문자열 결합이 없어 인젝션 여지가 없다.
