# #68 스쿨캠핑 신청 API — 코드 리뷰 결과

관련 기획서: [68-schoolcamp-application.md](./68-schoolcamp-application.md)
리뷰 대상: `git diff dev...HEAD` (브랜치 `feat/#68-schoolcamp-application`), 총 22개 파일
(신규 마이그레이션 1건 포함) — 구현 코드/테스트/기획서 문서 변경 전체.

> **반영 결과 (2026-08-19)**: High/Medium/Low 9건 전부 조치했다.
> - 1(High)·3(Medium, N+1)·8(Low, 예외 유실)·10(Low, stale 엔티티): 코드 수정
>   (`SchoolCampService`/`SchoolCampApplicationRepository`/`SchoolCampSessionClaimService`).
> - 2(Medium, 커넥션 2개 점유): 코드 구조는 그대로 두고 기획서 "커넥션 풀 경합 대응" 절의
>   과장된 서술("문제가 사라진다")을 실제 동작에 맞게 정정 — 구조 변경은 이 이슈 범위 밖으로
>   판단(review 자체의 방안 3 채택).
> - 4(Medium, 에러 메시지): `SCHOOLCAMP_004` 기본 메시지를 원인 중립적인 문구로 수정.
> - 5(Medium, GbswUtils 테스트 없음)·6(Medium, 중복/본인포함 테스트 없음)·9(Low, 가입
>   선생님 분기 테스트 없음): 각각 테스트 추가.
> - 7(Medium, 신청 성공 테스트가 MockMvc 우회): **코드는 바꾸지 않음** — 이 프로젝트의
>   `@WebMvcTest` 슬라이스는 보안 필터 체인이 안 붙어 `@AuthenticationPrincipal`을 MockMvc로
>   검증할 수 없다는 게 이미 `OutingControllerTest`/`FileControllerTest`에 문서화된 기존
>   컨벤션이다 — 리뷰가 제안한 "MockMvc+JWT로 전환"은 오히려 이 컨벤션에서 벗어나는 것이라
>   채택하지 않고, 같은 근거를 `SchoolCampControllerTest` 클래스 Javadoc에 명시하는 것으로
>   대신했다(리뷰 자체의 방안 2 채택).

## 리뷰 범위/방법
- 기획서 「구현 로직」 1~9번, 「에러」, 「영향 받는 기존 코드」, 「테스트」 절과 실제 diff를
  한 줄씩 대조해 계약(엔드포인트/에러 코드/검증 순서/동시성 방식) 일치 여부를 확인했다.
- `SchoolCampService`/`SchoolCampSessionClaimService`/`SchoolCampSessionRepository`/
  `SchoolCampController`/DTO/엔티티/에러코드/마이그레이션/테스트 4종(단위·웹슬라이스·통합)
  전체를 읽었다.
- 컨벤션 비교 대상으로 `OutingService.java`/`OutingController.java`(담당 선생님 검증
  패턴, `@ResponseStatus(CREATED)` + `@PreAuthorize` 패턴, `LocalDateTime.now(KST)` 패턴,
  `CommonErrorCode.UNAUTHORIZED` 사용 패턴)를 대조했다.
- `GlobalExceptionHandler`를 확인해 예외 전파 시 최종적으로 어떤 HTTP 응답이 나가는지
  추적했다.
- 소스 코드는 수정하지 않았다(리뷰 전용).

## 발견 사항

### 1. 🟠 High — "유령 점유" 잔여 리스크가 세션 1건이 아니라 캘린더 API 전체를 500으로 무너뜨림

**문제**: 기획서는 `release` 호출 자체가 실패해 `taken_at`만 채워진 채 신청 데이터가 없는
"유령 점유" 상태를 잔여 리스크로 명시적으로 수용했고(`68-schoolcamp-application.md:312-316`),
"발생하면 관리자가 수동으로 `taken_at`을 비우는 것으로 충분하다"고 판단했다. 그런데
`SchoolCampService.toCalendarResponse`(`src/main/java/com/remake/gone/schoolcamp/service/SchoolCampService.java:242-245`)는
`takenAt != null`인 세션마다 활성 신청을 조회해 없으면 `IllegalStateException`을 던진다.

```java
SchoolCampApplication application =
    applicationRepository.findBySessionIdAndCancelledAtIsNull(session.getId())
        .orElseThrow(() -> new IllegalStateException(
            "점유된 세션에 활성 신청이 없습니다: sessionId=" + session.getId()));
```

`getCalendar`는 `sessions.stream().map(this::toCalendarResponse).toList()`로 그 달의 모든
세션을 한 번에 순회한다(`SchoolCampService.java:232`). "유령 점유" 세션이 그 달에 단
하나만 있어도 이 `IllegalStateException`이 스트림 처리 도중 던져지고, `GlobalExceptionHandler
.handleException(Exception.class)`(`GlobalExceptionHandler.java:259-269`)가 이를 잡아
`500 COMMON_XXX`로 응답한다 — 결과적으로 그 달의 세션이 몇 개든, 그 유령 세션과 무관한
날짜까지 포함해 `GET /api/v1/school-camps?month=` 전체가 그 달 내내 500을 반환한다. 기획서가
"수용 가능"이라 판단한 근거("발생 조건이 극히 드문 인프라 장애")는 영향 범위가 "그 세션
1개의 표시 이름 필드"라는 암묵적 전제 위에 있는데, 실제 구현은 그 전제와 달리 캘린더
조회라는, 스쿨캠핑 신청 화면 진입 시 항상 먼저 호출되는 엔드포인트 전체를 막아 신청 자체가
불가능해지는 결과를 낳는다. 게다가 기획서는 "프론트가 신청 화면이 열려있는 동안 캘린더를
2~5초 폴링"하길 권장하므로(`68-schoolcamp-application.md:429`), 유령 점유가 한 번 발생하면
그 폴링이 계속 500을 받으며 반복 실패한다.

**해결 방안**:
1. `toCalendarResponse`에서 활성 신청이 없는 점유 세션을 만나면 예외 대신 방어적으로
   `CLOSED` 상태에 표시 이름만 `null`로 채워 반환한다(그 세션 하나만 이름이 비어 보이고
   나머지 세션은 정상 표시). 장점: 기획서가 원래 의도한 "영향 범위 = 세션 1개"로 blast
   radius를 되돌리고, 별도 알림/모니터링 없이도 서비스 가용성을 지킨다. 단점: 데이터
   이상 상태를 조용히 삼키므로, 로그(`log.warn`)를 반드시 같이 남겨 관리자가 알아챌 수단을
   남겨야 한다(현재 서비스 클래스에 `@Slf4j`가 없어 추가 필요).
2. `IllegalStateException`을 유지하되 `GlobalExceptionHandler`에 전용 핸들러를 추가해 최소한
   개별 세션 단위로 격리한다 — 다만 이 방식은 스트림 처리 자체가 "한 세션 실패 시 전체
   실패"인 구조를 그대로 두고 예외 타입만 바꾸는 것이라 근본 해결이 아니다. 방안 1과 같이
   써야 실질적 효과가 있다.
3. 현재 상태를 유지하고 QA 단계에서 "유령 점유 발생 시 캘린더 500" 시나리오를 알려진
   한계로 문서화한 뒤, 발생 시 온콜이 즉시 `taken_at`을 비우는 수동 대응 절차를 마련한다.
   장점: 코드 변경 없음. 단점: 기획서가 이미 "잔여 리스크는 수용 가능한 수준"이라고
   판단한 근거 자체가 이 구현에서는 성립하지 않으므로, 이 방안을 택한다면 기획서의 리스크
   서술을 실제 영향 범위에 맞게 고쳐야 한다.

### 2. 🟡 Medium — `REQUIRES_NEW` claim 호출 순간 요청 1건이 커넥션 2개를 동시에 점유해, 의도한 "커넥션 풀 경합 완화" 효과가 부분적으로 상쇄됨

**문제**: 기획서 "커넥션 풀 경합 대응" 절은 claim을 별도 트랜잭션으로 분리하면 "당첨자가
행 잠금을 쥐는 시간이 UPDATE 한 문장 수준으로 줄어 이 문제가 사라진다"고 결론짓는다
(`68-schoolcamp-application.md:414-416`). 그런데 `applyToCamp`(`SchoolCampService.java:273-294`)는
자신이 이미 `@Transactional`(REQUIRED)로 시작한 트랜잭션 안에서 `sessionRepository.findById`로
먼저 커넥션을 점유한 뒤, 그 트랜잭션이 아직 열려 있는 상태에서
`sessionClaimService.claim(...)`을 호출한다. Spring의 `REQUIRES_NEW`는 바깥 트랜잭션을
"일시 중단"만 할 뿐 그 트랜잭션이 이미 체크아웃한 커넥션을 풀에 반납하지 않고, 새 커넥션을
풀에서 하나 더 받아 `claim`의 `UPDATE` 한 문장을 실행한다 — 즉 claim이 실행되는 그 찰나에는
"낙첨/당첨을 가리지 않고" 이 요청 1건이 HikariCP 커넥션을 2개 동시에 물고 있다. 기획서가
우려한 시나리오(재학생 300명 규모, 한 세션에 100명 이상 동시 신청, 풀 크기 10)를 그대로
대입하면, claim 실행 순간에 몰린 요청 수만큼 순간적으로 "요청 수 × 2"에 가까운 커넥션
수요가 생길 수 있어 원래 우려("커넥션 풀 전체가 묶여 무관한 다른 API까지 지연")가 완전히
사라지지 않고 그 발생 확률/정도만 줄어드는 것에 가깝다. 이 사실이 기획서의 "리스크 및
고려사항"에 반영되어 있지 않다.

**해결 방안**:
1. 기획서에 이 잔여 리스크를 명시적으로 추가하고("claim 순간 요청당 최대 2개 커넥션 필요"),
   HikariCP `maximum-pool-size`를 소폭 상향(예: 20)하는 것을 이 이슈 또는 후속 이슈 범위로
   가져온다 — 기획서가 이미 "필요성이 확인되면 별도 이슈로 다룬다"고 여지를 남겨뒀으므로
   (`68-schoolcamp-application.md:421-425`) 이번 발견을 그 "필요성 확인"의 근거로 삼을 수
   있다. 비용은 설정값 변경 한 줄로 낮지만, 근본적인 "행 잠금 직렬화"는 여전히 남는다는
   점을 기획서가 이미 인지하고 있다.
2. `applyToCamp`에서 `sessionRepository.findById(sessionId)` 조회를 트랜잭션 없이(또는
   `@Transactional(readOnly = true)`인 헬퍼 메서드로 분리해) 수행한 뒤, claim이 성공한
   경우에만 `completeApplication`을 새 트랜잭션으로 시작하도록 구조를 바꾼다. 장점: 낙첨
   경로(대다수 요청)는 claim 실행 순간 커넥션 1개만 쓴다(세션 조회용 커넥션을 미리
   반납했으므로). 단점: 현재 "세션 조회 → 형식 검증 → claim" 순서가 한 트랜잭션 경계
   안에서 자연스럽게 읽히던 코드 구조가 메서드 분리로 복잡해지고, `@Transactional`
   경계가 하나 더 늘어나 이 프로젝트에 트랜잭션 전파 패턴이 하나 더 추가된다(기획서가 이미
   "이 프로젝트에서 처음 쓰이는 패턴이라 리뷰 시 특히 꼼꼼히 볼 지점"이라고 강조한 부분과
   같은 종류의 학습 비용).
3. 현재 구조를 유지한다 — claim 자체의 실행 시간이 여전히 매우 짧아(단일 UPDATE, 수 ms),
   순간적으로 커넥션 2개를 물더라도 그 겹침 구간 자체가 극히 짧아 실제 장애로 이어질
   가능성은 낮다고 보고 수용한다. 다만 이 경우에도 기획서의 리스크 서술을 "완전히
   사라진다"에서 "크게 줄어들지만 완전히 사라지지는 않는다"로 정정해두는 것을 권장한다 —
   추후 실제 장애 발생 시 원인 규명 속도에 영향을 준다.

### 3. 🟡 Medium — `GET /api/v1/school-camps` 캘린더 응답이 점유된 세션마다 개별 쿼리를 날리는 N+1 구조

**문제**: `getCalendar`는 그 달의 모든 세션을 한 번에 조회한 뒤(`SchoolCampService.java:222-223`),
`toCalendarResponse`(`SchoolCampService.java:235-253`)에서 점유된(`takenAt != null`) 세션
하나하나에 대해 `applicationRepository.findBySessionIdAndCancelledAtIsNull(session.getId())`를
개별 호출한다. 요일 제한(금/토/일 제외) 때문에 한 달 최대 세션 수는 20여 개 안팎으로
크지 않지만, 점유된 세션 수만큼(등록 오픈이 몰리는 인기 달에는 대부분의 평일이 마감될 수
있음) 쿼리가 그대로 늘어나는 전형적인 N+1이다. 기획서 "확장성/성능" 절은 "월 중복 확인
쿼리는 후보 학생 수로 상한이 있어 N+1 우려 없음"이라고만 언급했을 뿐
(`68-schoolcamp-application.md:376-377`), 이 캘린더 응답의 세션별 조회는 검토 대상에서
빠졌다. 이 엔드포인트는 기획서 자신이 "신청 화면이 열려있는 동안 2~5초 폴링"을 권장하는
바로 그 엔드포인트라(`68-schoolcamp-application.md:429`), 등록 오픈 직후 트래픽이 몰리는
시점에 반복 호출되는 요청마다 N+1이 겹쳐 실행된다.

**해결 방안**:
1. `SchoolCampApplicationRepository`에 `findBySessionIdInAndCancelledAtIsNull(Collection<Long>
   sessionIds)`를 추가해 점유된 세션 ID를 한 번에 모아 배치 조회한 뒤, `Map<Long,
   SchoolCampApplication>`으로 변환해 `toCalendarResponse`에 전달한다. 장점: 쿼리 수가
   세션 수와 무관하게 항상 2회(세션 조회 1 + 신청 배치 조회 1)로 고정된다. 단점: 스트림
   기반의 현재 1줄짜리 매핑 로직(`sessions.stream().map(this::toCalendarResponse).toList()`)을
   "먼저 배치 조회 → Map 조립 → 매핑"으로 구조를 바꿔야 해 변경 범위가 이 메서드 전체에
   걸친다.
2. 현재 구조를 유지한다 — 한 달 최대 세션 수 자체가 20여 개로 상한이 있어(요일 제한
   덕분에 이미 자연스러운 배치 크기 제한이 걸려 있음) 실제 부하는 크지 않다고 보고
   수용한다. 장점: 변경 비용 0. 단점: 폴링 트래픽이 겹치면 그 배수만큼 쿼리 수가 늘어나는
   구조는 그대로 남아, 트래픽이 예상보다 커지면 이 부분이 병목으로 재발견될 수 있다.

### 4. 🟡 Medium — `SCHOOLCAMP_004` 에러 메시지가 총원 초과 상황에는 원인과 맞지 않음

**문제**: `SchoolCampErrorCode.INVALID_APPLICATION_FORMAT`의 고정 메시지는 "담당 선생님
정보가 올바르지 않습니다"이다(`SchoolCampErrorCode.java:361`). 기획서는 이 코드를 "총원이
8명 초과, 또는 담당 선생님/팀원 정보 형식이 잘못됨"이라는 서로 다른 세 원인에 공유하도록
설계했고(`68-schoolcamp-application.md:320-321`), `SchoolCampService.validateApplicationFormat`도
그대로 구현했다(`SchoolCampService.java:313-316`, 총원 초과 시 같은
`INVALID_APPLICATION_FORMAT`을 던짐). 그 결과 클라이언트가 팀원 9명(대표 포함)으로
신청해 400을 받으면, 서버가 내려주는 메시지는 "담당 선생님 정보가 올바르지 않습니다"로,
실제 원인(인원 초과)과 무관한 문구를 보게 된다. 코드값(`SCHOOLCAMP_004`) 자체는 기획서
그대로라 계약 위반은 아니지만, 클라이언트가 코드를 파싱하지 않고 메시지를 그대로 노출하는
경로가 있다면 사용자에게 잘못된 원인을 안내하게 된다.

**해결 방안**:
1. 메시지를 원인 중립적인 문구(예: "신청 정보가 올바르지 않습니다")로 바꾼다. 장점: 코드
   한 곳만 고치면 되고 기획서의 "코드는 004 하나로 공유" 결정을 그대로 유지할 수 있다.
   단점: 메시지가 더 추상적이 되어, 담당 선생님 형식 오류처럼 원래 구체적이던 케이스의
   안내력이 약간 떨어진다.
2. `CustomException(ErrorCode, Object data)` 오버로드(이미 `CustomException.java:41`에
   존재)를 활용해, 던지는 지점마다 상황에 맞는 메시지를 실어 보낸다(총원 초과 시
   "총원은 8명을 초과할 수 없습니다" 등). 장점: 각 위반 상황에 정확한 안내를 줄 수 있다.
   단점: `validateApplicationFormat` 내 여러 던지는 지점을 개별적으로 손봐야 하고, 이
   프로젝트에서 이 오버로드가 다른 도메인에서 실제로 쓰이는지 선례를 먼저 확인해야 한다
   (선례가 없다면 이 이슈에서 새 패턴을 들이는 셈이 된다).

### 5. 🟡 Medium — `GbswUtils.studentNumber`에 대한 직접 단위 테스트가 없음

**문제**: 기획서 "테스트" 절은 `GbswUtils.studentNumber`에 대해 "정상 케이스(반이 1자리
확인)"를 별도 테스트 항목으로 명시했다(`68-schoolcamp-application.md:476-477`). 그런데 이번
diff에는 `GbswUtilsTest` 같은 전용 테스트 파일이 없다(`git diff dev...HEAD --stat`에
`gbsw/utils` 하위 테스트 파일 없음). `AuthServiceTest`의 기존 테스트
`padsSingleDigitNumberInStudentDefaultName`(`AuthServiceTest.java:215-233`, 이번 diff에서
수정되지 않음)이 `AuthService.generateStudentDefaultName`을 거쳐 간접적으로 같은 포맷 로직을
검증하긴 하지만, 이는 `GbswUtils.studentNumber`가 독립된 `public` 유틸로 승격되기 전부터
있던 회귀 테스트일 뿐이다. `GbswUtils`는 이제 `AuthService`와 `SchoolCampService`(캘린더
응답의 `applicantDisplayName`) 두 곳에서 쓰이는 공용 유틸인데, 정작 그 유틸 자체를 대상으로
하는 테스트가 하나도 없다.

**해결 방안**:
1. `src/test/java/com/remake/gone/gbsw/utils/GbswUtilsTest.java`를 새로 추가해
   `studentNumber`의 정상 케이스(학년/반/번호 조합, 특히 기획서 예시 "3학년 2반 18번 →
   3218"과 번호가 한 자리인 경우의 0채움)를 직접 검증한다. 장점: 기획서가 요구한 테스트를
   정확히 채우고, 이 유틸이 두 곳에서 재사용되는 만큼 향후 리팩터링 시 회귀를 가장 싸게
   잡을 수 있는 지점이다. 단점: 새 테스트 파일 하나가 늘어난다(비용이라 부르기 애매할
   정도로 낮음).
2. 현재처럼 `AuthServiceTest`의 간접 테스트로 커버리지를 대신한다. 장점: 추가 비용 없음.
   단점: `SchoolCampService`가 쓰는 `applicantDisplayName` 경로(캘린더 응답)의 포맷 정합성은
   여전히 어떤 테스트로도 직접 검증되지 않고, `AuthServiceTest`가 나중에 리팩터링되며
   `generateStudentDefaultName` 테스트가 삭제/변경되면 `GbswUtils`가 조용히 무검증 상태가
   된다.

### 6. 🟡 Medium — `additionalMembers` 중복 `studentUserId`/대표 신청자 본인 포함 케이스가 테스트로 검증되지 않음

**문제**: `SchoolCampService.findExistingStudents`(`SchoolCampService.java:359-383`)는 두
갈래 검증을 명시적으로 구현한다 — ① `new HashSet<>(candidateIds).size() !=
candidateIds.size()`로 중복 `studentUserId` 검사, ② `candidateIds.contains(applicantUserId)`로
대표 신청자 본인 포함 검사. 둘 다 기획서가 "IN 절의 암묵적 dedupe에 기대지 않고 명시적으로
검사한다"고 특별히 강조한 로직이다(`68-schoolcamp-application.md:284-291`). 그런데
`SchoolCampServiceTest.ApplyToCamp`(`SchoolCampServiceTest.java` diff)에는 이 두 분기를
직접 겨냥하는 테스트가 없다 — 존재하는 테스트는 `throwsAndReleasesWhenMemberDoesNotExist`
(존재하지 않는 ID)뿐이고, 중복 ID나 자기 자신 포함 케이스는 어느 것도 다루지 않는다.
기획서 "테스트" 절도 이 두 케이스를 명시적으로 요구한다(`68-schoolcamp-application.md:453`,
"같은 studentUserId 중복, 또는 대표 신청자 본인 포함 → 400"). 이 상태에서 누군가
`findExistingStudents`를 리팩터링하다 dedupe 로직을 실수로 지워도(예: "IN 절이 알아서
줄여주니 필요 없겠지"라는 판단으로) 어떤 테스트도 실패하지 않는다.

**해결 방안**:
1. `throwsAndReleasesWhenDuplicateStudentInMembers`, `throwsAndReleasesWhenApplicantIncludedInMembers`
   두 테스트를 `SchoolCampServiceTest.ApplyToCamp`에 추가한다 — 기존
   `throwsAndReleasesWhenMemberDoesNotExist`와 동일한 mock 세팅 패턴(session/claim은
   성공시키고 이후 단계에서 `INVALID_MEMBER_INFO`가 던져지는지, `release`가 호출되는지
   검증)을 그대로 재사용할 수 있어 비용이 낮다. 장점: 기획서가 요구한 테스트 항목을
   정확히 채우고, 이 두 로직이 실수로 삭제되는 회귀를 즉시 잡는다. 단점 없음(기존 테스트
   패턴 재사용이라 새 인프라가 필요 없음).
2. 현재 상태를 유지하고 QA 단계(10단계)에서 실서버로 두 케이스를 수동 검증해 QA 문서에만
   남긴다. 장점: 코드 리뷰 단계에서 추가 작업이 없다. 단점: 이후 회귀가 생겨도 자동으로
   잡히지 않고, 매 QA 사이클마다 수동 재현 비용이 반복된다 — 코드 리뷰 템플릿 예시가
   경계하는 바로 그 트레이드오프다.

### 7. 🟡 Medium — 신청 성공(201) 컨트롤러 테스트가 MockMvc를 우회해 HTTP 계층을 검증하지 않음

**문제**: `SchoolCampControllerTest.ApplyToCamp.returns201OnSuccess`
(`SchoolCampControllerTest.java` diff)는 `new SchoolCampController(schoolCampService)`로
컨트롤러를 직접 생성해 `controller.applyToCamp(principal, 5L, request)`를 호출하고, 반환된
`ApiResponse` 객체만 검증한다. 반면 같은 파일의 `RegisterCampDates.returns201OnSuccess`
(수정되지 않은 기존 테스트, `SchoolCampControllerTest.java:71-81`)는 이 클래스가
`@WebMvcTest` + `MockMvc`로 구성돼 있다는 점을 그대로 활용해
`mockMvc.perform(post(...)).andExpect(status().isCreated())`로 실제 HTTP 상태 코드까지
검증한다. 새로 추가된 신청 성공 테스트만 이 컨벤션을 벗어나 있어, `@ResponseStatus
(HttpStatus.CREATED)`가 실제로 201을 내려주는지, `@AuthenticationPrincipal`/`@Valid`가
서블릿 필터 체인과 메시지 컨버터를 거쳐 정상 동작하는지, 응답 JSON 직렬화가 기획서 예시와
맞는지는 이 테스트로 전혀 검증되지 않는다(같은 파일의 400 테스트 두 개는 MockMvc를 쓰므로
형식 검증 경로는 커버되지만, 정상 흐름의 HTTP 응답 자체는 비어 있다).

**해결 방안**:
1. `mockMvc.perform(post("/api/v1/school-camps/5/applications")...)`로 바꾸고,
   `schoolCampService.applyToCamp(any(), eq(5L), any(), any())`를 mock한 뒤
   `.andExpect(status().isCreated())`와 `jsonPath`로 응답 바디 핵심 필드(`data.id`,
   `data.teacherDisplayName` 등)까지 검증한다 — `@AuthenticationPrincipal`을 채우려면
   이 파일의 다른 인증 관련 테스트(`SchoolCampAuthorizationTest`)가 쓰는 JWT 발급 패턴을
   가져와야 한다. 장점: 이 파일의 기존 컨벤션(`RegisterCampDates.returns201OnSuccess`)과
   완전히 일치하고, HTTP 계층 전체가 실제로 동작하는지 검증한다. 단점:
   `SchoolCampControllerTest`는 인증 필터가 꺼져 있어(`@AutoConfigureMockMvc(addFilters =
   false)`) `@AuthenticationPrincipal` 주입을 위해 별도의 인자 리졸버 mock 설정이 필요할 수
   있다 — 이 프로젝트의 다른 `@WebMvcTest`가 이미 이 문제를 어떻게 풀고 있는지 먼저 확인이
   필요하다.
2. 현재처럼 컨트롤러를 직접 호출하는 단위 테스트를 유지하되, "이건 컨트롤러의 위임 로직만
   검증하는 순수 단위 테스트"라는 것을 `@DisplayName`이나 클래스 주석에 명시하고,
   `SchoolCampAuthorizationTest`의 `applyReturns403ForTeacherRole` 테스트가 실제로
   `@PreAuthorize`/인증까지 커버하고 있으므로 HTTP 계층 검증은 그쪽에 위임한다고 문서화한다.
   장점: 테스트 코드 변경이 없다. 단점: `SchoolCampAuthorizationTest`는 403/401만 확인할 뿐
   200/201 성공 응답의 JSON 직렬화는 여전히 어디서도 MockMvc로 검증되지 않는다는 공백이
   남는다.

### 8. 🟢 Low — `applyToCamp`의 `catch` 블록에서 `release()` 실패 시 원래 예외가 유실됨

**문제**: `SchoolCampService.applyToCamp`(`SchoolCampService.java:288-293`)의 실패 처리는
다음과 같다.

```java
try {
  return completeApplication(applicantUserId, session, request, additionalMembers);
} catch (RuntimeException e) {
  sessionClaimService.release(sessionId);
  throw e;
}
```

`completeApplication`이 예: `INVALID_MEMBER_INFO`로 실패해 `catch` 블록에 들어온 뒤,
`sessionClaimService.release(sessionId)` 호출 자체가 (드물지만) DB 순단 등으로 예외를 던지면,
그 예외가 원래 던지려던 `e`(`INVALID_MEMBER_INFO`)를 대체해버린다. 원래 실패 원인이
그대로 로그에 남지 않고 "release 중 DB 오류"만 보이게 되어, 장애 발생 시 최초 원인
추적이 한 단계 더 어려워진다. 기획서의 "잔여 리스크" 서술(`68-schoolcamp-application.md:312-316`)은
release 실패 자체는 이미 인지하고 있지만, 그 실패가 원래 예외를 가리는 부수 효과까지는
다루지 않는다.

**해결 방안**:
1. `catch` 블록에서 `release()` 호출도 다시 `try-catch`로 감싸, release 실패는 별도로
   `log.error`로 남기고 원래 예외 `e`는 그대로 다시 던진다. 장점: 원인 규명 순서가
   "무엇 때문에 애초에 실패했는지"를 항상 먼저 보여준다. 단점: `SchoolCampService`에
   현재 `@Slf4j`가 없어 로거 필드를 추가해야 한다(단, `OutingService`가 이미 같은 목적으로
   `@Slf4j` + `log.warn`을 쓰고 있어(`OutingService.java:51`, `313`) 컨벤션상 자연스러운
   추가다).
2. 현재 상태를 유지한다 — release 실패가 "이 이슈 범위에서 수용한 극히 드문 인프라 장애"
   상황에서만 발생하고, 그 경우 어차피 500이 나가며 서버 로그에 스택 트레이스가 남으므로
   실무상 원인 추적이 완전히 불가능해지는 것은 아니다(예외 메시지만 바뀔 뿐). 장점: 추가
   코드 없음. 단점: release 예외의 스택 트레이스에 원래 예외가 `cause`로도 담기지 않으므로
   ("throw e"가 그대로 대체), 로그에서 두 예외를 연결 지으려면 타임스탬프로 유추해야 한다.

### 9. 🟢 Low — 캘린더 응답의 "가입된 선생님" 표시 이름 분기가 테스트로 검증되지 않음

**문제**: `SchoolCampService.teacherDisplayName(SchoolCampApplication)`
(`SchoolCampService.java:465-469`)은 두 분기를 갖는다 — `application.getTeacherUser() !=
null`이면 가입된 선생님의 `Gbsw` 실명을, 아니면 자유 입력한 `teacherName`을 반환한다.
`SchoolCampServiceTest.GetCalendar.returnsClosedSessionWithDisplayNames`
(`SchoolCampServiceTest.java` diff)는 `SchoolCampApplication.builder()...teacherName("박선생")
.build()`만 사용해 `teacherUser`가 항상 `null`인 케이스만 검증한다 — "가입된 선생님을
선택한 경우"(`teacherUser != null`) 분기는 캘린더 응답 경로에서 한 번도 실행되지 않는다.
기획서 "테스트" 절도 이 엔드포인트에 대해 "가입 선생님/자유 입력 두 케이스" 둘 다를 요구한다
(`68-schoolcamp-application.md:478-480`). (참고: `applyToCamp` 성공 테스트(`appliesSuccessfully`)는
신청 응답 DTO를 만드는 `toApplicationResponse`의 별도 인라인 로직(`teacher != null ?
teacher.getGbsw().getName() : ...`, `SchoolCampService.java:442-443`)에서 가입 선생님
케이스를 검증하지만, 이는 캘린더 응답의 `teacherDisplayName` 헬퍼와는 다른 코드 경로다.)

**해결 방안**:
1. `returnsClosedSessionWithDisplayNames` 테스트를 하나 더 추가하거나 파라미터화해,
   `teacherUser`가 설정된 `SchoolCampApplication`에 대해서도 같은 방식으로
   `teacherDisplayName`이 채워지는지 검증한다. 장점: 기획서가 요구한 두 케이스를 모두
   채우고, 새 mock 세팅 없이 기존 테스트의 fixture를 조금만 바꾸면 된다(`teacherName` 대신
   `teacherUser(studentUser(...))` 설정). 단점 없음.
2. 현재 상태를 유지한다 — `teacherDisplayName`과 `toApplicationResponse`의 인라인 로직이
   사실상 동일한 삼항 조건이라, `appliesSuccessfully` 테스트가 간접적으로 그 조건식의
   정확성을 검증한다고 볼 수 있다. 장점: 추가 비용 없음. 단점: 두 코드가 물리적으로 다른
   메서드라 한쪽만 리팩터링하다 깨져도(예: `teacherDisplayName`만 실수로 널 체크를
   반대로 바꿔도) `appliesSuccessfully`는 여전히 통과한다 — 실제로는 서로 다른 코드를
   같은 테스트로 두 번 검증한다고 착각하기 쉽다.

### 10. 🟢 Low — claim 이후 outer 트랜잭션이 들고 있는 `session` 엔티티의 `takenAt`이 커밋 후에도 갱신되지 않음(향후 유지보수 함정)

**문제**: `applyToCamp`는 `sessionRepository.findById(sessionId)`로 `session` 엔티티를 outer
트랜잭션(영속성 컨텍스트 A)에 로드한 뒤(`SchoolCampService.java:276-277`), `claim`을
`REQUIRES_NEW`(별도 영속성 컨텍스트 B)로 실행해 DB의 `taken_at` 컬럼을 직접 벌크
`UPDATE`한다. JPA 벌크 업데이트는 실행한 그 영속성 컨텍스트(B) 밖에 있는 엔티티(A가 들고
있는 `session` 객체)를 동기화하지 않으므로, claim이 성공해 커밋된 뒤에도 outer 트랜잭션이
들고 있는 `session.getTakenAt()`은 여전히 메모리상 원래 값(신규 세션이면 `null`)을 반환한다.
현재 `completeApplication`(`SchoolCampService.java:319-346`)은 이 `session.getTakenAt()`을
어디서도 읽지 않아 지금 당장 눈에 보이는 버그는 없다. 다만 이후 누군가 "이미 점유된
세션인지 다시 한번 확인하자"는 의도로 `session.getTakenAt() != null` 같은 코드를
`completeApplication` 안에 추가하면, claim 직후인데도 항상 `null`(미점유)로 보여 조용히
틀린 분기를 타게 된다 — 컴파일 에러 없이 발견하기 어려운 함정이라는 점에서, 기획서가 이미
경고한 "`this`로 호출하면 `REQUIRES_NEW`가 조용히 무시된다"는 함정과 성격이 같다.

**해결 방안**:
1. `SchoolCampSessionRepository.claim`에 `@Modifying(clearAutomatically = true)`를 추가해,
   벌크 업데이트 이후 영속성 컨텍스트를 자동으로 비우도록 한다. 장점: Spring Data JPA
   표준 옵션이라 코드 한 줄로 해결되고, 이후 같은 트랜잭션에서 `session`을 다시 읽으면
   자동으로 재조회된다. 단점: `clearAutomatically`는 claim을 호출한 "그 트랜잭션"의
   영속성 컨텍스트만 비우는데, claim은 REQUIRES_NEW로 실행되어 별도 영속성 컨텍스트(B)에서
   동작하므로 실제로는 outer 트랜잭션(A)의 컨텍스트에 효과가 없을 가능성이 높다 — 적용
   전에 실제로 A의 캐시가 비워지는지 통합 테스트로 확인이 필요하다(효과가 없다면 이
   방안은 기각).
2. `SchoolCampSessionClaimService`/`SchoolCampSessionRepository`의 Javadoc에 "claim 성공
   이후 호출한 쪽이 들고 있던 `SchoolCampSession` 엔티티의 `takenAt` 필드는 갱신되지
   않는다 — 점유 여부를 다시 확인해야 하면 반드시 리포지토리를 통해 재조회하라"는 경고를
   추가한다. 장점: 비용이 거의 없고, 지금 당장 동작을 바꾸지 않아 회귀 위험이 없다. 단점:
   문서화만으로는 다음 작성자가 실제로 그 경고를 읽으리라는 보장이 없다 — 코드 자체가
   막아주는 방안 1보다 안전성이 낮다.

## Critical 없음

세션 점유 로직·에러 코드 분기·전체 흐름을 검토한 범위에서 서비스 전체 장애나 데이터
유실로 즉시 이어지는 Critical 등급 결함은 발견하지 못했다. 1번(High)이 캘린더 API를
정지시킬 수 있는 시나리오이긴 하나, 발생 조건 자체가 기획서가 이미 "극히 드문 인프라
장애"로 규정한 잔여 리스크에 의존하므로 Critical(상시·재현 가능한 장애)이 아니라 High로
분류했다.

## 보안 관련 확인
- `POST .../applications`는 `@PreAuthorize("hasRole('STUDENT')")`로 제한되고, 대표
  신청자는 요청 바디가 아니라 `@AuthenticationPrincipal`에서 추출한 `principal.userId()`를
  쓴다(`SchoolCampController.java:65-72`) — 다른 학생 명의로 신청을 대신 넣는 IDOR 경로는
  없다.
- `teacherUserId`/`additionalMembers[].studentUserId`는 임의의 유저 ID를 그대로 조회하지만,
  이는 "다른 사람을 팀에 초대/지정"하는 기능의 정상 동작이며 `outing`의 `teacherUserId`
  지정 방식과 동일한 패턴이라 별도 인가 문제로 보지 않는다.
- SQL/JPQL 전부 파라미터 바인딩(`:id`, `:candidateIds` 등)을 사용해 인젝션 여지가 없다.
