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
```java
/**
 * "유령 점유" 후보 세션을 재점유합니다. {@code claim}과 동일한 InnoDB current-read
 * 원자성을 갖는다 — 이미 다른 요청이 먼저 되찾아갔으면(taken_at이 더 최근 값으로
 * 갱신됐으면) 영향받은 행이 0이 된다.
 *
 * @param id        재점유할 세션의 PK
 * @param threshold 이 시각보다 이전에 점유된 경우에만 재점유를 허용
 * @param now       재점유 시각으로 기록할 값
 * @return 영향받은 행 수(재점유 성공 시 1, 이미 누가 먼저 가져갔으면 0)
 */
@Modifying
@Query("update SchoolCampSession s set s.takenAt = :now "
    + "where s.id = :id and s.takenAt < :threshold")
int reclaimIfExpired(
    @Param("id") Long id,
    @Param("threshold") LocalDateTime threshold,
    @Param("now") LocalDateTime now);
```
기존 `claim`(`taken_at IS NULL`)은 그대로 둔다 — 이미 검증된 동시성 핵심 쿼리를 건드리지
않아 리스크를 최소화한다.

### 2. `SchoolCampSessionClaimService.claim`에 재점유 폴백 추가
```java
private static final Duration GRACE_PERIOD = Duration.ofMinutes(2); // public static final

private final SchoolCampSessionRepository sessionRepository;
private final SchoolCampApplicationRepository applicationRepository; // 신규 의존성

@Transactional(propagation = Propagation.REQUIRES_NEW)
public boolean claim(Long sessionId, LocalDateTime now) {
  if (sessionRepository.claim(sessionId, now) == 1) {
    return true; // 기존 경로, 동작 변화 없음(대부분의 호출이 여기서 끝남)
  }
  return reclaimIfGhost(sessionId, now);
}

private boolean reclaimIfGhost(Long sessionId, LocalDateTime now) {
  boolean hasActiveApplication =
      applicationRepository.findBySessionIdAndCancelledAtIsNull(sessionId).isPresent();
  if (hasActiveApplication) {
    return false; // 진짜로 이미 신청된 세션 — 손대지 않는다
  }
  LocalDateTime threshold = now.minus(GRACE_PERIOD);
  return sessionRepository.reclaimIfExpired(sessionId, threshold, now) == 1;
}
```
`GRACE_PERIOD = 2분`: claim 이후 검증은 정상적으로 수 ms~수십 ms 수준이므로, 2분은 이미
정상 처리 시간 대비 수천 배 마진이다. 스케줄러 방식과 달리 이 값을 줄여도 폴링 지연이
안 붙으므로, 값 자체가 곧 "유령이 실제로 자리를 막는 최대 시간"이 된다.

**동시성 분석**: 두 요청이 동시에 같은 유령 세션의 재점유를 시도해도, `reclaimIfExpired`가
`claim`과 동일한 원자적 `UPDATE ... WHERE`이므로 정확히 하나만 성공한다(#68의 기존 근거와
동일). "활성 신청 없음" 확인과 재점유 `UPDATE` 사이의 이론적 레이스(그 사이 원래 요청이
뒤늦게 살아나 신청을 저장)는 #68/마스터 기획서가 이미 인정한 잔여 리스크와 같은 성격이며,
`GRACE_PERIOD`를 정상 처리 시간보다 압도적으로 크게 잡아 발생 확률을 사실상 0에 가깝게
낮춘다(스케줄러 방식이었어도 동일하게 안고 가야 했던 리스크로, 이번 재설계로 새로 생기는
게 아니다).

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
- 수정: `SchoolCampSessionRepository`(쿼리 메서드 추가, 기존 `claim`은 불변),
  `SchoolCampSessionClaimService`(`SchoolCampApplicationRepository` 의존성 추가,
  `claim`에 재점유 폴백 추가, `GRACE_PERIOD` 상수 추가), `SchoolCampService`
  (`getCalendar`/`toCalendarResponse` 시그니처에 `now` 추가), `SchoolCampController`
  (`getCalendar`가 `now`를 계산해 전달)
- 변경 없음: `SchoolCampApplicationRepository`(기존 메서드 재사용), API 요청/응답 스키마,
  데이터베이스 스키마

## 리스크 및 고려사항
- **API 설계 6원칙**: 요청/응답 스키마가 바뀌지 않아 해당 없음(내부 구현 변경).
- **`claim` 관련 쿼리를 건드리는 리스크**: 기존 `claim` 메서드 자체는 그대로 두고
  새 메서드(`reclaimIfExpired`)만 추가하는 방식으로 최소화했다 — #68이 이미 검증한
  동시성 보장(원자적 `UPDATE ... WHERE`)을 그대로 재사용하는 형태라 새로운 종류의
  동시성 버그를 들이지 않는다.
- **잔여 레이스(이론상, 인지하되 수용)**: 위 "동시성 분석" 절 참고 — 마스터 기획서가
  이미 인정한 리스크와 동일한 성격, 새로 생기는 리스크 아님.
- **DB 위생**: 캘린더/claim 양쪽 모두 유예시간 기준으로 판단하므로, 재신청 시도가
  단 한 번도 없는 유령 세션의 `taken_at`은 DB에 계속 stale 값으로 남을 수 있다.
  기능적 영향은 없다(claim/캘린더 둘 다 시간 계산으로 우회하므로) — 순수 데이터 정리는
  이번 범위에서 다루지 않는다(필요해지면 훨씬 가벼운 별도 배치로 후속 처리).
- **`#83`(자리나면 알림받기)과의 관계**: 유령 회수는 실제 취소(#70)와 성격이 달라
  (아무도 신청한 적 없는 자리가 풀리는 것) 대기자 알림을 연결하지 않는다 — 마스터 방향
  그대로 유지.
- **새 인프라 비용**: 없음(스케줄러 폐기로 이 항목 자체가 사라짐).

## 테스트
- `SchoolCampSessionClaimServiceTest`(신규 파일 또는 관련 통합 테스트 확장):
  - 유예시간 이전에 점유된 세션은 재점유되지 않는지(정상 예약 보호)
  - 유예시간이 지났지만 활성 신청이 있으면 재점유되지 않는지
  - 유예시간이 지났고 활성 신청이 없으면 재점유에 성공하는지
  - 두 요청이 동시에 재점유를 시도하면 정확히 하나만 성공하는지(가능하면 검증, 어려우면
    `reclaimIfExpired`가 영향받은 행 수로 결과를 정확히 반환하는지 단위 테스트로 대체)
- `SchoolCampServiceTest`(기존 `GetCalendar` `@Nested`에 케이스 추가):
  - 유예시간 이전 유령 후보는 여전히 CLOSED로 보이는지(기존 방어 로직 유지 확인)
  - 유예시간이 지난 유령 후보는 OPEN으로 보이는지
  - 활성 신청이 있는 정상 예약은 유예시간과 무관하게 항상 CLOSED로 보이는지

## 완료 조건 (Definition of Done)
- 로컬 빌드/테스트 통과
- CI 통과
- Postman/Notion 반영: 해당 없음(요청/응답 스키마 변경 없음)
