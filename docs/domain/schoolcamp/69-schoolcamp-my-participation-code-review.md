# #69 스쿨캠핑 본인 참여 내역 조회 API — 코드 리뷰 결과

리뷰 대상: `git diff dev...HEAD`(브랜치 `feat/#69-schoolcamp-my-participation`)의 아래 5개
커밋. 기획서 커밋(`4bfdec9`)은 문서만이라 제외.
- `d41eb44` feat(schoolcamp): #69 응답 DTO/enum/에러 코드 추가
- `10ca5ee` feat(schoolcamp): #69 참여 내역 조회용 리포지토리 메서드 추가
- `7e0ea9f` feat(schoolcamp): #69 참여 내역 목록/상세 서비스 로직 구현
- `1db471d` feat(schoolcamp): #69 참여 내역 목록/상세 API 엔드포인트 추가
- `49464cc` test(schoolcamp): #69 참여 내역 목록/상세 단위·인가 테스트 추가

리뷰 방식: 구현 대화 맥락 없이 diff + 기획서(`69-schoolcamp-my-participation.md`)만 보고
독립적으로 점검(코드 리뷰 컨텍스트 격리 규칙). 리뷰 레벨: medium.

## 요약
Critical/High 없음. Medium 1건(테스트 커버리지 갭). Low 없음.

기획서와 구현이 정확히 일치한다 — 목록 응답에 `members` 필드가 없는 것, 목록/상세 모두
`cancelledAt IS NULL` 필터 없이 취소 이력을 포함하는 것, 상세 조회의 참여자 판정
(`SchoolCampMember.studentUser.id` 일치 여부로 대표/팀원 통합 판정 후 `403 SCHOOLCAMP_013`),
`PageResponse.of(...)`로 먼저 페이지를 자른 뒤 그 페이지 항목만 DTO로 변환하는 순서(N+1
회피), `SchoolCampService`의 `MIN_PAGE_SIZE`(1)/`MAX_PAGE_SIZE`(100)/`validatePageParams`가
`outing.OutingService`(`OUTING_015`)와 동일한 규칙·메시지 문구인 것까지 모두 코드와
기획서가 일치함을 확인했다. `1_schoolcamp-domain.md` 마스터 기획서 갱신도 실제 최종 설계와
일치하며 범위를 벗어난 변경이 없다.

## 발견 사항

### 1. 🟡 Medium — 참여자 아님(`SCHOOLCAMP_013`) 403 경로가 엔드투엔드로 검증되지 않음

**문제**: `SchoolCampMyParticipationResponse getMyParticipationDetail`
(`src/main/java/com/remake/gone/schoolcamp/service/SchoolCampService.java:333-354`)의
"본인이 참여자가 아니면 403"이라는 핵심 인가 로직은
`SchoolCampServiceTest.GetMyParticipationDetail.throwsWhenNotParticipant`
(`src/test/java/com/remake/gone/schoolcamp/service/SchoolCampServiceTest.java:646-662`)로만
검증된다 — 서비스를 직접 호출하는 단위 테스트다.

반면 이번 커밋에서 함께 추가된 `SchoolCampAuthorizationTest`
(`src/test/java/com/remake/gone/schoolcamp/controller/SchoolCampAuthorizationTest.java:379-412`)의
신규 테스트 4개는 전부 인증 없음(401)·`TEACHER` 역할(403, `COMMON_003`)만 다루고, "인증된
`STUDENT`가 본인이 참여하지 않은 신청을 조회"하는 케이스는 실제 `@PreAuthorize`/필터
체인/JSON 직렬화를 거치는 통합 경로에서 한 번도 실행되지 않는다. `GET /me` 목록 엔드포인트의
`page`/`size` 검증 실패(`SCHOOLCAMP_012`) 역시 마찬가지로 서비스 단위 테스트로만 커버된다.

이 프로젝트에 이미 문서화된 것과 동일한 패턴이다 —
[code-review-template.md](../../rules/code-review-template.md)의 예시 자체가 `outing`
도메인의 소유권 기반 403이 통합 테스트로 커버되지 않는다는 사례를 다룬다. `#69`도 구조가
같다: 역할(Role) 기반 403은 컨트롤러 통합 테스트가 있지만, 그보다 더 중요한 리소스
소유권(참여자 여부) 기반 403은 서비스 단위 테스트에만 있다.

**해결 방안**:
1. 실제 DB에 학생 2명 + 신청 1건 픽스처를 만들어 `@SpringBootTest` 통합 테스트를 추가해
   "참여자가 아닌 인증된 `STUDENT`가 `GET /applications/{id}` 호출 → 403
   `SCHOOLCAMP_013`"을 검증한다 — 가장 확실하지만, 이 프로젝트에는 아직 소유권 레벨까지
   검증하는 픽스처 기반 통합 테스트 선례가 없어(`SchoolCampAuthorizationTest`도 역할
   기반만 다룸) 새 테스트 패턴을 도입하는 비용이 든다.
2. QA 단계(10단계)에서 실서버로 수동 재현해 QA 문서에 결과를 남기는 것으로 대체한다 —
   비용은 낮지만 이후 회귀가 생겨도 자동으로 잡히지 않는다. `outing` 도메인도 같은
   트레이드오프를 택한 전례가 있다(`code-review-template.md` 예시).
3. 현재 상태(서비스 단위 테스트만)를 그대로 유지하고 리스크로만 문서에 남긴다 — 서비스
   메서드가 컨트롤러에서 파라미터 변환 없이 그대로 호출되는 얇은 위임 구조라 실패 가능성이
   낮다는 판단이면 정당화할 수 있지만, `outing` 도메인에서 동일 유형 갭이 이미 한 번
   지적된 전례가 있어 이번에도 문서화 없이 넘어가면 같은 지적이 반복될 가능성이 크다.

## 확인 범위
`git diff dev...HEAD`의 프로덕션 코드 전체(`SchoolCampController`/`SchoolCampService`/
`SchoolCampMemberRepository`/DTO 2개/enum/`SchoolCampErrorCode`)와 테스트 코드 전체를
읽고, 관련 기존 코드(`PageResponse`, `OutingService.validatePageParams`,
`OutingErrorCode.INVALID_PAGE_PARAMS`, `SchoolCampMember`/`SchoolCampApplication` 엔티티,
`teacherDisplayName`/`toMemberResponse` 기존 private 메서드, 기존 `findParticipatedStudentIdsInMonth`
쿼리)까지 대조 확인했다. 마스터 기획서(`1_schoolcamp-domain.md`) diff도 확인해 실제
구현과의 정합성 및 범위 이탈 여부를 점검했다.

## 추가 리뷰 — 선생님 조회 확장(2026-08-20)

리뷰 대상: 위 최초 리뷰(9단계) 이후, 머지 전에 "선생님도 참여 내역을 조회할 수 있게
하자"는 추가 요구사항으로 같은 브랜치에 이어서 커밋된 아래 5개(기획서 갱신 커밋
`be3a68a` 포함, 문서만이라도 "설계 변경 3"의 최종 확정 내용을 담고 있어 리뷰 범위에
포함).
- `4cb801f` feat(schoolcamp): #69 선생님 참여 내역 조회를 위한 enum/리포지토리 확장
- `b7a98f9` feat(schoolcamp): #69 참여 내역 목록/상세에 선생님 이력 병합
- `b9376c2` feat(schoolcamp): #69 참여 내역 목록/상세 API에 TEACHER 역할 허용
- `3c2652d` test(schoolcamp): #69 선생님 참여 내역 조회 단위·인가 테스트 추가
- `be3a68a` docs(schoolcamp): #69 기획서에 선생님 참여 내역 조회(설계 변경 3) 반영

리뷰 방식: 최초 리뷰와 동일(구현 대화 맥락 없이 diff + 기획서만 보고 독립적으로 점검).
리뷰 레벨: medium.

### 요약
Critical/High/Medium/Low 없음. 기획서 "설계 변경 3" 절과 구현이 정확히 일치한다.

- `SchoolCampService.collectMyParticipationSources`
  (`src/main/java/com/remake/gone/schoolcamp/service/SchoolCampService.java:444-464`)가
  학생 쪽(`memberRepository.findMyParticipations(InMonth)`)과 선생님 쪽
  (`applicationRepository.findByTeacherUserId(InMonth)`)을 각각 조회해
  `ParticipationSource` 레코드로 통일한 뒤 `campDate` 내림차순으로 병합·정렬한다 — 기획서
  4번 구현 로직 문단과 일치. `PageResponse.of(sources, page, size)`로 병합된 목록을 먼저
  자르고 그 페이지 항목만 DTO로 변환하는 순서(N+1 회피)도 최초 리뷰 때 확인한 패턴 그대로
  유지된다.
- `getMyParticipationDetail`의 `resolveTeacherRole`
  (`SchoolCampService.java:518-525`)이 팀원 행에서 못 찾은 경우에만 호출되어
  `application.getTeacherUser()`가 본인인지 null-safe하게 확인하고, 아니면 그대로
  `403 SCHOOLCAMP_013`(`NOT_APPLICATION_PARTICIPANT`, 신규 코드 추가 없이 기존 코드 재사용)을
  던진다 — 기획서 2번 구현 로직의 "찾았으면.../못 찾았으면.../어느 쪽도 아니면..." 3단
  분기와 정확히 일치.
- 컨트롤러(`SchoolCampController.java:92`, `:114`) `@PreAuthorize`가 `GET /me`,
  `GET /applications/{id}` 둘 다 `hasAnyRole('STUDENT', 'TEACHER')`로 바뀌었고, Javadoc도
  "참여자(대표, 팀원, 또는 담당 선생님)"로 갱신되어 코드와 문서가 어긋나지 않는다.
- `SchoolCampApplicationRepository.findByTeacherUserId`/`findByTeacherUserIdInMonth`
  (`SchoolCampApplicationRepository.java:63-87`)의 JPQL이 `a.teacherUser.id = :teacherUserId`
  조건, `join fetch a.session s`, `order by s.campDate desc`까지 기획서에 명시된 쿼리
  그대로다. 의도적으로 `cancelledAt` 필터를 넣지 않은 것도 "취소 여부와 무관하게 조회"라는
  기획서 요구와 맞다.
- 신규 단위 테스트가 가짜 통과가 아님을 직접 확인했다:
  `teacherRoleForOwnAssignedApplication`은 `myRole == TEACHER`와 반환된 `id`를 함께
  검증하고, `mergesStudentAndTeacherHistoriesSortedByCampDate`
  (`SchoolCampServiceTest.java:1150-1165`)는 학생 이력(`campDate` 2026-03-01)과 선생님
  이력(2026-05-01)을 섞어 스텁한 뒤 `content()`의 `id` 순서가 `[2L, 1L]`(선생님 쪽이 먼저)임을
  직접 단언해 병합·정렬 로직을 실제로 행사한다. 상세 쪽 `throwsWhenNotParticipant`
  (`SchoolCampServiceTest.java:1312-1326`)도 `teacherUser`가 아예 `null`인(자유 입력
  `teacherName`) 신청으로 스텁되어 있어, 이번에 추가된 `resolveTeacherRole`의
  `teacherUser == null` 분기까지 실제로 거쳐 403을 검증한다 — 회귀(선생님 분기 추가 후에도
  "참여자 아님" 판정이 깨지지 않는지)를 잡아낼 수 있는 유효한 테스트다.
- `SchoolCampAuthorizationTest`의 두 테스트는 `TEACHER` 단독 403 검증에서
  `STUDENT`/`TEACHER` 모두 아닌 역할(`ADMIN`) 403 검증으로 정확히 갱신되어, 역할 확장과
  모순되지 않는다. (`TEACHER`가 실제로 통과하는 200 경로에 대한 컨트롤러 통합 테스트는 없지만,
  이 파일은 원래 역할 기반 403/401만 다루는 컨벤션이라 — 최초 리뷰 Medium #1이 지적한
  소유권 기반 403의 엔드투엔드 미검증 갭과 동일 계열이며 이번 커밋이 그 갭을 새로 넓히지는
  않는다. 별도 항목으로 추가하지 않음.)

### 확인 범위
`git diff 4cb801f^..be3a68a`(위 5개 커밋)의 프로덕션 코드 전체
(`SchoolCampController`/`SchoolCampService`/`SchoolCampMyRole`/
`SchoolCampApplicationRepository`)와 테스트 코드 전체(`SchoolCampAuthorizationTest`/
`SchoolCampServiceTest`)를 읽고, 기획서 "설계 변경 3" 절 및 두 엔드포인트 절 전체, 마스터
기획서(`1_schoolcamp-domain.md`) 3-1/3-2번 절 diff와 대조 확인했다. `findValidTeacher`
(기존 메서드, 담당 선생님 지정 시 `TEACHER` 역할 검증용)와 신규 `resolveTeacherRole`이
서로 다른 목적임을 확인해 중복/재사용 누락 여부도 점검했다.
