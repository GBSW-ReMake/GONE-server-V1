# #81 스쿨캠핑 신청/수정 시 월 중복 참여자 특정해서 응답하기 — 코드 리뷰 결과

관련 기획서: [81-schoolcamp-monthly-conflict-response.md](./81-schoolcamp-monthly-conflict-response.md)
리뷰 대상: `git diff 07fb4ed..HEAD`(브랜치 `feat/#81-schoolcamp-monthly-conflict-response`),
커밋 4개 중 코드 변경이 있는 3개 — `43c717a`(DTO 추가), `7e71a0a`(서비스 로직 변경),
`198cbc3`(테스트 보강). `3af5dae`(기획서 작성)는 문서 전용이라 코드 리뷰 대상에서 제외.

## 리뷰 범위/방법
- 기획서의 "구현 로직"·"신규 DTO"·"영향 받는 기존 코드" 절과 실제 diff를 한 줄씩 대조해,
  기획서 범위를 벗어난 변경이 없는지 확인했다.
- `SchoolCampConflictingMemberResponse`/`SchoolCampParticipationConflictResponse`
  (`src/main/java/com/remake/gone/schoolcamp/dto/`), `SchoolCampService`의
  `validateNoDuplicateThisMonth`/`validateAndCollectStudents`/`toConflictingMemberResponse`
  와 두 호출부(`completeApplication`, `updateApplication`), `SchoolCampErrorCode`의
  Javadoc 변경, `SchoolCampServiceTest`에 추가/보강된 테스트 3건 전체를 읽었다.
- `usersById.get(studentId)`가 항상 값을 찾는지(null 역참조 위험)를 확인하려고, 두
  호출부에서 `candidateIds`(월 중복 검사 대상)가 `usersById`의 키 집합의 부분집합임을
  보장하는지 추적했다:
  - `validateAndCollectStudents`(`completeApplication` 전용,
    `SchoolCampService.java:662-672`): `candidateIds = studentsById.keySet() + applicantUserId`,
    `usersById = studentsById + {applicantUserId: applicant}` — 두 집합이 정확히 같은 모양으로
    구성되어 있어 불일치 없음.
  - `updateApplication`(`SchoolCampService.java:318-326`): `candidateIds = diff.addedStudentIds()`
    를 넘기고 `usersById`로는 `studentsById`를 그대로 넘긴다. `computeMemberDiff`
    (`SchoolCampService.java:548-579`)를 확인한 결과 `addedStudentIds`는 `newStudentIds`
    (= `studentsById.keySet()`)에서 `existingStudentIds`를 뺀 부분집합이라, 항상
    `studentsById.keySet()`의 부분집합임이 보장된다.
  - `findParticipatedStudentIdsInMonth`(`SchoolCampMemberRepository.java:30-39`, 이번 diff에서
    변경 없음)는 `m.studentUser.id in :candidateIds` 조건으로 조회하므로, 반환되는
    `participated`도 항상 `candidateIds`의 부분집합이다. 따라서
    `participated ⊆ candidateIds ⊆ usersById.keySet()`이 항상 성립해
    `toConflictingMemberResponse(usersById.get(studentId))`에서 `null`이 나올 경로가 없다.
- `User.gbsw` 연관관계(`User.java:45`, `@JoinColumn(nullable = false)`)를 확인해
  `toConflictingMemberResponse`의 `user.getGbsw()` 호출도 NPE 위험이 없음을 확인했다.
- `findExistingStudents`(`SchoolCampService.java:685-709`)가 `studentUserId`가 있는 후보만
  모으는 것을 재확인해, "기타"(자유 입력) 팀원이 애초에 `candidateIds`/`usersById`에
  포함되지 않는다는 기획서의 전제(129-131행)가 실제 코드와 일치함을 확인했다.
- `CustomException(ErrorCode, Object data)` 생성자(`CustomException.java:41-45`)가 기존에
  이미 존재하는 패턴(기획서가 언급한 `PhoneAuthService`와 동일)인지 확인해, 이번 PR이 새
  패턴을 들이지 않고 기존 패턴을 재사용했음을 확인했다.
- 테스트 3건(`throwsAndReleasesWhenAlreadyParticipatedThisMonth` 보강,
  `includesAllConflictingMembersWhenMultipleParticipatedThisMonth` 신규,
  `updateApplication`의 `throwsWhenAddedMemberAlreadyParticipatedThisMonth` 보강)이 단순히
  `ErrorCode`만이 아니라 `data.conflictingMembers()`의 `studentUserId`/`studentRealName`
  값까지 실제로 단언하는지 확인했고, 동시에 여러 명이 걸리는 케이스(기획서 138-139행이
  "이번 이슈 핵심 케이스"로 지정)가 새 테스트로 커버되는지 확인했다.
- 코드 스타일([code-style.md](../../rules/code-style.md)) — 들여쓰기 2칸, import 정렬(static/
  일반 각각 알파벳 순, 와일드카드 없음), 공개 DTO Javadoc 존재 여부를 육안으로 확인했다.
- 소스 코드는 수정하지 않았다(리뷰 전용).

## Critical/High/Medium/Low 없음

기획서가 확정한 계약(엔드포인트/요청/성공 응답 불변, `409 SCHOOLCAMP_003`의 `data`만 보강,
새 에러 코드 없음)을 diff가 정확히 그대로 구현했고, 위 방법으로 점검한 범위에서
Critical/High/Medium/Low 등급 결함을 발견하지 못했다. 구체적으로 확인한 사항:

- **범위**: 신규 DTO 2개(필드 구성이 기획서 81-91행과 정확히 일치), `SchoolCampErrorCode`
  Javadoc만 갱신(코드/메시지 불변), `SchoolCampService` 호출부 2곳 수정 — 기획서 범위를
  벗어난 변경(예: 새 엔드포인트, 새 에러 코드, 무관한 리팩터링)은 없다.
- **추가 DB 쿼리 없음**: `usersById`는 두 호출부 모두 이미 조회해둔 `Map<Long, User>`를
  재사용하며, `toConflictingMemberResponse`가 별도 조회를 하지 않는다 — 기획서의
  "추가 DB 쿼리 없음" 주장(122-124행)과 diff가 일치한다.
- **null 처리**: `usersById.get(studentId)` 미스로 인한 NPE 경로 없음(위 리뷰 방법 절 참고).
  `Gbsw`는 `User`의 `nullable = false` FK라 `user.getGbsw()`도 안전하다.
- **미가입/자유 입력 팀원 처리**: `candidateIds`/`usersById`가 `studentUserId`가 있는 후보만
  구성되므로, "기타" 팀원이 월 중복으로 걸리는 경우 자체가 발생하지 않는다 — 기획서
  125-128행의 의도된 동작과 일치한다.
- **컨벤션**: `CustomException(ErrorCode, data)`는 이 프로젝트에 이미 있는 패턴을 그대로
  재사용했고, DTO/Javadoc 스타일도 기존 `SchoolCampMemberResponse` 계열과 통일되어 있다.
- **테스트**: 신규/보강된 3개 테스트 모두 `data.conflictingMembers()`의 실제 값(학생 ID,
  실명)을 단언해 형식적인 `ErrorCode`만 검증하는 회귀가 아니며, 기획서가 핵심 케이스로
  지정한 "대표 신청자 + 팀원 동시 중복"도 새 테스트로 커버된다.
