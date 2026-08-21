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
   * <p>{@code claim}과 정확히 반대 방향의 조건 없는 {@code UPDATE}다 — 이미 {@code null}인
   * 세션에 다시 호출해도(예: 중복 반환) 부작용 없이 그대로 {@code null}로 남는다(멱등).
   *
   * @param id 반환할 세션의 PK
   * @return 영향받은 행 수(정상적으로는 항상 {@code 1} — 존재하지 않는 {@code id}면
   *     {@code 0})
   */
  @Modifying
  @Query("update SchoolCampSession s set s.takenAt = null where s.id = :id")
  int release(@Param("id") Long id);

  /**
   * "유령 점유" 후보 세션을 재점유합니다(#84). {@link #claim}과 동일한 InnoDB
   * current-read 원자성을 갖는다 — 이미 다른 요청이 먼저 되찾아갔으면(taken_at이 더
   * 최근 값으로 갱신됐으면) 영향받은 행이 0이 된다.
   *
   * <p>이 메서드는 호출 전 반드시 "활성 신청이 정말 없는지"를 확인해야 한다 — 시간
   * 경과만으로는 유령 점유와 오래전에 정상 성사된 예약을 구분할 수 없다(상세 근거는
   * {@code docs/domain/schoolcamp/84-schoolcamp-ghost-claim-recovery.md} "핵심 위험"
   * 절 참고). {@link com.remake.gone.schoolcamp.service.SchoolCampSessionClaimService}
   * 를 통해서만 호출한다.
   *
   * @param id        재점유할 세션의 PK
   * @param threshold 이 시각보다 이전에 점유된 경우에만 재점유를 허용
   * @param now       재점유 시각으로 기록할 값
   * @return 영향받은 행 수(재점유 성공 시 {@code 1}, 이미 누가 먼저 가져갔으면 {@code 0})
   */
  @Modifying
  @Query("update SchoolCampSession s set s.takenAt = :now "
      + "where s.id = :id and s.takenAt < :threshold")
  int reclaimIfExpired(
      @Param("id") Long id,
      @Param("threshold") LocalDateTime threshold,
      @Param("now") LocalDateTime now);
}
