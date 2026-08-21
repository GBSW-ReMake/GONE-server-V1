package com.remake.gone.schoolcamp.service;

import com.remake.gone.schoolcamp.repository.SchoolCampApplicationRepository;
import com.remake.gone.schoolcamp.repository.SchoolCampSessionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스쿨캠핑 세션 점유(claim)/반환(release)을 각각 독립된 짧은 트랜잭션으로 실행하는
 * 전용 컴포넌트(#68).
 *
 * <p><b>왜 별도 트랜잭션인가</b>: {@code claim}이 신청 저장·알림 발송(무거운 검증 포함,
 * DB 왕복 12~20회 안팎)과 같은 트랜잭션 안에 있으면, 당첨자가 그 구간 내내 세션 행의
 * 배타적 락을 쥐게 된다. 재학생 300명 규모에서 한 세션에 100명 이상이 동시에 몰릴 수
 * 있는데, 이 프로젝트 HikariCP 풀은 기본값(10)이다 — 그 순간 같은 세션에 몰린 나머지
 * 요청 중 커넥션을 먼저 잡은 것들은 당첨자의 행 잠금 때문에 블로킹되고, 나머지는
 * 커넥션 자체를 못 얻어 대기열에 쌓인다. 이 커넥션 풀은 스쿨캠핑 API 전용이 아니라
 * 서버 전체가 공유하므로, 방치하면 그 순간 로그인·외출 신청 등 무관한 다른 API까지
 * 같이 지연된다. {@code claim}을 {@link Propagation#REQUIRES_NEW}로 분리해 즉시
 * 커밋하면, 행 잠금을 쥐는 시간이 "이 클래스의 UPDATE 한 문장" 수준(수 ms)으로 줄어
 * 이 문제가 사라진다(상세 근거:
 * {@code docs/domain/schoolcamp/68-schoolcamp-application.md}의 "커넥션 풀 경합" 절).
 *
 * <p><b>왜 별도 빈(bean)인가</b>: {@code SchoolCampService.applyToCamp}가 자기 자신의
 * 트랜잭션 안에서 이 클래스의 메서드를 직접 호출하지 않고 반드시 이 클래스를 주입받아
 * 호출해야 한다. Spring의 {@code @Transactional}은 AOP 프록시로 구현되는데, 같은
 * 클래스 안에서 {@code this.claim(...)}처럼 자기 자신을 호출하면 그 프록시를 거치지
 * 않아 {@link Propagation#REQUIRES_NEW}가 조용히 무시된다(컴파일 에러가 나지 않아
 * 발견하기 어렵다) — 그래서 claim/release 전용 빈으로 분리했다.
 *
 * <p><b>트레이드오프</b>: claim이 자기 트랜잭션으로 이미 커밋되므로, 호출한 쪽
 * (예: {@code applyToCamp})의 트랜잭션이 나중에 롤백돼도 claim의 결과는 되돌아가지
 * 않는다. 그래서 claim 이후 단계(선생님 검증, 팀원 조회, 월 중복 확인 등)가 실패하면
 * 호출한 쪽이 {@link #release}를 명시적으로 호출해 세션을 되돌려야 한다 — 예전
 * 설계(같은 트랜잭션에 claim을 두고 예외로 자동 롤백)가 갖고 있던 "자동 반환"의
 * 단순함을 잃는 대신 락 보유 시간을 최소화하는 쪽을 택한 것이다. {@link #release}
 * 호출 자체가 실패하면(DB 순단 등) 세션이 실제로는 비어있는데 점유된 채로 남는
 * "유령 점유" 상태가 될 수 있다.
 *
 * <p><b>유령 점유 즉시 회수(#84)</b>: {@link #claim}은 기존 경로가 실패하면(이미
 * {@code taken_at}이 채워져 있으면), 그 점유가 {@link #GRACE_PERIOD}보다 오래됐고
 * 활성 신청이 정말 없는 경우에 한해 자동으로 재점유를 시도한다. 별도 스케줄러 없이
 * 다음 claim 시도 시점에 즉시 회수하는 방식이다(상세 근거:
 * {@code docs/domain/schoolcamp/84-schoolcamp-ghost-claim-recovery.md}).
 */
@Service
@RequiredArgsConstructor
public class SchoolCampSessionClaimService {

  /**
   * claim 이후 검증은 정상적으로 수 ms~수십 ms 수준이므로, 이 시간이 지난 점유는
   * 유령 후보로 본다(#84). {@link SchoolCampService#getCalendar}도 같은 기준을
   * 참조해 화면과 실제 재점유 가능 여부가 어긋나지 않게 한다.
   */
  public static final Duration GRACE_PERIOD = Duration.ofMinutes(2);

  private final SchoolCampSessionRepository sessionRepository;
  private final SchoolCampApplicationRepository applicationRepository;

  /**
   * 세션을 원자적으로 점유합니다.
   *
   * <p>{@link SchoolCampSessionRepository#claim}을 별도의 {@link Propagation#REQUIRES_NEW}
   * 트랜잭션으로 감싸 즉시 커밋한다 — 호출 시점에 이미 진행 중인 바깥 트랜잭션(있다면)은
   * 이 호출이 끝날 때까지 잠시 중단(suspend)됐다가 재개된다.
   *
   * <p><b>주의(코드 리뷰(#68) 지적 사항)</b>: 이 메서드는 {@code UPDATE}를 별도 영속성
   * 컨텍스트(REQUIRES_NEW)에서 벌크로 실행하므로, 호출한 쪽이 이미 로드해 들고 있는
   * {@code SchoolCampSession} 엔티티는 이 호출이 끝나도 자동으로 갱신되지 않는다 —
   * {@code session.getTakenAt()}은 여전히 호출 전 값(보통 {@code null})을 반환한다. claim
   * 성공 이후 점유 여부를 다시 확인해야 하면 그 엔티티를 그대로 읽지 말고 반드시
   * 리포지토리로 재조회해야 한다.
   *
   * <p>이 경로가 실패하면(이미 점유돼 있으면) {@link #reclaimIfGhost}로 유령 점유
   * 재점유를 시도한다(#84).
   *
   * @param sessionId 점유할 세션의 PK
   * @param takenAt   점유 시각으로 기록할 값
   * @return 이번 호출로 점유(또는 유령 재점유)에 성공했으면 {@code true}, 이미 다른
   *     신청이 유효하게 선점해 영향받은 행이 없으면 {@code false}
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claim(Long sessionId, LocalDateTime takenAt) {
    if (sessionRepository.claim(sessionId, takenAt) == 1) {
      return true;
    }
    return reclaimIfGhost(sessionId, takenAt);
  }

  /**
   * 유령 점유 후보를 재점유합니다(#84). 활성 신청이 정말 없는지 먼저 확인한 뒤에만
   * 재점유를 시도한다 — 시간 경과만으로는 유령 점유와 오래전에 정상 성사된 예약을
   * 구분할 수 없기 때문이다(상세 근거는
   * {@code docs/domain/schoolcamp/84-schoolcamp-ghost-claim-recovery.md} "핵심 위험"
   * 절 참고).
   *
   * @param sessionId 재점유를 시도할 세션의 PK
   * @param now       재점유 시각으로 기록할 값(유예시간 기준 시각으로도 사용)
   * @return 재점유에 성공했으면 {@code true}
   */
  private boolean reclaimIfGhost(Long sessionId, LocalDateTime now) {
    boolean hasActiveApplication =
        applicationRepository.findBySessionIdAndCancelledAtIsNull(sessionId).isPresent();
    if (hasActiveApplication) {
      return false;
    }
    LocalDateTime threshold = now.minus(GRACE_PERIOD);
    return sessionRepository.reclaimIfExpired(sessionId, threshold, now) == 1;
  }

  /**
   * 세션 점유를 반환합니다.
   *
   * <p>{@link #claim}으로 점유에 성공한 뒤, 호출한 쪽의 이후 로직(선생님 검증·팀원
   * 조회·월 중복 확인 등)이 실패했을 때 호출한다. 이 메서드도
   * {@link Propagation#REQUIRES_NEW}라 호출한 쪽의 트랜잭션이 이미 롤백을 결정한
   * 상태여도 독립적으로 커밋된다 — 그래서 실패 처리 흐름에서 호출한 쪽이 예외를 다시
   * 던지기 전에 이 메서드를 먼저 호출해야, 세션이 실제로 다시 열린 뒤에 예외가
   * 전파된다.
   *
   * @param sessionId 반환할 세션의 PK
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void release(Long sessionId) {
    sessionRepository.release(sessionId);
  }
}
