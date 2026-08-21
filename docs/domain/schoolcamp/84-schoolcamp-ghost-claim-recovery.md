# #84 스쿨캠핑 유령 점유(ghost claim) 회수 — 기획서

관련 이슈: [#84 스쿨캠핑 유령 점유(ghost claim) 회수](https://github.com/GBSW-ReMake/GONE-server-V1/issues/84)
마스터 기획서: [1_schoolcamp-domain.md](./1_schoolcamp-domain.md)의 "후속 이슈 후보 — 유령
점유(ghost claim) 회수 스케줄러" 절이 최초 방향을 남겨뒀으나, 이 문서에서 **스케줄러가
아닌 방식으로 재설계**했다(아래 "설계 변경 경위" 참고).
선행 이슈: [#68](./68-schoolcamp-application.md)(신청 API, 이 잔여 리스크가 드러난 원 이슈,
완료·머지됨)

## 개요/목적
`SchoolCampSessionClaimService.claim`/`release`는 각각 `REQUIRES_NEW`로 즉시 커밋되는
독립 트랜잭션이다(#68). claim 이후 검증(선생님 역할·팀원 존재·월 중복)이 실패하면 호출한
쪽이 `release`를 명시적으로 호출해야 세션이 다시 열리는데, 그 `release` 호출 자체가
실패하면(release 도중 서버가 죽거나 DB 연결이 끊기는 등) 세션은 실제로는 비어있는데
`taken_at`만 채워진 채 영구히 남는다 — "유령 점유".

**언제 생기는가(정확한 조건)**: 아래 두 사건이 정확히 같은 순간에 겹쳐야 한다.
1. claim 이후 검증이 실패한다(팀원 정보 오류, 월 중복 등 — 흔한 사용자 실수, 자주 있음).
2. 그래서 `release()`를 호출하는데, 그 호출 자체가 실행되지 못하고 죽는다(서버 프로세스가
   그 타이밍에 죽거나 DB 연결이 그 순간 끊김 — 드묾).

이 프로젝트는 `server.shutdown: graceful`을 설정하지 않아 배포할 때마다 진행 중인 요청을
기다리지 않고 즉시 종료한다(Spring Boot 기본값). 배포가 잦은 이 저장소 특성상 2번 사건은
마스터 기획서가 가정한 "극히 드문 하드웨어 장애" 수준보다는 현실적으로 발생 가능하다 — 다만
1번과 정확히 같은 순간에 겹쳐야 하므로 실제 유령 발생 빈도 자체는 여전히 낮다.

## 설계 변경 경위
마스터 기획서는 `outing`의 `OutingMissedScheduler`(#42)와 동일한 패턴(주기적으로 스캔해
회수하는 백그라운드 스케줄러)을 제안했다. 이 문서의 초안도 그 방향으로 시작했으나, 보스
리뷰 과정에서 아래 이유로 스케줄러 없이 **claim 시도 시점에 즉시 회수하는 방식**으로
전면 재설계했다.

- **회수 지연**: 스케줄러 방식은 `GRACE_PERIOD` + 스케줄 주기만큼(예: 2분 + 5분 = 최대
  7분) 그 날짜가 "아무도 안 쓰는데 마감으로 보이는" 상태로 남는다. 인기 날짜를 노리던
  다른 학생들이 그 시간 동안 신청 자체를 못 한다 — 스케줄 주기 지연은 구조적으로 없앨 수
  있는 낭비다.
- **오버엔지니어링**: 스케줄러 방식은 새 컴포넌트(`SchoolCampGhostClaimScheduler`) +
  새 조회 메서드 + 폴링 인프라가 필요했다. claim 시도 시점에 즉시 처리하면 이 전부가
  필요 없다 — 새 컴포넌트 0개, 기존 두 메서드(`claim`, 캘린더 조회)만 수정한다.
- **캘린더 조회와의 정합성**: 스케줄러 방식이든 즉시 처리 방식이든, 캘린더 조회
  (`GET /api/v1/school-camps`)가 "유예시간이 지난 유령"을 여전히 CLOSED로 보여주면
  사용자는 애초에 재신청을 시도하지 않는다 — 회수 메커니즘이 아무리 빨라도 실전에서
  안 쓰인다. 그래서 캘린더 조회도 같은 유예시간 기준을 반영해야 하는데, 이건 두 방식
  모두에 공통으로 필요한 수정이라 스케줄러를 유지할 이유가 되지 못한다.

## 핵심 위험 — 유예시간만으로는 안전하지 않음(설계 확정)
claim 쿼리에 "유예시간이 지나면 재점유 허용"만 넣으면, **정상적으로 몇 주/몇 달 전에
확정된 예약도 함께 뺏길 수 있다** — `taken_at`은 예약이 성사된 순간 한 번 찍히고 그
캠핑 날짜가 지날 때까지 바뀌지 않으므로, 시간 경과만으로는 "정말 비어있는 유령"과
"오래전에 정상 성사된 진짜 예약"을 구분할 수 없다. 그래서 재점유를 시도하기 전에 반드시
`SchoolCampApplicationRepository.findBySessionIdAndCancelledAtIsNull`로 **활성 신청이
정말 없는지**를 먼저 확인한다(마스터 기획서의 원래 조건을 그대로 유지, 적용 위치만
스케줄러에서 claim으로 옮김).

## 구현

### 1. `SchoolCampSessionRepository`에 재점유 전용 쿼리 추가(기존 `claim`은 손대지 않음)
**(코드 리뷰 반영, 갱신)** "유예시간 지남"과 "활성 신청 없음"을 별도 `SELECT` + `UPDATE`
두 단계가 아니라 `NOT EXISTS` 서브쿼리를 포함한 하나의 원자적 `UPDATE`로 합쳤다(아래
"동시성 분석" 절 참고). 벌크 `UPDATE`의 상관 서브쿼리를 JPQL이 그대로 지원하는지
불확실해 네이티브 쿼리로 작성한다.
```java
/**
 * "유령 점유" 후보 세션을 재점유합니다. "유예시간 지남"과 "활성 신청 없음"을 하나의
 * 원자적 UPDATE로 같이 평가한다 — claim과 동일한 InnoDB current-read 원자성을 갖는다.
 */
@Modifying
@Query(value = "update school_camp_session s "
    + "set s.taken_at = :now "
    + "where s.id = :id and s.taken_at < :threshold "
    + "and not exists ("
    + "  select 1 from school_camp_application a "
    + "  where a.session_id = s.id and a.cancelled_at is null"
    + ")",
    nativeQuery = true)
int reclaimIfExpired(
    @Param("id") Long id,
    @Param("threshold") LocalDateTime threshold,
    @Param("now") LocalDateTime now);
```
기존 `claim`(`taken_at IS NULL`)은 그대로 둔다 — 이미 검증된 동시성 핵심 쿼리를 건드리지
않아 리스크를 최소화한다.

**`release`도 조건부(compare-and-swap)로 변경(코드 리뷰 반영, 추가)**:
```java
@Modifying
@Query("update SchoolCampSession s set s.takenAt = null "
    + "where s.id = :id and s.takenAt = :expectedTakenAt")
int release(@Param("id") Long id, @Param("expectedTakenAt") LocalDateTime expectedTakenAt);
```

### 2. `SchoolCampSessionClaimService.claim`에 재점유 폴백 추가
```java
private static final Duration GRACE_PERIOD = Duration.ofMinutes(2); // public static final

private final SchoolCampSessionRepository sessionRepository; // 유일한 의존성, 변경 없음

@Transactional(propagation = Propagation.REQUIRES_NEW)
public boolean claim(Long sessionId, LocalDateTime now) {
  if (sessionRepository.claim(sessionId, now) == 1) {
    return true; // 기존 경로, 동작 변화 없음(대부분의 호출이 여기서 끝남)
  }
  return reclaimIfGhost(sessionId, now);
}

private boolean reclaimIfGhost(Long sessionId, LocalDateTime now) {
  LocalDateTime threshold = now.minus(GRACE_PERIOD);
  return sessionRepository.reclaimIfExpired(sessionId, threshold, now) == 1;
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void release(Long sessionId, LocalDateTime expectedTakenAt) {
  sessionRepository.release(sessionId, expectedTakenAt);
}
```
**(코드 리뷰 반영)** 초안은 이 클래스가 `SchoolCampApplicationRepository`를 새로
주입받아 "활성 신청 없음"을 먼저 확인했으나, 그 확인 로직이 리포지토리의 원자적
`UPDATE` 안으로 옮겨가면서 이 의존성 자체가 필요 없어졌다 — 클래스가 기존처럼
`SchoolCampSessionRepository` 하나에만 의존하는 형태로 되돌아갔다.
`GRACE_PERIOD = 2분`: claim 이후 검증은 정상적으로 수 ms~수십 ms 수준이므로, 2분은 이미
정상 처리 시간 대비 수천 배 마진이다. 스케줄러 방식과 달리 이 값을 줄여도 폴링 지연이
안 붙으므로, 값 자체가 곧 "유령이 실제로 자리를 막는 최대 시간"이 된다.

**동시성 분석(코드 리뷰 반영, 갱신)**: 초안은 "활성 신청 없음"을 별도 `SELECT`로 먼저
확인한 뒤 `reclaimIfExpired` `UPDATE`를 실행했는데, 코드 리뷰(High 1번)가 이 두 단계
사이의 갭을 정확히 지적했다 — 원래 점유자(A)가 **죽지 않고 살아서** `GRACE_PERIOD`보다
오래 처리 중일 때, 그 갭 사이에 다른 요청(B)이 재점유해 정상적으로 신청을 완료할 수
있다. 그 뒤 A가 뒤늦게 끝나면: (a) A가 성공하면 같은 세션에 A/B 활성 신청이 2건 남거나,
(b) A가 실패해 무조건적인 `release`를 호출하면 B의 정상적인 새 점유를 되돌린다. 이는
"일단 죽었다가 되살아나는" #68/마스터 기획서의 기존 잔여 리스크와는 다른 경로다(A가
애초에 죽은 적이 없다).

**반영한 수정**:
1. **"활성 신청 없음" + "유예시간 지남"을 `reclaimIfExpired` 하나의 원자적 `UPDATE`로
   합쳤다**(`NOT EXISTS` 서브쿼리, 아래 코드 참고) — 확인과 재점유 사이의 갭 자체가
   사라져 B 쪽에서 발생하던 레이스는 닫힌다.
2. **`release`를 조건부(compare-and-swap)로 바꿨다** — `release(sessionId,
   expectedTakenAt)`처럼 claim 성공 시 받은 시각을 그대로 넘겨야만 실제로 반환된다.
   A의 지연된 `release` 호출이 B의 새 점유를 실수로 되돌리는 (b) 경로를 막는다.

**남은 잔여 리스크(인지, 수용)**: 위 두 수정을 적용해도 (a) — "A가 GRACE_PERIOD보다
오래 걸렸지만 결국 성공해 신청을 커밋"하는 경로는 원자적 `UPDATE`로 막을 수 없다(claim
자체는 이미 오래전에 성공했고, 그 이후의 검증·저장 로직은 재점유 여부를 모른 채 그대로
진행되므로). 이 경우 세션 하나에 두 활성 신청이 남을 수 있다. `school_camp_application`
테이블에 `session_id` 유니크 제약이 없어 이 저장 자체는 예외 없이 성공한다. 다만
발생하려면 "claim 이후 검증이 정상적으로는 ms~수십 ms인데 `GRACE_PERIOD`(2분)를 넘기고도
결국 성공"해야 하므로 여전히 극히 드물다 — 이 경로를 완전히 막으려면 `completeApplication`
저장 직전에 "여전히 내가 이 세션을 점유하고 있는지" 재확인하는 로직이 추가로 필요한데,
이는 #68의 핵심 로직(`applyToCamp`/`completeApplication`)에 새 분기를 넣는 것이라 이번
이슈가 지키려던 "기존 로직 최소 변경" 원칙과 충돌한다. 지금은 코드 변경 없이 문서화만
하고, 실제로 관측되면 별도 이슈로 대응한다.

### 3. `SchoolCampService.getCalendar`/`toCalendarResponse`에 유예시간 반영
```java
@Transactional(readOnly = true)
public List<SchoolCampCalendarResponse> getCalendar(YearMonth month, LocalDateTime now) {
  ...
  return sessions.stream()
      .map(session -> toCalendarResponse(session, applicationsBySessionId.get(session.getId()), now))
      .toList();
}

private SchoolCampCalendarResponse toCalendarResponse(
    SchoolCampSession session, SchoolCampApplication application, LocalDateTime now) {
  if (session.getTakenAt() == null) {
    return OPEN;
  }
  if (application != null) {
    return CLOSED (이름 포함);
  }
  // 점유는 됐는데 활성 신청 없음 — 유예시간이 지났으면 claim()도 재점유를 허용하는
  // 상태이므로 화면도 OPEN으로 맞춘다. 아직 유예시간 안이면 정상 처리 중일 가능성을
  // 배려해 기존처럼 방어적으로 CLOSED 유지.
  if (session.getTakenAt().isBefore(now.minus(SchoolCampSessionClaimService.GRACE_PERIOD))) {
    return OPEN;
  }
  log.warn("점유된 세션에 활성 신청이 없습니다(유령 점유 의심, 유예시간 내): sessionId={}", session.getId());
  return CLOSED (이름 없이, 기존 방어 로직 유지);
}
```
`GRACE_PERIOD`는 `SchoolCampSessionClaimService`의 `public static final` 상수를 그대로
참조한다 — claim이 허용하는 기준과 캘린더가 보여주는 기준이 어긋나지 않도록 값을
한 곳에서만 정의한다(중복 정의로 인한 불일치 방지).

**컨트롤러 변경**: `GET /api/v1/school-camps`가 `LocalDateTime.now(KST)`를 계산해
`getCalendar`에 같이 넘긴다(같은 컨트롤러의 `applyToCamp`/`cancelApplication`과 동일한
기존 패턴). **요청/응답 스키마는 변경 없음** — 내부 서비스 메서드 시그니처만 바뀐다.

## 데이터 모델 변경
없음.

## 영향 받는 기존 코드
- 수정: `SchoolCampSessionRepository`(`reclaimIfExpired` 추가 — 활성 신청 확인까지 포함한
  네이티브 쿼리, 기존 `claim`은 불변, `release`를 조건부(CAS)로 변경), `SchoolCampSessionClaimService`
  (`claim`에 재점유 폴백 추가, `release`가 `expectedTakenAt`을 받도록 변경, `GRACE_PERIOD`
  상수 추가 — 의존성은 기존과 동일하게 `SchoolCampSessionRepository` 하나뿐), `SchoolCampService`
  (`getCalendar`/`toCalendarResponse` 시그니처에 `now` 추가, `releaseQuietly`가 `takenAt`을
  받도록 변경, `cancelApplication`의 `sessionRepository.release` 호출에 `takenAt` 인자 추가),
  `SchoolCampController`(`getCalendar`가 `now`를 계산해 전달)
- 변경 없음: `SchoolCampApplicationRepository`(기존 메서드 재사용, 새 의존 관계 없음),
  API 요청/응답 스키마, 데이터베이스 스키마

## 리스크 및 고려사항
- **API 설계 6원칙**: 요청/응답 스키마가 바뀌지 않아 해당 없음(내부 구현 변경).
- **`claim` 관련 쿼리를 건드리는 리스크**: 기존 `claim` 메서드 자체는 그대로 두고
  새 메서드(`reclaimIfExpired`)만 추가하는 방식으로 최소화했다 — #68이 이미 검증한
  동시성 보장(원자적 `UPDATE ... WHERE`)을 그대로 재사용하는 형태라 새로운 종류의
  동시성 버그를 들이지 않는다.
- **잔여 레이스(코드 리뷰로 재분석, 인지하되 수용)**: 위 "동시성 분석" 절 참고 —
  원자적 쿼리 병합 + `release` CAS 가드로 대부분의 경합을 닫았지만, "claim 이후 처리가
  `GRACE_PERIOD`를 넘기고도 결국 성공"하는 극히 드문 경로는 여전히 남아 있다(#68의
  핵심 로직에 새 분기를 넣지 않기로 한 결정과 트레이드오프).
- **DB 위생**: 캘린더/claim 양쪽 모두 유예시간 기준으로 판단하므로, 재신청 시도가
  단 한 번도 없는 유령 세션의 `taken_at`은 DB에 계속 stale 값으로 남을 수 있다.
  기능적 영향은 없다(claim/캘린더 둘 다 시간 계산으로 우회하므로) — 순수 데이터 정리는
  이번 범위에서 다루지 않는다(필요해지면 훨씬 가벼운 별도 배치로 후속 처리).
- **`#83`(자리나면 알림받기)과의 관계**: 유령 회수는 실제 취소(#70)와 성격이 달라
  (아무도 신청한 적 없는 자리가 풀리는 것) 대기자 알림을 연결하지 않는다 — 마스터 방향
  그대로 유지.
- **새 인프라 비용**: 없음(스케줄러 폐기로 이 항목 자체가 사라짐).

## 테스트
- `SchoolCampSessionClaimServiceTest`(신규, Mockito 단위 테스트): fast path 성공/실패 시
  분기, `reclaimIfExpired` 성공/실패 결과가 그대로 반환되는지, `release`가 받은
  `expectedTakenAt`을 그대로 리포지토리에 전달하는지
- `SchoolCampSessionClaimServiceIntegrationTest`(`@SpringBootTest`, 실 DB, `reclaimIfExpired`
  자체가 원자적 `UPDATE`라 목으로 검증할 수 없는 부분 담당, 코드 리뷰 Medium 2번 반영):
  - 유예시간이 지났고 활성 신청이 없는 세션은 재점유에 성공하는지(기존)
  - **유예시간 이전에 점유된 세션은 재점유되지 않는지**(정상 예약 보호, 코드 리뷰 전까지
    누락돼 있었음 — `s.taken_at < :threshold` 부등호가 뒤집혀도 잡아내지 못하던 갭)
  - 유예시간이 지났지만 활성 신청이 있는 세션은 재점유되지 않는지(`NOT EXISTS` 조건
    실동작 확인)
  - 두 요청이 동시에 같은 유령 세션의 재점유를 시도하면 정확히 하나만 성공하는지(기존,
    20-스레드 동시성 테스트)
- `SchoolCampServiceTest`(기존 `GetCalendar` `@Nested`에 케이스 추가):
  - 유예시간 이전 유령 후보는 여전히 CLOSED로 보이는지(기존 방어 로직 유지 확인)
  - 유예시간이 지난 유령 후보는 OPEN으로 보이는지
  - 활성 신청이 있는 정상 예약은 유예시간과 무관하게 항상 CLOSED로 보이는지

## 완료 조건 (Definition of Done)
- 로컬 빌드/테스트 통과
- CI 통과
- Postman/Notion 반영: 해당 없음(요청/응답 스키마 변경 없음)
