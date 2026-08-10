# #42 외출증 마감 지난 PENDING 처리 (승인/거절 마감 차단 + MISSED 스케줄러) — 코드 리뷰 결과

관련 기획서: [42-outing-missed-scheduler.md](./42-outing-missed-scheduler.md)
형식 규칙: [code-review-template.md](../../rules/code-review-template.md)

## 리뷰 범위/방법

- 대상: `git diff dev...HEAD`의 코드 diff만(구현 커밋 4개 — 에러코드/리포지토리 →
  서비스 로직 → 스케줄러 → 테스트). 문서 커밋 2개(`0dfcc1c`, 그리고 브랜치 시작 시점의
  `a6271fe`)는 리뷰 대상에서 제외했다.
- 변경 파일: `GoneServerV1Application.java`, `OutingErrorCode.java`,
  `OutingRepository.java`, `OutingMissedScheduler.java`(신규),
  `OutingService.java`(`approveOuting`/`rejectOuting`/`markOverdueOutingsAsMissed`),
  `OutingServiceTest.java`.
- 기획서(`42-outing-missed-scheduler.md`) 대비 범위 초과 변경 없음을 확인했다. 신규
  엔드포인트 없음, 기존 두 엔드포인트의 응답 스키마 변경 없음, 데이터 모델(마이그레이션)
  변경 없음 — 전부 기획서 기술과 일치.
- 코드 스타일(`code-style.md`)·에러 코드 네이밍(`OUTING_NNN` + `CustomException` 패턴)·
  응답 포맷·테스트 구조(`@Nested` + `@DisplayName` + `BDDMockito`)는 기존 컨벤션과
  일치함을 확인했다.
- 독립 에이전트(컨텍스트 격리, 같은 diff + 기획서만 전달)로 `code-review` 스킬 리뷰를
  한 라운드 더 돌려 교차 검증했다 — 아래 1번 항목은 두 리뷰에서 동일하게 도출됐다.
- 두 항목 모두 이 리뷰에서는 "적용"까지 진행하지 않고 방안만 정리했다 — 어떤 방안을
  택할지는 다음 검토 단계에서 결정한다.

---

## 1. 🟡 Medium — 스케줄러의 무조건 `UPDATE`가 동시에 처리된 승인/거절을 조용히 덮어씀(`approvedAt`/`rejectedReason` 유실 포함)

**문제**: `OutingService.markOverdueOutingsAsMissed`(`src/main/java/com/remake/gone/outing/service/OutingService.java:264-272`)는
`findByStatus(PENDING)`으로 읽은 엔티티를 그대로 메모리에서 걸러 `status`만
`MISSED`로 바꾼 뒤 `saveAll`로 저장한다. `Outing` 엔티티(`Outing.java`)에는
`@Version`도, `@DynamicUpdate`도 없다 — 즉 Hibernate가 이 엔티티를 플러시할 때
매핑된 **모든 컬럼**을 그 트랜잭션이 읽었던 스냅샷 값 그대로 `UPDATE`한다(`status`뿐
아니라 `approved_at`, `rejected_reason`까지 전부 덮어쓴다). 애플리케이션 코드
어디에도 이 `UPDATE`를 "그 사이에 다른 트랜잭션이 이미 상태를 바꾸지 않았다면"으로
조건화하는 부분이 없다(`WHERE status = 'PENDING'` 같은 조건부 갱신도, DB 레벨
낙관적 락도 없음).

**재현 시나리오**:
1. 스케줄러 트랜잭션(T1)이 `findByStatus(PENDING)`으로 외출증 O(당시 상태
   PENDING, `approvedAt=null`)를 읽어 영속성 컨텍스트에 올린다. 이 시점 O는 이미
   마감이 지난 것으로 필터링되어 "MISSED로 바꿀 대상" 목록에 포함된다.
2. T1이 아직 커밋하기 전, 선생님이 `PATCH /outings/{code}/approve`를 호출해
   T2가 O를 별도로 조회한다(`OutingService.approveOuting`,
   `OutingService.java:114-135`). T2 시점의 `now`(컨트롤러가 캡처한
   `LocalDateTime.now(KST)`) 기준으로는 아직 `isPastDeadline`이 `false`일 수도
   있고(교사가 마감 임박 시각에 클릭), 혹은 이번 PR이 추가한 마감 체크를 통과하지
   못하는 경우도 있지만 — **핵심은 T1이 이미 읽어간 스냅샷과 무관하게 T2는 자신의
   조회 시점 상태(PENDING)만 보고 판단한다는 점**이다. T2는 `status=APPROVED`,
   `approvedAt=X`로 갱신 후 커밋한다.
3. T1이 뒤이어 플러시/커밋되면, T1이 들고 있던 스냅샷(status=PENDING,
   approvedAt=null 등) 기준의 `UPDATE`가 그대로 나가 `status=MISSED`,
   `approved_at=NULL`로 O를 덮어쓴다. **T2가 정상적으로 승인 처리한 기록
   (승인 상태 자체와 `approvedAt` 시각)이 아무 에러 없이 사라진다.**

이는 정확히 기획서 "2. MISSED DB 실제 반영" 절의 "동시성" 항목과 이 메서드의
Javadoc(`OutingService.java:256-259`)이 "안전하다"고 명시한 그 시나리오다 — 두
문서 모두 "스케줄러가 먼저 커밋되면 뒤이은 승인/거절 시도는 `status != PENDING`이라
`ALREADY_PROCESSED`로 막히고, 승인/거절이 먼저 커밋되면 스케줄러의 조회 대상에서
빠진다"고 설명하지만, 이 설명은 **두 트랜잭션의 읽기 시점이 서로 겹치지 않는다는
전제**에서만 성립한다. 위 시나리오처럼 T1의 읽기가 T2의 커밋보다 먼저 일어나면(즉
두 트랜잭션이 겹쳐 실행되면) T1은 O가 이미 처리된 사실을 전혀 모른 채 자신의 스냅샷을
그대로 덮어쓴다 — `status`뿐 아니라 다른 컬럼까지 무조건 갱신하는 Hibernate의 기본
`UPDATE` 생성 방식(변경 감지된 필드가 아니라 매핑된 전체 컬럼을 쓰는 것) 때문에
`approvedAt`/`rejectedReason`까지 함께 유실된다는 점은 기획서에서 언급되지 않은
추가 위험이다.

실제 발생 확률은 낮다(현재 단일 인스턴스, 트래픽이 적은 교내 앱이라 같은 건에 대해
스케줄러 배치의 읽기~쓰기 구간과 승인/거절 요청이 밀리초 단위로 겹쳐야 함). 다만
발생하면 **에러 없이 조용히** 승인/거절 기록이 사라지는 데이터 정합성 문제이고,
사람이 DB를 직접 고치기 전까지 복구할 방법이 없다.

**해결 방안**:
1. **조건부 벌크 `UPDATE`로 전환** — `OutingRepository`에
   `@Modifying @Query("UPDATE Outing o SET o.status = 'MISSED' WHERE o.status = 'PENDING' AND (...)")`
   형태로 마감 판정까지 SQL에 넣거나, 최소한 `WHERE o.status = 'PENDING'` 조건은
   갱신 시점에 다시 검사하도록 만든다. 갱신이 "그 순간 실제로 아직 PENDING인 행"에만
   적용되므로 위 레이스 자체가 원천 차단된다(다른 트랜잭션이 먼저 커밋해 상태를
   바꿨다면 이 `UPDATE`는 그 행에 영향을 주지 않는다). 트레이드오프: 마감 판정 로직을
   `OutingTimeUtils.isPastDeadline`과 JPQL/네이티브 쿼리 양쪽에 유지해야 해 두
   구현이 갈라질 위험이 생기고, 엔티티를 안 거치므로 지금처럼
   `Mockito.verify(saveAll(...))`로 단위 테스트하기 어려워져(`@DataJpaTest` 등
   실제 DB 기반 테스트가 필요) 테스트 전략도 같이 바꿔야 한다.
2. **낙관적 락(`@Version`) 도입** — `Outing`에 `@Version` 컬럼을 추가하면(Flyway
   마이그레이션 필요 — 기획서의 "데이터 모델 변경 없음"과는 상충하는 추가 변경),
   T1이 스냅샷과 다른 버전을 덮어쓰려 할 때 `ObjectOptimisticLockingFailureException`이
   발생해 최소한 **조용히 유실되지 않고 감지**된다. 다만 이 예외를 스케줄러
   배치(`saveAll`) 레벨에서 그대로 던지면 같은 배치의 다른(충돌 없는) 행까지 롤백될
   수 있어, 건별로 개별 저장 + 예외 캐치/로깅으로 바꿔야 하는 추가 구현이 필요하다.
   방안 1보다 구현 비용이 크지만 "충돌이 실제로 있었다"는 사실이 로그로 남는다는
   장점이 있다.
3. **지금은 위험을 감수하고 문서만 정정** — 현재 단일 인스턴스·저트래픽 환경에서
   실제 재현 확률이 매우 낮다고 보고 당장 고치지 않는다. 다만 이 경우 최소한
   `markOverdueOutingsAsMissed`의 Javadoc과 기획서의 "동시성" 절에 있는 "최종
   상태가 어긋나지 않는다"는 현재의 단정적 서술은 부정확하므로, "두 트랜잭션의
   읽기가 겹치지 않는 한"이라는 전제를 명시하도록 정정해야 한다(코드/문서가 실제
   보장 범위보다 더 강한 안전성을 주장하고 있다는 점 자체가 별도로 고쳐야 할
   문제).

---

## 2. 🟢 Low — 승인/거절 마감 체크 블록이 `approveOuting`/`rejectOuting`에 중복 작성됨

**문제**: 이번 PR이 추가한 마감 재계산 블록

```java
if (OutingTimeUtils.isPastDeadline(
    outing.getOutingDate(), outing.getStartTime(), now.toLocalDate(), now.toLocalTime())) {
  throw new CustomException(OutingErrorCode.DEADLINE_PASSED);
}
```

이 `approveOuting`(`OutingService.java:124-127`)과 `rejectOuting`
(`OutingService.java:160-163`) 두 곳에 글자 그대로 복사돼 있다. `OutingService`는
이미 `validateStudentRole`/`validateDateRange`/`validateDeadline`/
`validateClassAssigned`/`validateNoOverlap`/`validatePageParams`/
`validatePeriodParams`/`validateDetailAccess`(`OutingService.java` 전역, 총 8개)처럼
검증 하나당 전용 `private validateXxx` 메서드로 분리하는 일관된 컨벤션을 갖고
있고, 심지어 신청 시점 마감 검증도 이미 `validateDeadline`(`OutingService.java:323`)로
분리돼 있다. 이번 승인/거절 마감 체크만 그 패턴을 따르지 않고 인라인 중복으로
들어갔다.

기능적으로는 문제없지만(두 곳이 지금은 완전히 동일), 이후 이 조건이 바뀌어야
할 때(예: 유예 시간 추가, 혹은 위 1번 해결책 적용 과정에서 로직이 바뀔 때) 한
쪽만 고치고 다른 쪽을 놓치면 승인/거절 중 한쪽만 조용히 옛 로직으로 남는
회귀가 생기기 쉽다.

**해결 방안**:
1. **`private void validateNotPastDeadline(Outing outing, LocalDateTime now)`로
   추출** — 기존 `validateXxx` 컨벤션과 완전히 일치하고, 변경 비용이 메서드 추출
   수준으로 낮다. 단점은 사실상 없다(파라미터 4개를 그대로 넘기던 것을 `Outing`
   엔티티 + `LocalDateTime` 2개로만 넘기면 되므로 호출부도 더 짧아진다).
2. **지금 상태 유지** — 두 호출부가 완전히 동일한 4줄이라 당장 유지보수 비용이
   크지 않고, 셀레니움처럼 큰 반복이 아니라 정말 짧은 조건 하나라 "과도한 추상화"로
   볼 여지도 있다. 다만 이 프로젝트가 이미 이보다 짧은 검증들(`validateStudentRole`
   등)도 전부 분리해 온 만큼, 이 팀의 기존 판단 기준과는 어긋나는 선택이 된다.

---

## 요약

Critical/High 없음. Medium 1건(동시성 — 스케줄러의 무조건 `UPDATE`가 승인/거절
결과를 조용히 덮어쓸 수 있음), Low 1건(마감 체크 블록 중복, DRY 컨벤션 불일치).

## 적용 결과

- **1번(Medium)**: 해결 방안 2(낙관적 락)를 보스가 선택해 적용함. `Outing`에 `@Version`
  컬럼 추가(Flyway `V8__add_outing_version.sql`), `markOverdueOutingsAsMissed` 단일
  메서드를 `findOverdueOutingIds`(읽기 전용 조회) + `markSingleOutingAsMissed`(건별 독립
  트랜잭션 갱신, 저장 직전 상태 재확인 + `ObjectOptimisticLockingFailureException` 캐치)로
  분리해 배치 롤백 문제도 함께 해소함. 상세: `42-outing-missed-scheduler.md` "2. `MISSED`
  DB 실제 반영" 절.
- **2번(Low)**: 해결 방안 1(메서드 추출)을 적용함. `OutingService.validateNotPastDeadline
  (Outing, LocalDateTime)`로 추출해 `approveOuting`/`rejectOuting` 양쪽에서 재사용하도록
  변경.
