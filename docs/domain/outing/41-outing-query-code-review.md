# #41 외출증 본인/담당/단건 조회 API — 코드 리뷰 결과

관련 기획서: [41-outing-query.md](./41-outing-query.md)
형식 규칙: [code-review-template.md](../../rules/code-review-template.md)

컨텍스트 격리를 위해 구현 대화 기록 없이 별도 에이전트를 띄워, `merge-base`(84d74f7) ~
`feat/#41-outing-query` 전체 diff와 기획서만 전달해 `code-review` 스킬로 리뷰했다.

**요약**: 기본 구현 리뷰(1~5번) — Critical/High 없음. Medium 1건, Low 4건 발견. Medium
1건은 QA(10단계)에서 실서버로 재현해 해소, Low 1건(2번)은 리뷰 직후 바로 수정, 나머지 Low
3건은 보류(사유 각 항목에 명시). 페이지네이션 추가분 재리뷰(6~7번) — High 1건(오버플로
버그, 즉시 수정), Low 1건(테스트 커버리지, 즉시 수정) — 상세는 아래 해당 절 참고.

---

## 1. 🟡 Medium — `GET /{code}`의 무관한 사용자 403 경로가 통합 테스트로 커버되지 않음

**문제**: `OutingDetailAuthorizationTest`(`src/test/java/com/remake/gone/outing/controller/OutingDetailAuthorizationTest.java`)는
인증 없음(401)만 확인한다. `GET /api/v1/outings/{code}`는 `@PreAuthorize("isAuthenticated()")`
(역할 무관, 로그인만 확인)이고, 실제 소유권 판단은 `OutingService.validateDetailAccess`가
담당한다(`src/main/java/com/remake/gone/outing/service/OutingService.java`) — 신청 학생
본인/담당 선생님/`DISCIPLINE`/`ADMIN` 중 아무것에도 해당하지 않으면 403 `OUTING_007`.

이 소유권 기반 403은 `OutingServiceTest.GetOutingDetail.rejectsUnrelatedUser`라는 **서비스
단위 테스트**로만 검증되고, 실제 `@PreAuthorize` → 컨트롤러 → 서비스 → JSON 직렬화까지
전부 거치는 **end-to-end 경로에서는 한 번도 실행되지 않는다.** 다른 두 신규 엔드포인트
(`GET /me/requests`, `GET /me/received`)는 역할 기반 403이라
`OutingMyRequestsAuthorizationTest`/`OutingReceivedAuthorizationTest`가 실제 필터 체인을
거치는 403을 검증하지만, 이 엔드포인트만 그 방식이 안 통한다(역할이 아니라 리소스 소유권이
기준이라, 실제 DB에 그 리소스와 "무관한" 사용자가 있어야 재현 가능).

**해결 방안**:
1. 실제 DB에 학생/선생님/외출증 픽스처를 심어 `@SpringBootTest` 통합 테스트를 추가한다.
   가장 확실하고 회귀 방지가 자동화되지만, 이 프로젝트에 아직 소유권 레벨까지 검증하는
   픽스처 기반 통합 테스트 선례가 없어(기존 `*AuthorizationTest`는 전부 역할 기반, DB
   데이터 불필요) 새 테스트 패턴/헬퍼를 도입하는 비용이 든다.
2. 10단계 QA에서 실서버로 수동 재현하고 그 결과를 QA 문서에 남기는 것으로 대체한다.
   비용은 낮지만, 이후 이 로직을 건드리는 회귀가 생겨도 CI가 자동으로 잡아주지 못한다.

**적용**: 방안 2 채택. QA(10단계)에서 관계없는 계정(역할 없음)으로 실서버에 직접
`GET /{code}`를 호출해 403 `OUTING_007`을 재현/확인했다(`41-outing-query-QA.md` 15번
항목). 방안 1(픽스처 기반 통합 테스트)은 이 프로젝트의 첫 사례가 될 만큼 비용이 커서,
비슷한 소유권 검증이 두 번째로 필요해지는 시점(예: #43 출발/도착 보고)에 공용 테스트
헬퍼와 함께 재검토하는 게 낫다고 판단.

---

## 2. 🟢 Low — `status` 쿼리 파라미터가 `DEPARTED`/`RETURNED`도 유효한 값으로 받아들임 (수정 완료)

**문제**: 기획서(`41-outing-query.md`)는 "`DEPARTED`/`RETURNED`는 필터 값에서 뺀다 — 지금
넣어봐야 항상 빈 결과만 나오는 죽은 옵션"이라고 명시했지만, 최초 구현은 응답 DTO의 `status`
필드와 필터 파라미터에 **같은 `OutingStatus` enum**을 재사용해서
`GET /outings/me/requests?status=DEPARTED` 같은 요청도 `200 OK` + 빈 배열로 통과시켰다.

기능적 피해는 없다(그 값들로 필터링해도 실제로 있을 수 없는 상태라 항상 빈 배열 — 기획서가
예측한 그대로). 문제는 이 기획의 핵심 원칙인 **"모순된 조합은 조용히 무시하지 않고 에러로
알린다"**(엄격 모드, `period`/`dateFrom`/`dateTo` 조합 검증에 이미 적용된 원칙)와
어긋난다는 점이다 — `status=DEPARTED`는 "이 서버에서 지금 절대 성립할 수 없는 필터"인데도
클라이언트에게 아무 신호 없이 그냥 빈 배열을 돌려준다. 클라이언트 개발자가 오타/오해로
`DEPARTED`를 보냈을 때 "필터가 잘못됐다"가 아니라 "그냥 데이터가 없다"로 오인하기 쉽다.

**해결 방안**:
1. **전용 필터 enum을 새로 만든다** — `status` 파라미터 타입을 `OutingStatus`가 아니라
   `PENDING`/`APPROVED`/`REJECTED`/`MISSED`만 있는 별도 enum(`OutingQueryStatus`)으로
   바꾼다. 정의되지 않은 값(`DEPARTED` 포함)을 보내면 Spring의 enum 바인딩 실패로 자동으로
   `400`이 된다(이미 `period`/날짜 파싱 실패와 같은 메커니즘, 신규 코드 불필요). 응답 DTO의
   `status` 필드는 기존 `OutingStatus`를 그대로 둬서(질문에 답하는 값의 범위 ≠ 필터로 받는
   값의 범위) 나중에 4/5번 엔드포인트가 생겨도 스키마 변경이 없다.
2. **서비스 코드에서 수동 검증한다** — 파라미터 타입은 `OutingStatus`로 유지하고,
   서비스에서 `status == DEPARTED || status == RETURNED`면 별도 에러 코드로 거부한다. 새
   enum을 안 만들어도 되지만, "필터로 받을 수 있는 값의 범위"라는 도메인 규칙이 타입
   시스템이 아니라 `if`문 안에 숨어버려서(런타임에만 드러남) 다음 사람이 놓치기 쉽고,
   이 프로젝트가 이미 `OutingTimeSlot`/`OutingQueryPeriod`에서 "허용된 값만 있는 좁은
   enum"을 반복적으로 쓰는 컨벤션과도 어긋난다.

**적용**: 방안 1 채택(`OutingQueryStatus` 추가, `outing/enums/OutingQueryStatus.java`).
타입 시스템으로 표현 가능한 제약을 굳이 런타임 `if`로 다시 표현하지 않는 쪽이 이
프로젝트의 기존 enum 사용 패턴과 더 일관된다고 판단했다.

---

## 3. 🟢 Low — 목록 조회 엔드포인트에서 N+1 쿼리 가능성

**문제**: `Outing.student`/`Outing.teacher`는 `@ManyToOne(fetch = FetchType.LAZY)`
(`src/main/java/com/remake/gone/outing/entity/Outing.java:53-60`), 그리고
`User.gbsw`도 `@OneToOne(fetch = FetchType.LAZY)`
(`src/main/java/com/remake/gone/user/entity/User.java:41-43`)다.

`getMyRequests`/`getReceivedOutings`(`OutingService.java`)는 날짜 범위로 **여러 건**의
`Outing`을 한 번의 쿼리로 가져온 뒤(`findByStudentIdAndOutingDateBetween...`), 각 건마다
`toResponse(...)`에서 `student.getName()`, `student.getGbsw().getName()`,
`teacher.getGbsw().getName()` 등을 호출한다. 이 필드들이 LAZY라 각 `Outing`을 순회하면서
처음 접근하는 순간 Hibernate가 그때그때 별도 `SELECT`를 날린다 — 결과가 N건이면 최악의
경우 "1번(Outing 목록) + 최대 N번(student) + 최대 N번(student.gbsw) + 최대 N번(teacher) +
최대 N번(teacher.gbsw)" 쿼리가 나갈 수 있다(같은 학생/선생님이 여러 건에 걸쳐 반복되면
Hibernate 1차 캐시가 같은 영속성 컨텍스트 안에서 중복 조회를 걸러주므로 실제로는 이보다
적을 수 있다).

기존 `applyOuting`/`approveOuting`/`rejectOuting`/`getOutingDetail`은 전부 **단건** 처리라
이 패턴 자체가 없었다 — `getMyRequests`/`getReceivedOutings`가 이 도메인에서 "목록"을
반환하는 첫 엔드포인트라 처음 나타나는 문제다.

**영향 범위**: 기획서의 "정책 가정"(학생 한 명이 하루에 신청 가능한 건수가 시간대 수로 이미
제한됨)에 따르면 `period` 기본값(`THIS_WEEK`)이나 `TODAY` 조회에서는 결과 건수가 원래
작아 체감 영향이 거의 없다. 다만 `period=THIS_MONTH`나 `CUSTOM`으로 넓은 범위(수개월~수년)를
조회하면, 특히 `GET /me/received`(선생님 한 명이 담당하는 서로 다른 학생 수만큼 쿼리가
늘어날 수 있음)에서 건수가 늘어날수록 쿼리 수도 같이 늘어난다.

**해결 방안**:
1. **`@EntityGraph` 또는 JPQL `JOIN FETCH`로 즉시 로딩** — 리포지토리 메서드에
   `@EntityGraph(attributePaths = {"student", "student.gbsw", "teacher", "teacher.gbsw"})`를
   붙이거나, 동일 내용의 `JOIN FETCH` JPQL을 작성해 한 번의 쿼리(SQL JOIN)로 전부 가져온다.
   근본적인 해결책이고 이후 목록 조회가 늘어나도 안전하지만, 이 프로젝트에 아직
   `@EntityGraph`/`JOIN FETCH` 선례가 없어(기존 리포지토리는 전부 파생 쿼리 메서드) 새
   패턴을 도입하는 셈이다.
2. **지금은 보류하고 실측 후 판단** — 학교 하나 규모(하루 처리 건수가 작음)에서는 추가
   쿼리가 몇 개~몇십 개 수준이라 응답 시간에 체감 영향이 없을 가능성이 높다. 실제 운영
   중 `period=THIS_MONTH` 사용 빈도나 담당 학생 수가 커져 문제가 확인되면 그때 방안 1을
   적용한다(YAGNI — 이 프로젝트가 이미 여러 곳에서 채택한 "지금 단계에서 미리 최적화하지
   않는다" 기조와 일치).

**적용**: 방안 2 채택(보류). 실사용 데이터로 실제 병목이 확인되기 전까지는 조기 최적화로
보고 미룬다 — 병목이 확인되면 방안 1로 해결.

---

## 4. 🟢 Low — `status=REJECTED`/`APPROVED` 필터 조합에 대한 단위 테스트 부재

**문제**: `OutingServiceTest.GetMyRequests`에는 `status=PENDING`/`status=MISSED` 필터
테스트(`statusFilterExcludesMissedWhenFilteringPending`,
`statusFilterReturnsOnlyMissed`)만 있고, `REJECTED`/`APPROVED`로 필터링하는 케이스는
자동화 테스트에 없다(10단계 QA에서 실서버로 `status=REJECTED` 필터를 수동 확인하긴 했다 —
`41-outing-query-QA.md` 5번 항목). 필터 로직 자체(`response.status() == effectiveFilter`)는
어떤 `OutingStatus` 값이든 동일하게 동작하는 범용 비교라 로직 결함 위험은 낮지만, 이
비교식이 나중에 바뀌면(예: 여러 상태를 한 번에 걸러야 하는 요구사항이 생겨 로직이
복잡해지면) `PENDING`/`MISSED` 경로만 테스트돼 있어 회귀를 못 잡을 수 있다.

**해결 방안**:
1. `GetMyRequests`에 `status=REJECTED`, `status=APPROVED` 각각에 대한 테스트를
   추가한다(기존 `statusFilterReturnsOnlyMissed`와 같은 패턴으로 복사-수정 수준이라 비용이
   낮다).
2. 지금처럼 QA 수동 확인으로 커버 범위를 유지하고 자동화는 추가하지 않는다 — 필터 로직이
   워낙 단순(`enum` 동등 비교 한 줄)해서 자동화 테스트를 추가해도 실질적으로 잡아낼 결함
   유형이 거의 없다고 볼 수도 있다.

**적용**: 보류(방안 2에 가깝게 QA 수동 확인 유지). 필터 로직이 향후 복잡해지는 시점에
방안 1을 같이 적용하는 게 비용 대비 합리적이라고 판단.

---

## 5. 🟢 Low — `getReceivedOutings`의 `period` 검증 실패 경로 전용 테스트 없음

**문제**: `period=CUSTOM`인데 날짜 누락, `period≠CUSTOM`인데 날짜 동봉, `dateFrom > dateTo`
같은 검증 실패 케이스는 `OutingServiceTest.GetMyRequests`에서만 테스트되고,
`GetReceivedOutings`에는 대응하는 테스트가 없다. 두 메서드가
`resolveQueryRange`/`validatePeriodParams`(`OutingService.java`)를 완전히 공유하므로 실제
로직 결함이 있을 가능성은 낮지만, "두 엔드포인트가 완전히 같은 검증 계약을 갖는다"는 사실이
테스트로 명시적으로 보장돼 있지는 않다 — 나중에 두 메서드 중 하나만 리팩터링하다가
공유 로직 호출을 빠뜨려도(예: `resolveQueryRange` 호출을 실수로 지워도) `GetReceivedOutings`
쪽에서는 이를 잡아낼 테스트가 없다.

**해결 방안**:
1. `GetReceivedOutings`에도 `GetMyRequests`의 검증 실패 테스트 3종(CUSTOM 날짜 누락,
   비CUSTOM 날짜 동봉, dateFrom > dateTo)을 동일하게 추가한다 — 복사-수정 수준의 비용.
2. 공유 로직이므로 `GetMyRequests` 쪽 테스트만으로 충분하다고 보고 중복 테스트를 만들지
   않는다 — 테스트 코드량을 줄이는 대신, 위에서 설명한 "리팩터링 중 공유 호출 누락"
   시나리오에는 두 메서드 중 하나가 무방비 상태가 된다.

**적용**: 보류(방안 2). 두 메서드가 정말로 로직을 공유하는 한 위험은 낮다고 보고, 로직이
갈라지는 변경이 생기는 시점에 방안 1을 적용하기로 한다.

---

## 페이지네이션 추가분 재리뷰 (구현 중 추가, 별도 라운드)

위 1~5번 리뷰 이후, 목록 조회 두 엔드포인트에 `page`/`size` 페이지네이션을 추가했다(기획서
"페이지네이션" 절 참고). 이 추가 코드만 별도로 독립 에이전트(컨텍스트 격리, diff +
`PageResponse`/`OutingController`/`OutingService`/`OutingErrorCode`/테스트 파일 현재 상태 +
기획서 "페이지네이션" 절만 전달)에게 다시 리뷰를 맡겼다.

## 6. 🟠 High — `page`가 매우 크면 `page*size`가 `int` 오버플로되어 500이 남 (수정 완료)

**문제**: `PageResponse.of(...)`(`src/main/java/com/remake/gone/common/response/PageResponse.java`)가
`fromIndex`를 `page * size`로 `int` 산술로 계산했다. `OutingService.validatePageParams`는
`page < 0`만 거부하고 `page`의 상한은 검증하지 않는다(상한을 미리 알 방법이 없다 — 결과
건수는 DB 조회 이후에야 정해짐). 그래서 `?page=999999999&size=100`처럼 `page`가 아주 크게
오면 `page * size`가 `int` 범위를 넘어 **음수로 오버플로**되고, 그 값이 그대로
`allContent.subList(fromIndex, toIndex)`의 `fromIndex`로 들어가 `IndexOutOfBoundsException`이
발생한다. `GlobalExceptionHandler`에 이 예외 전용 핸들러가 없어 일반 폴백(500, 스택
트레이스 로깅)으로 떨어진다 — 기획서가 명시한 "마지막 페이지 다음 페이지 요청은 흔한
상황이라 에러가 아니라 빈 배열"이라는 계약을 오버플로 구간에서는 지키지 못하는 셈이다.
`hasNext` 계산(`(page + 1) < totalPages`)도 같은 이유로 `page`가 `Integer.MAX_VALUE` 근처면
`page + 1`이 음수로 오버플로돼 `hasNext`가 잘못 `true`로 나올 수 있었다.

**해결 방안**:
1. **`PageResponse.of` 내부에서 `long` 산술로 계산 후 `totalElements`로 clamp** —
   `fromIndex`/`hasNext` 계산 모두 `long`으로 올려 오버플로 자체가 발생하지 않게 하고,
   최종적으로 `int`로 좁힐 때는 항상 `[0, totalElements]` 범위 안으로 잘라낸다. `page`의
   상한을 미리 검증할 필요 없이(애초에 상한을 알 수 없으므로) 어떤 `page` 값이 와도 항상
   안전하게 빈 페이지로 수렴한다 — 기획서의 "마지막 페이지 다음은 에러 아님" 계약과
   자연스럽게 일치.
2. **`OutingErrorCode`에 오버플로 전용 코드를 추가해 400으로 거부** — `page`가 특정 상한
   (예: `Integer.MAX_VALUE / size`)을 넘으면 `OUTING_015`(기존 페이지 파라미터 에러)로
   거부한다. 다만 이 상한은 `size`에 따라 달라지고, "존재하지 않는 먼 페이지"와 "오버플로를
   일으키는 페이지"를 구분해 각각 다르게 응답하는 게 클라이언트 입장에서 더 혼란스러울 수
   있다(둘 다 "그 페이지엔 아무것도 없다"는 같은 의미인데 하나는 200/빈 배열, 하나는 400).

**적용**: 방안 1 채택. `PageResponse.of`가 `long fromIndexLong = (long) page * size`로
계산한 뒤 `Math.min(fromIndexLong, totalElements)`로 clamp하도록 수정했고, `hasNext`도
`(page + 1L) < totalPages`로 바꿔 같은 오버플로를 막았다. 오버플로를 일으키는 `page`도
"존재하지 않는 먼 페이지"와 본질적으로 같은 상황이라 별도 에러 코드로 구분하지 않고 항상
빈 `content`로 응답하는 쪽이 기획서 계약과도, 클라이언트 입장에서도 더 일관적이라고
판단했다. 회귀 방지용으로 `PageResponseTest`(신규,
`src/test/java/com/remake/gone/common/response/PageResponseTest.java`)에
`veryLargePageDoesNotOverflow` 케이스를 추가했다.

## 7. 🟢 Low — "마지막 페이지 다음 페이지" 시나리오가 자동화 테스트에 없었음 (수정 완료)

**문제**: pagination 추가 시점의 유일한 자동 테스트(`OutingServiceTest.paginatesAndReportsHasNext`)는
데이터가 있는 페이지만 검증했고, 기획서가 명시적으로 "에러 아님"이라 강조한 "마지막 페이지
다음 페이지" 케이스는 자동화 테스트로 보장되지 않았다(QA 문서 23번 항목으로 실서버 수동
검증만 돼 있었음).

**해결 방안**:
1. `PageResponseTest`에 마지막 페이지 다음 페이지 케이스를 전용 단위 테스트로 추가한다 —
   `PageResponse.of`가 이 로직의 실제 소재지라 여기서 검증하는 게 가장 직접적이다.
2. `OutingServiceTest`에서 서비스 레벨로 같은 시나리오를 추가한다 — `PageResponse.of` 자체는
   이미 커버되지만, 서비스가 그 결과를 그대로 전달하는지까지 한 번 더 확인할 수 있다.

**적용**: 방안 1 채택(`PageResponseTest.pageBeyondLastPageReturnsEmptyContent`). 이 로직은
`PageResponse.of` 안에 전부 있고 서비스는 그 결과를 그대로 통과시키기만 하므로, 서비스
레벨에서 같은 케이스를 중복 검증할 필요는 낮다고 판단했다.
