# #84 스쿨캠핑 유령 점유(ghost claim) 회수 — 코드 리뷰 결과

리뷰 대상: `git diff dev...feat/#84-schoolcamp-ghost-claim-recovery`
(`SchoolCampController`(수정), `SchoolCampSessionRepository`(`reclaimIfExpired` 추가),
`SchoolCampService`(`getCalendar`/`toCalendarResponse`에 유예시간 반영),
`SchoolCampSessionClaimService`(`claim`에 재점유 폴백 추가, `GRACE_PERIOD` 상수 추가),
관련 테스트 4종(`SchoolCampControllerTest`, `SchoolCampServiceTest`,
`SchoolCampSessionClaimServiceIntegrationTest`, 신규 `SchoolCampSessionClaimServiceTest`))

리뷰어는 구현 과정에 관여하지 않은 격리된 세션에서, 기획서
(`84-schoolcamp-ghost-claim-recovery.md`)만 참고해 진행했다
([code-review-isolation.md](../../rules/code-review-isolation.md) 준수). 정적 코드 대조 외에
`./gradlew checkstyleMain checkstyleTest`와 `./gradlew test --tests "*SchoolCamp*"`를 실제로
실행해 스타일/테스트 통과 여부를 확인했고(모두 성공, 새로 추가된 동시성 통합 테스트 포함),
`docs/skills/code-review`(medium effort) 서브에이전트를 별도로 병행 실행해 교차 검증했다 —
그 서브에이전트가 독립적으로 발견한 내용은 아래 1번/3번 항목에 반영했다.

기획서의 "설계 변경 경위" 절이 밝힌 대로, 이 이슈는 마스터 기획서가 제안한 스케줄러 방식을
보스 리뷰에서 명시적으로 폐기하고 "claim 시도 시점 즉시 회수" 방식으로 재설계한 결과물이다.
"왜 스케줄러가 없는가"는 이미 근거를 남기고 확정한 설계 결정이라 이 리뷰에서 다시 문제
삼지 않았다.

## 발견 사항

### 1. 🟠 High — 재점유가 "살아있지만 느린" 원래 요청과 경합하면 세션 하나에 활성 신청이 2건 남을 수 있음 → **부분 반영 완료**

해결 방안 1(원자적 `UPDATE ... NOT EXISTS`)과 2(`release` CAS 가드)를 둘 다 적용했다 —
`reclaimIfExpired`가 "유예시간 지남"과 "활성 신청 없음"을 하나의 원자적 쿼리로 평가하고,
`release(sessionId, expectedTakenAt)`가 claim 성공 시 받은 시각과 일치할 때만 실행된다.
해결 방안 2가 스스로 명시한 대로 "A가 결국 성공해 이중 저장되는" 극히 드문 경로(#68
핵심 로직에 새 분기가 필요)는 해결 방안 3(문서화)을 택해 코드 변경 없이 수용했다 —
기획서 "동시성 분석" 절에 이 정확한 변형을 명시적으로 추가했다.

**리뷰와 별개로 구현 중 추가로 발견한 버그**: `taken_at`이 `DATETIME`(소수점 이하 초
없음) 컬럼이라 저장 시 나노초가 잘려나가는데, CAS 비교(`taken_at = :expectedTakenAt`)는
정밀도가 정확히 일치해야 한다. `applyToCamp`가 `LocalDateTime.now(KST)`를 초 단위로
미리 자르지 않으면 `release`가 **정상적인 경우에도 항상** 조용히 실패해(claim 이후
검증 실패 시 세션이 영원히 잠김) 이 이슈가 고치려던 문제보다 더 나쁜 회귀가 될
뻔했다 — `SchoolCampController.applyToCamp`에서 `truncatedTo(ChronoUnit.SECONDS)`로
수정했고, 실제로 로컬 테스트 3건이 이 문제로 실패하는 것을 확인한 뒤 고쳤다.

**문제**: `SchoolCampSessionClaimService.reclaimIfGhost`(`SchoolCampSessionClaimService.java:105-113`)의
"활성 신청 없음" 확인은 `SchoolCampApplicationRepository.findBySessionIdAndCancelledAtIsNull`로
**커밋된** 신청만 본다. 그런데 `SchoolCampService.applyToCamp`
(`SchoolCampService.java:231-253`)의 실제 흐름은 다음과 같다.

1. `sessionClaimService.claim(sessionId, now)`(REQUIRES_NEW, 즉시 커밋)로 세션을 점유한다.
2. **커밋되지 않은 채로** 같은 `applyToCamp`의 트랜잭션 안에서 `completeApplication`을 호출해
   선생님 검증 → 팀원 조회 → 이번 달 중복 검사 → `applicationRepository.save(application)`
   순서로 처리한 뒤에야(문서화된 대로 "DB 왕복 12~20회 안팎") `applyToCamp`가 커밋된다.

즉 "점유 성공 시각"과 "신청 행이 실제로 DB에 보이는 시각" 사이에 항상 시차가 있고, 정상
상황에서는 그 시차가 ms~수십 ms 수준이라 문제가 안 된다는 게 기획서의 전제다. 하지만 학생
A의 `applyToCamp` 트랜잭션이(GC 정지, 커넥션 풀 대기, 유난히 느린 쿼리 등으로) 우연히
`GRACE_PERIOD`(2분)보다 오래 걸리면:

1. `t0`: A가 fast path로 세션 S를 점유(`taken_at = t0`). A는 크래시하지 않았고 여전히 살아서
   처리 중이다 — 신청 행은 아직 커밋 전.
2. `t0 + 2분 1초`: 학생 B가 같은 세션에 신청. fast path 실패 → `reclaimIfGhost` 호출 →
   `findBySessionIdAndCancelledAtIsNull`은 A의 신청이 아직 커밋되지 않았으므로
   `Optional.empty()`를 반환 → `hasActiveApplication = false` → `reclaimIfExpired` 실행,
   `taken_at(t0) < threshold(t0+1초)` 조건을 만족해 재점유 성공. B는 정상적으로
   `completeApplication`을 마치고 커밋한다 — 이 시점에 세션 S에는 B의 활성 신청 1건이 존재.
3. 그 뒤 A의 처리가 마침내 끝난다.
   - **A가 결국 성공하면**: A는 세션 S가 이미 B에게 재점유된 것을 전혀 모른 채
     `applicationRepository.save(application)`을 그대로 커밋한다.
     `school_camp_application` 테이블에는 `session_id`에 대한 유니크 제약이 없으므로
     (`db/migration/V12__add_schoolcamp_application.sql:1-16` — FK와 일반 인덱스만 있고
     유니크 제약 없음) 이 저장은 아무 예외 없이 성공한다. 결과: 세션 S에 A/B **두 개의
     활성 신청**이 동시에 존재 — `SchoolCampApplicationRepository.findBySessionIdAndCancelledAtIsNull`
     의 자체 Javadoc(`SchoolCampApplicationRepository.java:19`)이 명시한 "한 세션에 성사되는
     신청은 정확히 1건뿐"이라는 불변식이 조용히 깨진다.
   - **A가 결국 실패하면**: `applyToCamp`의 `catch` 블록이 `releaseQuietly(sessionId, e)`
     (`SchoolCampService.java:261-268`)를 호출하고, 이는 곧
     `sessionClaimService.release(sessionId)` → `SchoolCampSessionRepository.release`
     (`SchoolCampSessionRepository.java:70-72`, `UPDATE ... SET taken_at = null WHERE id = :id`,
     **조건 없이 무조건 실행**)로 이어진다. 이 호출은 B가 방금 정상적으로 확보한
     `taken_at`을 아무 확인 없이 `null`로 되돌린다. 그 직후 세 번째 학생 C가 fast path
     (`taken_at IS NULL`)로 같은 세션을 또 점유할 수 있어, 결국 B와 C **두 개의 활성 신청**이
     같은 세션에 남을 수 있다.

두 경로 모두 이 PR이 지키려는 핵심 불변식("한 세션 = 활성 신청 0건 또는 1건")을 깨뜨리고,
이를 막는 DB 제약이 전혀 없어 조용히(예외 없이) 발생한다. `#84` 이전에는 이런 경로 자체가
없었다 — 점유된 세션은 그 점유자가 명시적으로 `release`를 호출하기 전까지 아무도 가로챌 수
없었으므로, "아직 살아있는 점유자"가 도중에 밀려나는 상황이 구조적으로 불가능했다. `#84`가
"시간 경과"만으로 재점유를 허용하면서, 크래시한 유령과 단지 느린 살아있는 요청을 구분하지
못하는 이 경로가 새로 생겼다.

발생 조건(A의 처리가 2분 이상 걸림)은 기획서 스스로도 "정상 처리 시간 대비 수천 배 마진"
이라 부를 만큼 드물다 — 다만 이 클래스 자신의 Javadoc(`SchoolCampSessionClaimService.java:16-26`)이
"재학생 300명 규모에서 한 세션에 100명 이상이 동시에 몰릴 수 있고, 그 순간 커넥션 풀(기본값
10)이 아예 이 서비스만이 아니라 서버 전체와 공유된다"고 명시한 바로 그 고경합 상황이야말로
A의 12~20회 DB 왕복이 예외적으로 길어질 수 있는 조건과 정확히 겹친다. 발생 확률이 낮다는
점 자체는 기획서의 논지와 같지만, 이 특정 변형(원 요청이 죽지 않고 살아있는 채로 재점유당함)은
기획서의 "동시성 분석" 절이 명시적으로 다룬 리스크(그 사이 "원래 요청이 뒤늦게 살아나
신청을 저장"하는, 즉 일단 죽었다가 되살아나는 경우)와는 다른 경로다 — 원 요청이 애초에 죽은
적이 없다.

**해결 방안**:
1. `reclaimIfExpired`에 "활성 신청 없음" 조건까지 포함한 단일 원자적 `UPDATE`로 합친다 —
   예: `update SchoolCampSession s set s.takenAt = :now where s.id = :id and s.takenAt <
   :threshold and not exists (select 1 from SchoolCampApplication a where a.session.id = s.id
   and a.cancelledAt is null)`. 이러면 "확인 SELECT"와 "재점유 UPDATE" 사이의 갭 자체가
   사라져 이 문제의 근본 원인(비원자성)이 없어지고, 아래 3번 항목이 지적하는 "패자 claim마다
   추가 SELECT 왕복이 붙는" 문제도 동시에 해결된다. 단점: 벌크 `UPDATE`에 `NOT EXISTS`
   서브쿼리를 포함하는 형태라 하이버네이트 JPQL이 이를 그대로 지원하는지 확인이 필요하고
   (미지원 시 네이티브 쿼리로 내려가야 함), 기존 `claim`/`release` 두 문장의 단순한 패턴보다
   복잡도가 올라간다.
2. `release`를 조건부(compare-and-swap)로 바꾼다 — `release(Long id, LocalDateTime
   expectedTakenAt)`처럼 자신이 claim에 성공했을 때 받은 시각을 그대로 넘겨받아
   `UPDATE ... WHERE id = :id AND taken_at = :expectedTakenAt`으로 제한한다.
   `releaseQuietly`/`cancelApplication` 등 모든 호출부가 자신이 기억하는 값을 넘기면 된다.
   기존 `claim`이 이미 쓰고 있는 "WHERE 절 기반 조건부 원자적 UPDATE" 패턴을 그대로 확장하는
   것이라 컨벤션과 잘 맞는다. 단점: "release 경로에서 남의 점유를 잘못 되돌리는" 사고만
   막을 뿐, "A가 결국 성공해 이중 저장되는" 경로는 별도로 막아야 한다(1번 또는 3번과 병행
   필요).
3. 발생 확률이 극히 낮다고 보고(claim 이후 정상 처리는 ms~수십 ms인데 `GRACE_PERIOD`는
   2분) 지금은 코드 변경 없이 수용하되, 기획서 "동시성 분석" 절에 이 구체적 변형(원 요청이
   죽지 않고 살아있는 채로 재점유당하는 경우)을 명시적으로 추가해 리스크를 문서화한다.
   비용은 0이지만, 실제로 트리거되면 DB 제약 없이 조용히 데이터 정합성이 깨지는 채로
   남는다는 부담은 그대로 남는다.

### 2. 🟡 Medium — "유예시간 이내에는 재점유되지 않는다"는 기획서 명시 케이스가 실제 DB로 검증되지 않음 → **반영 완료**

해결 방안 1을 채택했다 — `SchoolCampSessionClaimServiceIntegrationTest.ReclaimGhost`에
`doesNotReclaimWithinGracePeriod`를 추가해, 유예시간 이내로 점유된 세션이 실제 DB에서
재점유되지 않고 `taken_at`이 그대로 유지되는지 확인한다. 활성 신청이 있으면 유예시간이
지나도 재점유되지 않는지는 `User`/`Gbsw` 전체 그래프를 구성해야 해 이번 통합 테스트에는
넣지 않았다 — 대신 QA(10단계)에서 실 HTTP 흐름으로 확인한다.

**문제**: 기획서 "테스트" 절은 `SchoolCampSessionClaimServiceTest`가 검증해야 할 첫 번째
케이스로 "유예시간 이전에 점유된 세션은 재점유되지 않는지(정상 예약 보호)"를 명시한다.
그런데 이 경계 조건이 실제 SQL(`SchoolCampSessionRepository.reclaimIfExpired`,
`s.takenAt < :threshold`)을 대상으로 검증되는 곳이 없다.

- 신규 단위 테스트 `SchoolCampSessionClaimServiceTest`(`src/test/java/.../SchoolCampSessionClaimServiceTest.java:678-690`)의
  `returnsFalseWhenReclaimLosesRace`는 `sessionRepository.reclaimIfExpired(...)`가 `0`을
  반환하도록 **모킹**만 할 뿐, 그 이유가 "유예시간이 안 지나서"인지 "이미 남이 가져가서"인지
  구분하지 않는다(둘 다 서비스 코드 입장에서는 동일하게 `false`를 반환하므로 서비스 레벨
  단위 테스트로는 구분할 수 없다 — 이 자체는 정상).
- `SchoolCampSessionClaimServiceIntegrationTest`에 새로 추가된 `ReclaimGhost` nested
  클래스(`src/test/java/.../SchoolCampSessionClaimServiceIntegrationTest.java:536-580`)에는
  `onlyOneReclaimSucceedsUnderConcurrency` 테스트 하나뿐이고, 이마저 이미 유예시간이
  1분 지난 세션만 다룬다.

즉 "방금(유예시간 이내) 점유된 세션에 `claim`을 호출하면 실제 DB에서 재점유가 거부되는지"를
실제로 실행해 확인하는 테스트가 하나도 없다. 이 리뷰와 병행 실행한 code-review
서브에이전트도 독립적으로 같은 갭을 지적했다 — `reclaimIfExpired`의 `s.takenAt < :threshold`를
실수로 `<=`로 바꾸거나 조건을 아예 빼도 현재 테스트 스위트는 전부 그대로 통과한다. 이 항목은
1번 항목이 지적한 경합의 "시간 경계"를 지키는 최후의 보루이기도 해서, 회귀를 못 잡는 상태로
남겨두면 위험이 더 크다.

**해결 방안**:
1. `SchoolCampSessionClaimServiceIntegrationTest.ReclaimGhost`에 "유예시간 이내에 점유된
   세션은 재점유에 실패한다" 테스트를 추가한다 — `taken_at`을 `GRACE_PERIOD`보다 짧게(예:
   30초 전)로 설정한 뒤 `claimService.claim(...)`을 호출해 `false`가 반환되고, 세션을
   재조회해 `taken_at`이 그대로인지 확인한다. 기존 `ReclaimGhost` 클래스에 케이스 하나만
   추가하면 되어 비용이 낮고, 실제 SQL의 방향성을 직접 검증한다.
2. 낮은 우선순위로 보고 지금은 넘어간다 — `reclaimIfExpired`는 이미 #68에서 검증된 `claim`
   쿼리 패턴(부등호만 추가)을 그대로 재사용한 형태라 실수 가능성이 낮고, 코드 리뷰 시 SQL을
   육안으로 바로 확인할 수 있다. 비용은 0이지만, 향후 리팩터링 시 회귀를 자동으로 잡아내지
   못한다.

### 3. 🟡 Medium — `reclaimIfGhost`가 유예시간 경과 여부를 먼저 보지 않고, 실패하는 모든 claim마다 DB SELECT를 먼저 실행함 → **반영 완료**

1번 항목과 같은 수정(해결 방안 2)으로 같이 해결됐다 — `reclaimIfGhost`는 이제 별도
`SELECT` 없이 `reclaimIfExpired` 하나만 호출한다. `SchoolCampSessionClaimService`도
`SchoolCampApplicationRepository` 의존성이 사라져 기존처럼 `SchoolCampSessionRepository`
하나에만 의존한다.

**문제**: `SchoolCampSessionClaimService.reclaimIfGhost`(`SchoolCampSessionClaimService.java:105-113`)는
"활성 신청 존재 여부"(DB SELECT)를 먼저 확인하고, 그다음에야 유예시간 조건을 재점유
`UPDATE`의 `WHERE` 절 안에서 평가한다. 즉 fast path claim이 실패하는 모든 요청 —
`taken_at`이 **방금**(예: 1ms 전) 채워졌든 이미 몇 달 전이든 상관없이 — 가 예외 없이 먼저
`findBySessionIdAndCancelledAtIsNull` SELECT를 실행한다. 유예시간 경과 여부는 애플리케이션
코드에서 메모리 비교만으로도(추가 DB 왕복 없이) 먼저 걸러낼 수 있는데, 지금은 그렇게 하지
않는다.

병행 실행한 code-review 서브에이전트가 이 부분을 정확히 지적했다 — 인기 날짜에 100명이
동시에 신청 버튼을 눌러 1명만 fast path로 성공하고 나머지 99명이 실패하는 시나리오(이
클래스 자신의 Javadoc이 "재학생 300명 규모에서 실제로 벌어질 수 있다"고 명시한 바로 그
상황)에서, `#84` 이전에는 이 99명 전원이 `sessionRepository.claim` 실패 시점에 바로
`false`를 반환받아 끝났지만, `#84` 이후에는 99명 전원이 추가로 `SchoolCampApplication`
테이블에 대한 SELECT를 하나씩 더 실행한다. 이는 `SchoolCampSessionClaimService` 클래스
Javadoc(`SchoolCampSessionClaimService.java:16-26`)이 "락 보유 시간·DB 왕복을 최소화해
HikariCP 커넥션 풀 경합을 줄이려고 `REQUIRES_NEW`로 분리했다"고 밝힌 바로 그 목적과 반대
방향으로, 가장 경합이 심한 순간에 쿼리 왕복 수를 오히려 늘린다.

**해결 방안**:
1. `reclaimIfGhost`에서 DB를 조회하기 전에, 호출부가 이미 갖고 있거나 가볍게 조회 가능한
   `taken_at` 값으로 유예시간 경과 여부를 먼저(메모리 비교로) 판단해, 아직 유예시간 이내면
   `findBySessionIdAndCancelledAtIsNull` SELECT 자체를 건너뛰고 즉시 `false`를 반환한다.
   다만 현재 `claim(sessionId, now)`은 `taken_at` 값을 별도로 조회하지 않으므로, 이를 위해서는
   세션을 한 번 조회하거나(SELECT 1회는 여전히 필요) `sessionRepository.claim`의 실패
   원인을 구분할 방법이 필요해 구현이 단순하지 않다. 장점: 유예시간 이내인 압도적 다수의
   "방금 점유당한" 실패 케이스에서 SELECT를 아예 생략할 수 있다. 단점: 그 판단을 위해
   세션을 다시 읽어야 한다면 순서만 바뀔 뿐 왕복 횟수 자체는 줄지 않을 수 있다 — 1번
   항목의 해결 방안 1(단일 원자적 `UPDATE ... WHERE ... AND NOT EXISTS`)과 함께 적용해야
   진짜 효과가 난다.
2. 1번 항목의 해결 방안 1(활성 신청 없음 + 유예시간 경과를 하나의 원자적 `UPDATE`로 합치기)을
   채택한다 — 그러면 이 항목이 지적하는 "패자 claim마다 추가 SELECT"라는 성능 문제와, 1번
   항목이 지적하는 원자성 문제가 동시에 해결된다(추가 SELECT 자체가 사라지고 DB 왕복이
   `claim` 1회 실패 + `reclaimIfExpired` 1회 시도로, `#84` 이전과 동일한 왕복 수로
   돌아온다). 가장 근본적인 해결책이지만 쿼리 복잡도가 올라가는 비용은 1번 항목과 동일하게
   따라온다.
3. 재학생 300명 규모·세션당 최대 100명 동시 경합이라는 이 프로젝트의 실측 상한을 고려하면
   "실패한 claim마다 SELECT 1회 추가"가 실무적으로 감당 가능한 수준이라고 보고 지금은 그대로
   둔다 — 커넥션 풀 경합이 실제 장애로 관측되면 그때 1번/2번 방안을 적용한다. 비용은 0이지만,
   이 클래스가 스스로 문서화한 설계 목적(커넥션 풀 경합 최소화)과 어긋난 채로 남는다는 점은
   문서에 남겨야 나중에 "왜 이런 구조냐"는 질문이 반복되지 않는다.

## 확인했지만 문제 없었던 항목 (Critical 없음)

- **재점유 전 활성 신청 확인 순서**: `SchoolCampSessionClaimService.reclaimIfGhost`
  (`SchoolCampSessionClaimService.java:105-113`)는 `applicationRepository
  .findBySessionIdAndCancelledAtIsNull`을 먼저 호출해 `hasActiveApplication`이 `true`면
  `sessionRepository.reclaimIfExpired`를 아예 호출하지 않고 즉시 `false`를 반환한다 — 이
  순서를 건너뛰는 코드 경로는 diff 전체에서 찾지 못했다(`claim` 안에서 `reclaimIfGhost`를
  호출하는 지점은 한 곳뿐).
- **`reclaimIfExpired`의 원자성**: `SchoolCampSessionRepository.reclaimIfExpired`
  (`SchoolCampSessionRepository.java:90-96`)는 `update ... where s.id = :id and s.takenAt <
  :threshold` 형태의 단일 `@Modifying @Query`다 — 기존 `claim`과 동일하게 SELECT 후 UPDATE가
  아니라 조건이 붙은 UPDATE 한 문장이라, `claim`의 Javadoc이 근거로 든 InnoDB current-read
  원자성을 그대로 재사용한다. 두 요청이 동시에 같은 세션을 재점유하려 해도 정확히 하나만
  영향받은 행 1을 받는다는 것은 새로 추가된 통합 테스트
  `SchoolCampSessionClaimServiceIntegrationTest.ReclaimGhost
  .onlyOneReclaimSucceedsUnderConcurrency`(20개 스레드 동시 호출)가 실제 DB로 검증하고,
  로컬에서 재실행해 통과를 확인했다.
- **"확인 → UPDATE" 갭의 동시성 분석**: 기획서가 "동시성 분석" 절에서 수용한 잔여 리스크
  (죽었던 원 요청이 되살아나 신청을 저장하는 경우)에 대한 논리는 재확인해도 유효하다 — 다만
  이 리뷰는 그와 다른 변형(원 요청이 죽지 않고 살아있는 채로 밀려나는 경우)을 새로 찾아
  1번 항목으로 별도 지적했다.
- **기존 `claim` fast path 무변경**: `SchoolCampSessionRepository.claim`
  (`SchoolCampSessionRepository.java:55-58`)의 쿼리 문자열과 시그니처는 diff에서 전혀
  수정되지 않았다 — `reclaimIfExpired`가 파일 끝에 순수 추가만 됐다. `SchoolCampSessionClaimService
  .claim`도 기존 `sessionRepository.claim(sessionId, takenAt) == 1` 조건을 그대로 유지한 채
  실패 시에만 `reclaimIfGhost`로 분기해, 대부분의 정상 호출(fast path 성공)은 동작 변화가
  없다.
- **캘린더 표시의 분기 우선순위**: `SchoolCampService.toCalendarResponse`
  (`SchoolCampService.java:183-212`)는 `application != null`이면(즉 실제 활성 신청이
  있으면) `taken_at`이 아무리 오래됐어도 유예시간 검사를 거치지 않고 무조건 이름을 채운
  `CLOSED`를 반환한다(191번째 줄의 `if (application == null)` 분기 안에서만 유예시간 비교가
  일어남) — 오래전에 정상 성사된 예약이 캘린더에서 실수로 `OPEN`으로 보일 경로는 없다.
- **`GRACE_PERIOD` 단일 정의**: `grep -rn GRACE_PERIOD src`로 확인한 결과, 실제 정의는
  `SchoolCampSessionClaimService.java:59` 한 곳뿐이고 `SchoolCampService`/테스트 코드 전부
  `SchoolCampSessionClaimService.GRACE_PERIOD`를 참조만 한다 — 중복 정의로 값이 어긋날
  여지가 없다.
- **`GRACE_PERIOD = 2분` 값의 타당성**: 기획서가 밝힌 근거(claim 이후 검증은 정상적으로
  ms~수십 ms 수준이라 2분이면 수천 배 마진)를 그대로 확인했다 — 이 값 자체는 이번 리뷰에서
  문제 삼지 않았다(보스와 이미 논의를 거친 확정 값). 다만 1번/3번 항목에서 지적한 대로, 이
  마진이 "일반적인 경우"에 대한 것이지 "커넥션 풀 경합이 심한 극단적인 경우"까지 보장하지는
  않는다는 점은 별도로 짚었다.
- **트랜잭션 경계**: `claim`은 여전히 `@Transactional(propagation = REQUIRES_NEW)` 하나로
  fast path와 `reclaimIfGhost`(활성 신청 확인 SELECT + 재점유 UPDATE) 전체를 감싼다 —
  `reclaimIfGhost`는 `private`이고 별도 `@Transactional`이 붙어 있지 않아, 같은 클래스
  내부 호출이 AOP 프록시를 우회해 전파 옵션이 조용히 무시되는(이 클래스 자신의 Javadoc이
  경고하는) 함정에 해당하지 않는다. `SchoolCampService.getCalendar`도 여전히
  `@Transactional(readOnly = true)`를 유지한다.
- **null 안전성**: `toCalendarResponse`/`reclaimIfGhost`에서 `now`는 항상 컨트롤러
  (`LocalDateTime.now(KST)`)나 테스트의 고정값에서 전달되며, `now.minus(GRACE_PERIOD)`가
  `null`을 받을 수 있는 경로는 찾지 못했다.
- **checkstyle/테스트 실행**: `./gradlew checkstyleMain checkstyleTest`가 경고 없이
  통과했고, `./gradlew test --tests "*SchoolCamp*"`도 새로 추가된 20-스레드 동시성
  통합 테스트를 포함해 전부 통과했다.
- **Javadoc/주석 스타일**: `SchoolCampSessionRepository`/`SchoolCampSessionClaimService`/
  `SchoolCampService`에 추가된 Javadoc이 이슈 번호(`#84`)를 명시하고, 근거 문서 경로
  (`docs/domain/schoolcamp/84-schoolcamp-ghost-claim-recovery.md`)를 `{@code}`로 인용하는
  방식이 기존 `#68`/`#70`/`#83` 주석 스타일과 일관된다.

## 반영 시점

코드 리뷰 직후(9단계) 작성. QA(10단계) 시작 전 이 문서가 먼저 존재해야 한다는
[code-review-template.md](../../rules/code-review-template.md) 규칙을 따랐다.
