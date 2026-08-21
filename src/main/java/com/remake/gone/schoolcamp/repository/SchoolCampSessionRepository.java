package com.remake.gone.schoolcamp.repository;

import com.remake.gone.schoolcamp.entity.SchoolCampSession;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link SchoolCampSession} 리포지토리.
 */
public interface SchoolCampSessionRepository extends JpaRepository<SchoolCampSession, Long> {

  /**
   * 주어진 날짜 중 이미 세션이 등록된 날짜가 있는지 확인합니다.
   *
   * @param campDates 확인할 날짜 목록
   * @return 하나라도 이미 등록되어 있으면 {@code true}
   */
  boolean existsByCampDateIn(List<LocalDate> campDates);

  /**
   * 주어진 기간(양 끝 포함)에 속하는 세션을 전부 조회합니다.
   *
   * @param from 조회 시작일
   * @param to   조회 종료일
   * @return 조건에 맞는 세션 목록
   */
  List<SchoolCampSession> findByCampDateBetween(LocalDate from, LocalDate to);

  /**
   * 세션을 원자적으로 점유합니다(#68 "선착순" 동시성 처리의 핵심).
   *
   * <p>{@code WHERE taken_at IS NULL} 조건이 붙은 이 {@code UPDATE}는 MySQL(InnoDB)에서
   * 그 자체로 원자적이다 — 이 문장을 실행하는 트랜잭션이 대상 행에 배타적 행 잠금을 걸고,
   * {@code REPEATABLE READ} 격리 수준에서도 {@code UPDATE}는 "current read"로 동작해
   * 트랜잭션 시작 시점의 스냅샷이 아니라 그 순간의 최신 커밋 데이터를 기준으로
   * {@code WHERE}를 평가한다. 그래서 같은 세션에 수십~수백 개의 동시 요청이 이 메서드를
   * 동시에 호출해도, 그중 정확히 하나만 영향받은 행 수 1을 반환하고 나머지는 전부 0을
   * 반환한다 — 별도의 유니크 인덱스 트릭이나 애플리케이션 레벨 락, 재시도 루프 없이도
   * "check-then-act" 레이스가 생기지 않는다.
   *
   * <p>이 메서드는 {@link com.remake.gone.schoolcamp.service.SchoolCampSessionClaimService}를
   * 통해서만 호출한다 — 그 클래스의 Javadoc에 이 쿼리를 별도 트랜잭션으로 즉시 커밋해야 하는
   * 이유(HikariCP 커넥션 풀 경합)가 있다.
   *
   * @param id      점유할 세션의 PK
   * @param takenAt 점유 시각으로 기록할 값(호출 시점의 "지금")
   * @return 영향받은 행 수. 이미 다른 신청이 이 세션을 점유했으면 {@code 0}, 이번 호출이
   *     점유에 성공했으면 {@code 1}
   */
  @Modifying
  @Query("update SchoolCampSession s set s.takenAt = :takenAt "
      + "where s.id = :id and s.takenAt is null")
  int claim(@Param("id") Long id, @Param("takenAt") LocalDateTime takenAt);

  /**
   * 세션 점유를 반환합니다(취소, 또는 claim 이후 검증 실패 시 사용).
   *
   * <p>{@code WHERE ... AND taken_at = :expectedTakenAt}로 조건부(compare-and-swap)
   * 실행한다(#84 코드 리뷰 High 1번 대응) — 호출한 쪽이 자신이 claim에 성공했을 때 받은
   * 값을 그대로 넘겨야 한다. 그래야 이 호출이 지연되는 사이 그 세션이 이미 다른 요청에
   * 재점유(#84 {@link #reclaimIfExpired})됐다면, 남의 정상적인 새 점유를 실수로
   * 되돌리지 않고 조용히 아무 일도 하지 않는다(영향받은 행 {@code 0}).
   *
   * <p><b>정밀도 주의</b>: {@code taken_at}은 {@code DATETIME}(소수점 이하 초 없음)
   * 컬럼이라 저장 시 나노초가 잘려나간다. {@code claim}에 넘긴 시각이 초 단위로 미리
   * 잘려있지 않으면, 이 메서드에 그대로 넘겨도 DB에 실제 저장된(잘린) 값과 항상
   * 불일치해 반환이 매번 조용히 실패한다 — 호출부는 반드시 {@code claim}에 쓴 시각을
   * {@code truncatedTo(ChronoUnit.SECONDS)}로 미리 잘라서 넘겨야 한다
   * ({@code SchoolCampController#applyToCamp} 참고).
   *
   * @param id               반환할 세션의 PK
   * @param expectedTakenAt  호출한 쪽이 claim(또는 재점유) 성공 시 받은 시각(초 단위로
   *                         잘려있어야 함). 이 값과 현재 {@code taken_at}이 일치할 때만
   *                         반환이 실행된다
   * @return 영향받은 행 수(반환 성공 시 {@code 1}, 이미 다른 값으로 바뀌었으면 {@code 0})
   */
  @Modifying
  @Query("update SchoolCampSession s set s.takenAt = null "
      + "where s.id = :id and s.takenAt = :expectedTakenAt")
  int release(@Param("id") Long id, @Param("expectedTakenAt") LocalDateTime expectedTakenAt);

  /**
   * "유령 점유" 후보 세션을 재점유합니다(#84). "유예시간이 지났음"과 "활성 신청이 없음"을
   * {@code NOT EXISTS} 서브쿼리로 하나의 원자적 {@code UPDATE}에 합쳤다(#84 코드 리뷰
   * High 1번/Medium 3번 대응) — 별도 {@code SELECT}로 활성 신청 여부를 먼저 확인하고
   * 그 결과를 바탕으로 이 {@code UPDATE}를 실행하는 두 단계 방식은, 그 사이(확인 SELECT와
   * 재점유 UPDATE 사이)에 원래 점유자가 뒤늦게 신청을 커밋하면 같은 세션에 활성 신청이
   * 2건 남는 레이스를 열어준다. 하나의 문장으로 합치면 그 갭 자체가 사라지고, 실패하는
   * 대다수의 claim(유예시간이 안 지났거나 이미 활성 신청이 있는 경우)에서 별도 SELECT
   * 왕복이 추가되지 않는 부수 효과도 있다 — {@link #claim}과 동일한 InnoDB current-read
   * 원자성을 그대로 재사용한다(JPQL 벌크 UPDATE의 상관 서브쿼리 지원 여부가 불확실해
   * 네이티브 쿼리로 작성했다).
   *
   * <p>{@link com.remake.gone.schoolcamp.service.SchoolCampSessionClaimService}를
   * 통해서만 호출한다.
   *
   * <p><b>남은 잔여 리스크(인지, 수용)</b>: 이 원자적 조건은 "재점유를 시도하는 그 순간"
   * 활성 신청이 없다는 것만 보장한다 — 원래 점유자가 죽지 않고 살아서 처리 중이다가(예:
   * GRACE_PERIOD보다 오래 걸리는 극단적 커넥션 풀 경합) 이 재점유 이후에 뒤늦게 신청을
   * 성공적으로 커밋하면, 그 신청 자체는 이 원자성으로 막을 수 없다(상세는
   * {@code docs/domain/schoolcamp/84-schoolcamp-ghost-claim-recovery.md} "동시성 분석"
   * 절 참고 — claim 자체는 그대로 성공하므로 {@link #release}의 CAS 가드로도 막지 못하는
   * 유일한 경로다).
   *
   * @param id        재점유할 세션의 PK
   * @param threshold 이 시각보다 이전에 점유된 경우에만 재점유를 허용
   * @param now       재점유 시각으로 기록할 값
   * @return 영향받은 행 수(재점유 성공 시 {@code 1}, 이미 누가 먼저 가져갔거나 활성 신청이
   *     있으면 {@code 0})
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
}
