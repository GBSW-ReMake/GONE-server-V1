# #42 외출증 마감 지난 PENDING 처리 (승인/거절 차단 + MISSED 스케줄러) — 기획서

관련 이슈: [#42 외출증 마감 지난 PENDING 처리](https://github.com/GBSW-ReMake/GONE-server-V1/issues/42)
선행 이슈: [#41 외출증 본인/담당/단건 조회 API 구현](./41-outing-query.md)

## 개요/목적
#41에서 조회 응답 시점에만 실시간으로 `MISSED`(승인/거절 없이 마감이 지난 `PENDING`)를
계산해 보여주기로 했는데, 그때 DB 자체는 계속 `PENDING`으로 남겨두는 대신 두 가지를 #42로
분리했다:

1. **승인/거절 마감 차단**: `approveOuting`/`rejectOuting`(#30/#31)은 마감 시각을 전혀
   확인하지 않아, 이미 지나가버린 외출증도 아무 때나 승인/거절할 수 있는 구멍이 있다.
2. **DB 실제 반영**: DB의 `status` 컬럼에도 실제로 `MISSED`를 반영하는 게 맞는 방향이라,
   1분 주기 `@Scheduled` 폴링 스케줄러를 추가한다(이 프로젝트 첫 백그라운드 스케줄러).

새 엔드포인트는 없다. 기존 두 엔드포인트(`PATCH /outings/{code}/approve`,
`PATCH /outings/{code}/reject`)의 내부 검증 로직 수정 + 신규 백그라운드 컴포넌트 1개
추가다.

> **이슈 본문 대비 정정**: 이슈 #42 원문의 "`OutingStatus`에 `MISSED` 값 추가" 항목은
> 이미 #41에서 완료됐다(`OutingStatus.java`에 `MISSED`가 6번째 값으로 이미 존재, #41
> 기획서 "영향 받는 기존 코드" 절 참고). 이번 이슈에서 추가로 할 일이 아니라 이미 끝난
> 선행 작업이라 아래 범위에서 제외한다.

## 1. 승인/거절 마감 차단 — `PATCH /outings/{code}/approve`, `PATCH /outings/{code}/reject`
**변경 전**: `outing.getStatus() != PENDING`이면 `ALREADY_PROCESSED`(409)만 확인하고,
마감 시각은 전혀 보지 않는다. DB가 아직 `PENDING`이면 마감이 훨씬 지난 건도 승인/거절이
그대로 처리된다.

**변경 후**: `ALREADY_PROCESSED` 체크를 통과한 뒤(즉 DB상 아직 `PENDING`인 경우),
`OutingTimeUtils.isPastDeadline(outing.getOutingDate(), outing.getStartTime(), today, now)`로
**요청 시점에 다시** 마감 여부를 계산한다. `true`면 처리를 거부한다. DB의 `status` 값에
의존하지 않고 매 요청마다 재계산하므로, 스케줄러가 아직 그 건을 `MISSED`로 갱신하기 전
(최대 1분의 갭)이어도 항상 정확하게 차단된다.

**검증 순서** (기존 순서 유지, 마지막에 추가):
1. `code`로 조회, 없으면 `404` `OUTING_006`
2. 담당 선생님 불일치 → `403` `OUTING_004`
3. `status != PENDING` → `409` `OUTING_005`(`ALREADY_PROCESSED`)
4. **(신규)** 마감 지남 → `409` `OUTING_008`(`DEADLINE_PASSED`, 신규)

**신규 에러 코드**: `OUTING_008` `DEADLINE_PASSED`, `409 CONFLICT`,
"마감이 지나 더 이상 처리할 수 없는 외출증입니다."
- `409`를 쓰는 이유: "존재하지 않음"(`404`)도 "권한 없음"(`403`)도 아니고, `ALREADY_PROCESSED`와
  마찬가지로 "리소스가 지금 상태에서는 그 액션을 받을 수 없다"는 상태 충돌이라 같은
  카테고리(`409`)로 통일한다.
- 번호 부여 방식(승인됨, 마스터 기획서도 함께 수정): 마스터 기획서는 원래
  `OUTING_008`~`010`을 출발/도착 보고 API(#43, 아직 미구현)용으로 미리 적어뒀지만, 이는
  사전 예약이 아니라 작성 시점의 자리 채우기였다. 실제 번호는 **구현되는 순서대로** 그
  시점에 비어있는 다음 번호로 정하기로 재확인했다(`1_outing-domain.md` "에러 코드" 절 상단에
  이 방침을 명시하고, 해당 절의 `008`~`010`을 "번호 미정 — #43에서 구현 시 확정"으로
  갱신했다). #42가 #43보다 먼저 구현되므로 `OutingErrorCode`에서 현재 비어있는 가장 빠른
  번호인 `008`을 그대로 쓴다.

**영향받는 기존 테스트**: `OutingServiceTest`의 `approveOuting`/`rejectOuting` 그룹에
"마감 지난 건은 거부한다" 케이스를 추가한다. 기존 성공 케이스(`approvesSuccessfully`,
`rejectsSuccessfully`)는 마감 전 시각을 쓰므로 영향 없음.

## 2. `MISSED` DB 실제 반영 — 신규 `@Scheduled` 스케줄러
> **코드 리뷰(9단계) 이후 정정**: 최초 설계는 스케줄러가 마감 지난 `PENDING`을 전부 읽어
> 한 트랜잭션에서 `saveAll`로 한 번에 갱신하는 방식이었다. 코드 리뷰에서 이 방식이
> `approvedAt`/`rejectedReason`까지 조용히 유실시킬 수 있는 레이스를 지적했고(아래 "동시성"
> 절 참고), 보스가 낙관적 락(`@Version`) 도입을 선택해 아래 설계로 바뀌었다. **데이터 모델
> 변경이 생겨(하단 "데이터 모델 변경" 절 갱신) 최초 기획의 "데이터 모델 변경 없음" 판단도
> 함께 정정한다.**

**신규 컴포넌트**: `outing/scheduler/OutingMissedScheduler.java` — 조회(읽기 전용)와 건별
갱신(각각 독립 트랜잭션)을 분리해서 호출한다.
```java
@Component
@RequiredArgsConstructor
public class OutingMissedScheduler {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final OutingService outingService;

  @Scheduled(fixedDelay = 60_000) // 1분
  public void markOverdueOutingsAsMissed() {
    LocalDateTime now = LocalDateTime.now(KST);
    List<Long> overdueIds =
        outingService.findOverdueOutingIds(now.toLocalDate(), now.toLocalTime());
    overdueIds.forEach(outingService::markSingleOutingAsMissed);
  }
}
```
`fixedDelay`(이전 실행 **종료 후** 60초, `fixedRate` 아님)를 쓴다 — 실행 시간이 늘어나도
겹쳐 실행되지 않게 하기 위함(현재는 몇 건 안 되지만, 안전한 기본값을 처음부터 선택).

**신규 서비스 메서드 2개** (기존 계획의 `markOverdueOutingsAsMissed(today, now)` 단일
메서드를 대체):
```java
@Transactional(readOnly = true)
public List<Long> findOverdueOutingIds(LocalDate today, LocalTime now) {
  return outingRepository.findByStatus(OutingStatus.PENDING).stream()
      .filter(o -> OutingTimeUtils.isPastDeadline(o.getOutingDate(), o.getStartTime(), today, now))
      .map(Outing::getId)
      .toList();
}

@Transactional
public void markSingleOutingAsMissed(Long outingId) {
  Optional<Outing> found = outingRepository.findById(outingId);
  if (found.isEmpty() || found.get().getStatus() != OutingStatus.PENDING) {
    return; // 조회 이후 이미 승인/거절이 먼저 커밋됨 — 건드리지 않는다
  }
  Outing outing = found.get();
  outing.setStatus(OutingStatus.MISSED);
  try {
    outingRepository.save(outing);
  } catch (ObjectOptimisticLockingFailureException e) {
    log.warn("외출증 MISSED 갱신 중 낙관적 락 충돌로 건너뜀(outingId={})", outingId, e);
  }
}
```
**왜 하나가 아니라 두 메서드로 나눴나**: 배치 하나(`saveAll`)로 묶으면 그 안의 한 건이
낙관적 락 충돌로 `ObjectOptimisticLockingFailureException`을 던지는 순간, JPA 스펙상 그
영속성 컨텍스트는 더 이상 쓸 수 없어 같은 트랜잭션의 나머지 건까지 전부 롤백된다. 건마다
독립 트랜잭션(`markSingleOutingAsMissed`가 `@Transactional`)으로 분리하면 한 건의 충돌이
다른 건에 영향을 주지 않는다. 조회(`findOverdueOutingIds`)와 갱신을 분리한 것도 같은 이유
— 조회 시점 스냅샷을 오래 들고 있을수록 그 사이에 승인/거절이 끼어들 창이 넓어진다.

**신규 리포지토리 메서드**: `OutingRepository.findByStatus(OutingStatus status)` → `List<Outing>`

> **이슈 본문 대비 정정(승인 필요)**: 이슈 #42 원문은 "DB 쿼리 레벨 최적화
> (`WHERE status = ...` 조건 추가 등)도 하지 않는다"고 적혀 있어, 문자 그대로 읽으면
> 스케줄러가 매 분 **테이블 전체**(모든 상태, 모든 날짜)를 읽어와 서비스 코드에서
> `status == PENDING`까지 걸러야 한다. 하지만 재검토해보니 이건 #41의 "DB 레벨 `status`
> 필터 금지" 이유와 상황이 다르다 — #41은 `MISSED`가 DB에 실제로 없는 값이라
> `WHERE status='MISSED'`가 영원히 빈 결과가 되는 게 문제였지, `PENDING`은 실제 저장되는
> 값이라 `WHERE status='PENDING'` 필터는 항상 정확하다. 오히려 스케줄러는 조회 API와
> 달리 날짜 범위로 자연스럽게 좁혀지지 않고 **영구히 매 분 반복 실행**되므로, 시간이
> 지날수록(승인/거절/반려로 이미 끝난 건까지 포함해) 테이블 전체를 계속 훑는 건 #41보다
> 성능 리스크가 더 크다고 판단했다. 그래서 위 설계는 이슈 원문과 다르게
> **`WHERE status = 'PENDING'` 필터를 리포지토리 메서드에 넣는 것으로 변경 제안**한다
> (그 이상의 최적화—예: `outingDate` 하한 추가—는 여전히 하지 않는다, YAGNI). 이 부분은
> 이슈 원문에서 이미 "채택하지 않기로 결정"한 대안들과는 성격이 달라(정밀 스케줄링/Quartz
> 도입처럼 새 인프라가 아니라 기존 Spring Data 쿼리 메서드 이름만 좁히는 것), **검토
> 단계에서 확인 부탁드립니다.**

**동시성(코드 리뷰로 재확인 후 정정)**: 최초 설계는 "스케줄러가 먼저 커밋되면 승인 시도가
`ALREADY_PROCESSED`로 막히고, 승인이 먼저 커밋되면 스케줄러 조회 대상에서 빠진다"고 봐서
별도 락 없이 안전하다고 판단했다. 이 설명은 **두 트랜잭션의 읽기 시점이 겹치지 않는다는
전제**에서만 성립한다 — 실제로는 다음 순서가 가능하다:
1. 스케줄러(T1)가 마감 지난 외출증 O를 읽는다(`status=PENDING`, `approvedAt=null`).
2. T1이 커밋하기 전에 선생님이 O를 승인(T2) — `status=APPROVED`, `approvedAt=X`로 커밋.
3. T1이 뒤늦게 커밋되면, T1이 들고 있던 낡은 스냅샷 그대로 `UPDATE`가 나가 `status=MISSED`,
   `approved_at=NULL`로 O를 덮어쓴다. **T2의 승인 기록이 에러 없이 사라진다.**

`Outing`에 `@Version`이 없던 시점에는 Hibernate가 매핑된 전체 컬럼을 스냅샷 값 그대로
`UPDATE`하기 때문에 `status`뿐 아니라 `approvedAt`/`rejectedReason`까지 함께 유실됐다(#42
코드 리뷰, `42-outing-missed-scheduler-code-review.md` 1번 항목). 이를 막기 위해:
- `Outing`에 `@Version`을 추가해(아래 "데이터 모델 변경" 참고) T1이 낡은 버전으로 저장을
  시도하면 `ObjectOptimisticLockingFailureException`으로 **감지**하도록 했다.
- `markSingleOutingAsMissed`가 저장 직전에 `status`를 다시 확인해(위 코드 참고) 그 사이
  이미 처리된 건은 애초에 건드리지 않고, 그래도 충돌하면 예외를 잡아 경고 로그만 남기고
  건너뛴다(그 건은 다음 스케줄러 주기에 다시 시도되지 않는다 — 이미 `PENDING`이 아니게
  됐을 것이므로 재시도 자체가 불필요).

## 3. 검토 후 채택하지 않기로 결정한 대안 (이슈 원문 그대로 기록)
- **개별 마감 시각에 정밀하게 맞춘 동적 스케줄링**(`TaskScheduler.schedule(task, 정확한
  시각)`): 서버 재시작 시 예약 유실, 다중 인스턴스 환경 중복 실행 위험.
- **Quartz + JDBC JobStore** 등 클러스터 안전 정밀 스케줄링: DB 값의 "즉시성"이 실제 사용자
  경험에 영향을 주지 않는다(조회/승인·거절 모두 매 요청 실시간 재계산이라 DB 반영 지연과
  무관하게 항상 정확함). 소비자(관리자 페이지)도 아직 없어 지금 도입은 과하다.

## 데이터 모델 변경
> **코드 리뷰 이후 정정**: 최초 판단("없음")은 스케줄러가 무조건 `saveAll`로 갱신하는
> 설계 기준이었다. 낙관적 락 도입으로 컬럼이 하나 추가된다.

`Outing`에 `version`(`BIGINT NOT NULL DEFAULT 0`) 컬럼 추가 — JPA `@Version` 매핑,
낙관적 락 감지용. Flyway 마이그레이션 `V8__add_outing_version.sql` 신규 추가. 기존 행은
`DEFAULT 0`으로 채워지므로 백필 로직 불필요. `OutingStatus`의 `MISSED` 값은 #41에서 이미
추가됐다(위 "이슈 본문 대비 정정" 참고).

## 영향 받는 기존 코드
- `Outing` 엔티티: `@Version private Long version;` 필드 추가(위 "데이터 모델 변경" 참고)
- `OutingService`:
  - `approveOuting`/`rejectOuting`: 마감 체크를 신규 `private validateNotPastDeadline(Outing,
    LocalDateTime)`로 추출해 호출(코드 리뷰 Low 항목 반영 — 기존 `validateStudentRole` 등과
    같은 검증-메서드-분리 컨벤션에 맞춤). 시그니처 변경 없음.
  - `findOverdueOutingIds(LocalDate today, LocalTime now)`(신규, 반환 `List<Long>`,
    `@Transactional(readOnly = true)`)
  - `markSingleOutingAsMissed(Long outingId)`(신규, 반환 `void`, `@Transactional`) — 최초
    계획의 `markOverdueOutingsAsMissed(today, now)` 단일 메서드를 대체(위 "2." 절 참고)
- `OutingRepository`: `findByStatus(OutingStatus status)` → `List<Outing>`(신규, Spring Data
  쿼리 메서드)
- `outing/scheduler/OutingMissedScheduler`(신규 패키지+클래스)
- `GoneServerV1Application`: `@EnableScheduling` 추가(이 프로젝트에서 스케줄링을 쓰는 첫
  사례라 전역 활성화가 필요, `common/config`에 별도 `@Configuration` 클래스를 만들 만큼
  설정이 늘어나지 않아 진입점 클래스에 바로 붙인다 — Spring Boot 기본 관례)
- `OutingErrorCode`: `OUTING_008`(`DEADLINE_PASSED`, 신규) 추가

## API 설계 6원칙 체크
1. **한 가지를 잘하기**: 새 엔드포인트가 없어 해당 없음. 기존 두 엔드포인트는 각자 원래
   책임(승인/거절)에 마감 검증만 추가하는 것이라 책임이 늘어나지 않는다.
2. **빠르게 시작하기**: 승인/거절 요청/응답 스키마 자체는 안 바뀌므로 별도 예시 불필요,
   신규 에러 응답만 아래 테스트 방법에서 확인한다.
3. **일관성**: 신규 에러 코드가 기존 `{DOMAIN}_NNN` 네이밍/`CustomException` 패턴을 그대로
   따른다. 스케줄러도 이 프로젝트의 기존 순수 유틸(`OutingTimeUtils`) + 서비스 계층 분리
   패턴을 재사용한다(스케줄러는 트리거 역할만, 실제 로직은 `OutingService`에 둔다 —
   컨트롤러가 얇고 서비스가 로직을 갖는 기존 구조와 동일).
4. **의미 있는 오류**: `DEADLINE_PASSED`(신규)는 `ALREADY_PROCESSED`와 원인이 달라(하나는
   "이미 결론이 났음", 하나는 "결론 없이 시간만 지남") 코드를 분리했다.
5. **확장성/성능**: 위 "이슈 본문 대비 정정" 절 참고 — `findByStatus(PENDING)`으로 최소한의
   DB 레벨 필터는 유지하도록 이슈 원문에서 변경 제안. 스케줄러 주기(1분)는 이슈에서 이미
   확정된 값.
6. **하위 호환성**: 기존 승인/거절 API의 성공 케이스 동작/응답 스키마는 그대로 유지된다.
   새로 추가되는 건 실패 케이스(신규 409) 하나뿐이라, 마감 전에 정상적으로 호출하던
   기존 클라이언트에는 영향이 없다.

## 알려진 제약 (경미, 의도된 트레이드오프 — "버그"로 트래킹만, 이슈 원문 그대로)
실제 마감 시각이 지난 순간부터 스케줄러가 다음 실행에서 그 건을 처리할 때까지(최대 1분)
DB의 `status` 컬럼은 여전히 `PENDING`이다. 조회 API(#41)와 승인/거절 API(이 이슈에서 추가하는
마감 체크 포함)는 매 요청마다 실시간 재계산을 하므로 이 지연과 무관하게 항상 정확하다 —
영향받는 건 DB를 직접 보는 경로(SQL, 향후 관리자 페이지 등)뿐이고, 그마저도 최대 1분 이내에
자동으로 해소된다.

## 테스트 방법
로컬 서버(`baseUrl=http://localhost:9091`) 기동 후 Postman 컬렉션(`GONE - Outing API`) +
`GONE - Local Dev` 환경으로 검증한다.

1. 학생 로그인 → 마감이 임박한(1~2분 뒤 시작) `CUSTOM` 시간대로 외출증 신청
2. 마감 전에 선생님이 승인 시도 → `200 OK` 정상 처리되는지 확인(기존 동작 회귀 없음)
3. 새로 외출증 하나 더 신청 → 마감을 지나도록 대기 → 선생님이 승인 시도 → `409`
   `OUTING_008` 확인
4. 3번과 같은 방식으로 거절 시도 → `409` `OUTING_008` 확인
5. 3번 건을 처리하지 않고 스케줄러 주기(1분) 이상 대기 → DB(또는 `GET /outings/{code}`
   재조회로 간접 확인, 로컬 DB 직접 조회 가능하면 `SELECT status`)에서 `status`가 실제로
   `MISSED`로 바뀌었는지 확인
6. 5번 이후 다시 승인/거절 시도 → `409` `OUTING_005`(`ALREADY_PROCESSED`)로 바뀌어 있는지
   확인(마감 체크가 아니라 상태 체크에서 걸리는지 — DB가 이미 `MISSED`이므로 `status !=
   PENDING` 분기에서 막힘)
7. 단위 테스트: `OutingServiceTest`에 마감 지난 승인/거절 케이스, `findOverdueOutingIds`
   케이스(마감 지난 PENDING의 ID만 반환하는지), `markSingleOutingAsMissed` 케이스(PENDING만
   MISSED로 바뀌는지, 이미 처리된 건/존재하지 않는 ID는 건드리지 않는지, 낙관적 락 충돌
   시 예외를 삼키는지) 추가

## 리스크 및 고려사항
- **동시성**: 위 "2. MISSED DB 실제 반영" 절의 "동시성" 항목 참고 — 코드 리뷰로 최초 설계의
  레이스(승인/거절 기록 유실 가능성)를 확인했고, `@Version` 낙관적 락 + 건별 독립 트랜잭션
  갱신으로 정정함.
- **스케줄러 조회 범위(`findByStatus`) 변경 제안**: 위 "이슈 본문 대비 정정" 절 — 검토
  단계에서 승인 필요.
- **에러 코드 번호(`OUTING_008`)**: 마스터 기획서가 작성 시점에 `008`~`010`을 #43(출발/도착
  API)용으로 적어뒀던 건 사전 예약이 아니라는 게 보스 확인으로 정리됐다. 구현 순서대로 번호를
  매기기로 하고 마스터 기획서도 함께 갱신했다(위 "이슈 본문 대비 정정" 절 참고). #43이 실제로
  구현될 때는 그 시점에 비어있는 다음 번호를 새로 받는다.
- **다중 인스턴스 배포 시 중복 실행**: 현재 배포 환경이 단일 인스턴스라고 가정한다. 여러
  인스턴스가 동시에 떠 있으면 스케줄러가 인스턴스 수만큼 중복 실행되지만(같은 건을 여러 번
  `MISSED`로 갱신 시도), `UPDATE`가 멱등적이라(이미 `MISSED`인 건 다시 `MISSED`로 써도
  결과가 같음) 데이터 정합성 문제는 없다. 다만 불필요한 쿼리가 인스턴스 수만큼 반복되는
  비효율은 있다 — 다중 인스턴스 환경이 실제로 생기면 그때 분산 락(예: `ShedLock`) 도입을
  재검토한다(YAGNI, 지금은 해당 없음).
