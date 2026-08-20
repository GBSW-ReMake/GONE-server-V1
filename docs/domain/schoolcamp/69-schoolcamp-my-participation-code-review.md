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
