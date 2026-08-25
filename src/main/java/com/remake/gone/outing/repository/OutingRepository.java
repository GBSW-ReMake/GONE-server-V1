package com.remake.gone.outing.repository;

import com.remake.gone.outing.entity.Outing;
import com.remake.gone.outing.enums.OutingStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link Outing} 리포지토리.
 */
public interface OutingRepository extends JpaRepository<Outing, Long> {

  /**
   * 외부 식별자 코드로 외출증을 조회합니다.
   *
   * @param code 조회할 코드
   * @return 외출증 정보, 없으면 {@link Optional#empty()}
   */
  Optional<Outing> findByCode(String code);

  /**
   * 특정 학생의 특정 날짜에, 주어진 상태들에 해당하는 외출증을 조회합니다. 시간 겹침(중복 신청)
   * 검사에 사용합니다.
   *
   * @param studentId  학생 사용자 ID
   * @param outingDate 외출 날짜
   * @param statuses   조회할 상태 목록
   * @return 조건에 맞는 외출증 목록
   */
  List<Outing> findByStudentIdAndOutingDateAndStatusIn(
      Long studentId, LocalDate outingDate, Collection<OutingStatus> statuses);

  /**
   * 학생 본인이 신청한, 주어진 날짜 범위(양 끝 포함) 안의 외출증을 DB 페이지네이션으로
   * 조회합니다(#41 도입, #96에서 in-memory 슬라이싱 대신 DB {@code LIMIT/OFFSET}으로 전환).
   *
   * <p>{@code statusEq}/{@code wantExpired} 두 파라미터로 "유효 상태" 필터를 표현한다 — 이
   * 도메인은 {@code PENDING}이 마감을 넘기면 {@code OutingMissedScheduler}(#42)가 최대 1분
   * 주기로 DB {@code status}를 실제로 {@code MISSED}로 갱신하므로, 마감 직후부터 스케줄러가
   * 반영하기 전까지는 DB에 {@code PENDING}으로 남아있는 "빈틈" 구간이 생긴다({@link
   * OutingStatus} Javadoc 참고). 응답 DTO의 유효 상태는 이 빈틈을 조회 시점에 실시간으로
   * 메꿔 보여주므로, 필터도 "DB가 이미 MISSED" + "빈틈 구간(DB는 PENDING인데 마감 지남)"
   * 둘 다 잡아야 한다:
   * <ul>
   *   <li>{@code statusFilter}가 없으면 {@code statusEq=null}, {@code wantExpired=null}
   *       (둘 다 무시, 전체 반환)</li>
   *   <li>{@code APPROVED}/{@code REJECTED}/{@code DEPARTED}/{@code RETURNED}면
   *       {@code statusEq}=그 값, {@code wantExpired=null}(무시)</li>
   *   <li>{@code PENDING}(마감 전만)이면 {@code statusEq=PENDING},
   *       {@code wantExpired=false}</li>
   *   <li>{@code MISSED}(DB가 이미 MISSED이거나, 빈틈 구간에 있는 PENDING)면
   *       {@code statusEq=null}, {@code wantExpired=true} — 이 쿼리 내부에서
   *       {@code status = MISSED OR (status = PENDING AND 마감 지남)}으로 두 경우를 모두
   *       처리하므로 {@code statusEq}로 미리 좁히지 않는다</li>
   * </ul>
   * 마감 판정 조건({@code outingDate}/{@code startTime} vs {@code today}/{@code now})은
   * {@link com.remake.gone.outing.utils.OutingTimeUtils#isPastDeadline}과 동일한 규칙을
   * SQL로 옮긴 것이므로, 그 메서드의 판정 기준이 바뀌면 이 쿼리도 같이 바꿔야 한다.
   *
   * @param studentId   학생 사용자 ID
   * @param dateFrom    범위 시작일
   * @param dateTo      범위 종료일
   * @param statusEq    {@code status} 컬럼과 직접 비교할 값. {@code null}이면 무시
   * @param wantExpired {@code true}면 "유효 상태 MISSED"(DB가 이미 MISSED이거나 빈틈
   *                    구간의 PENDING), {@code false}면 {@code statusEq=PENDING}과 결합해
   *                    "마감 전 PENDING만". {@code null}이면 무시
   * @param today       "오늘" 날짜(KST) — 마감 판정 기준
   * @param now         "지금" 시각(KST) — 마감 판정 기준
   * @param pageable    페이지 번호/크기/정렬
   * @return 조건에 맞는 외출증의 페이지
   */
  @Query("""
      SELECT o FROM Outing o
      WHERE o.student.id = :studentId
        AND o.outingDate BETWEEN :dateFrom AND :dateTo
        AND (:statusEq IS NULL OR o.status = :statusEq)
        AND (:wantExpired IS NULL
             OR (:wantExpired = TRUE
                 AND (o.status = com.remake.gone.outing.enums.OutingStatus.MISSED
                      OR (o.status = com.remake.gone.outing.enums.OutingStatus.PENDING
                          AND (o.outingDate < :today
                               OR (o.outingDate = :today AND o.startTime <= :now)))))
             OR (:wantExpired = FALSE
                 AND (o.outingDate > :today
                      OR (o.outingDate = :today AND o.startTime > :now))))
      """)
  Page<Outing> findStudentRequestsPage(
      @Param("studentId") Long studentId,
      @Param("dateFrom") LocalDate dateFrom,
      @Param("dateTo") LocalDate dateTo,
      @Param("statusEq") OutingStatus statusEq,
      @Param("wantExpired") Boolean wantExpired,
      @Param("today") LocalDate today,
      @Param("now") LocalTime now,
      Pageable pageable);

  /**
   * 담당 선생님에게 지정된, 주어진 날짜 범위(양 끝 포함) 안의 외출증을 DB 페이지네이션으로
   * 조회합니다(#41 도입, #96에서 전환). 필터 파라미터 의미는
   * {@link #findStudentRequestsPage}와 동일하다.
   *
   * @param teacherId   선생님 사용자 ID
   * @param dateFrom    범위 시작일
   * @param dateTo      범위 종료일
   * @param statusEq    {@code status} 컬럼과 직접 비교할 값. {@code null}이면 무시
   * @param wantExpired {@link #findStudentRequestsPage}와 동일한 의미. {@code null}이면 무시
   * @param today       "오늘" 날짜(KST) — 마감 판정 기준
   * @param now         "지금" 시각(KST) — 마감 판정 기준
   * @param pageable    페이지 번호/크기/정렬
   * @return 조건에 맞는 외출증의 페이지
   */
  @Query("""
      SELECT o FROM Outing o
      WHERE o.teacher.id = :teacherId
        AND o.outingDate BETWEEN :dateFrom AND :dateTo
        AND (:statusEq IS NULL OR o.status = :statusEq)
        AND (:wantExpired IS NULL
             OR (:wantExpired = TRUE
                 AND (o.status = com.remake.gone.outing.enums.OutingStatus.MISSED
                      OR (o.status = com.remake.gone.outing.enums.OutingStatus.PENDING
                          AND (o.outingDate < :today
                               OR (o.outingDate = :today AND o.startTime <= :now)))))
             OR (:wantExpired = FALSE
                 AND (o.outingDate > :today
                      OR (o.outingDate = :today AND o.startTime > :now))))
      """)
  Page<Outing> findTeacherReceivedPage(
      @Param("teacherId") Long teacherId,
      @Param("dateFrom") LocalDate dateFrom,
      @Param("dateTo") LocalDate dateTo,
      @Param("statusEq") OutingStatus statusEq,
      @Param("wantExpired") Boolean wantExpired,
      @Param("today") LocalDate today,
      @Param("now") LocalTime now,
      Pageable pageable);

  /**
   * 특정 날짜의 외출증 전체(학생/선생님으로 좁히지 않음)를 DB 페이지네이션으로
   * 조회합니다(#98, 관리용 하루 전체 현황). 필터 파라미터 의미는
   * {@link #findStudentRequestsPage}와 동일하다 — 날짜만 범위가 아니라 단일 값(
   * {@code =} 비교)이라는 점이 다르다.
   *
   * @param date        조회할 외출 날짜
   * @param statusEq    {@code status} 컬럼과 직접 비교할 값. {@code null}이면 무시
   * @param wantExpired {@link #findStudentRequestsPage}와 동일한 의미. {@code null}이면 무시
   * @param today       "오늘" 날짜(KST) — 마감 판정 기준
   * @param now         "지금" 시각(KST) — 마감 판정 기준
   * @param pageable    페이지 번호/크기/정렬
   * @return 조건에 맞는 외출증의 페이지
   */
  @Query("""
      SELECT o FROM Outing o
      WHERE o.outingDate = :date
        AND (:statusEq IS NULL OR o.status = :statusEq)
        AND (:wantExpired IS NULL
             OR (:wantExpired = TRUE
                 AND (o.status = com.remake.gone.outing.enums.OutingStatus.MISSED
                      OR (o.status = com.remake.gone.outing.enums.OutingStatus.PENDING
                          AND (o.outingDate < :today
                               OR (o.outingDate = :today AND o.startTime <= :now)))))
             OR (:wantExpired = FALSE
                 AND (o.outingDate > :today
                      OR (o.outingDate = :today AND o.startTime > :now))))
      """)
  Page<Outing> findByOutingDatePage(
      @Param("date") LocalDate date,
      @Param("statusEq") OutingStatus statusEq,
      @Param("wantExpired") Boolean wantExpired,
      @Param("today") LocalDate today,
      @Param("now") LocalTime now,
      Pageable pageable);

  /**
   * 주어진 상태에 해당하는 외출증을 전부 조회합니다(#42, {@code MISSED} 반영 스케줄러 대상
   * 조회). 스케줄러는 페이지네이션 없이 대상 전체를 훑어야 하므로 {@link List}를 반환하는
   * 이 메서드를 그대로 둔다.
   *
   * @param status 조회할 상태
   * @return 조건에 맞는 외출증 목록
   */
  List<Outing> findByStatus(OutingStatus status);

  /**
   * 주어진 상태에 해당하는 외출증을 DB 페이지네이션으로 조회합니다(#96, 지금 외출 중인 학생
   * 목록). 정렬은 호출부가 {@code pageable}의 {@link org.springframework.data.domain.Sort}로
   * 지정한다({@code departedAt} 오름차순 + {@code id} 보조 정렬 — 가장 오래 나가 있는 학생이
   * 먼저 보이고, {@code departedAt}이 같은 초에 몰려도 페이지 경계에서 순서가 흔들리지 않도록).
   *
   * @param status   조회할 상태
   * @param pageable 페이지 번호/크기/정렬
   * @return 조건에 맞는 외출증의 페이지
   */
  Page<Outing> findByStatus(OutingStatus status, Pageable pageable);
}
