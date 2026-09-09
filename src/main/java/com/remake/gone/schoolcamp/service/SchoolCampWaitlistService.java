package com.remake.gone.schoolcamp.service;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.notification.enums.NotificationType;
import com.remake.gone.notification.service.NotificationService;
import com.remake.gone.schoolcamp.dto.SchoolCampWaitlistResponse;
import com.remake.gone.schoolcamp.dto.SchoolCampWaitlistStatusResponse;
import com.remake.gone.schoolcamp.entity.SchoolCampWaitlist;
import com.remake.gone.schoolcamp.exception.SchoolCampErrorCode;
import com.remake.gone.schoolcamp.repository.SchoolCampWaitlistRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스쿨캠핑 "자리나면 알림받기" 대기 등록/취소/상태 조회 + 취소 발생 시 대기자 알림 발송을
 * 처리하는 서비스(#83).
 *
 * <p>등록/취소/조회 전부 파라미터를 받지 않고, 항상 호출 시점의 "지금 이 순간의 이번 달"만
 * 다룬다({@code 83-schoolcamp-waitlist-notification.md} 참고). {@code SchoolCampService}처럼
 * 이미 책임이 큰 서비스에 섞지 않고 별도 서비스로 분리했다({@code SchoolCampSessionClaimService}
 * 와 같은 이유).
 */
@Service
@RequiredArgsConstructor
public class SchoolCampWaitlistService {

  private static final DateTimeFormatter YYYYMM_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

  private final SchoolCampWaitlistRepository waitlistRepository;
  private final UserRepository userRepository;
  private final NotificationService notificationService;

  /**
   * 이번 달 대기를 등록합니다. 이미 취소된 적 있는 같은 달 행이 있으면 재활성화하고, 없으면
   * 새로 만듭니다.
   *
   * @param studentUserId 등록하는 학생 사용자 ID(Access Token에서 추출됨)
   * @param now           등록 시각으로 기록할 "지금"(KST) — 이 시각이 속한 달이 곧 "이번 달"
   * @return 등록된 대기 정보
   */
  @Transactional
  public SchoolCampWaitlistResponse register(Long studentUserId, LocalDateTime now) {
    YearMonth thisMonth = YearMonth.from(now);
    Optional<SchoolCampWaitlist> existing =
        waitlistRepository.findByStudentUserIdAndMonth(studentUserId, thisMonth.atDay(1));

    if (existing.isPresent()) {
      reactivate(existing.get(), now);
    } else {
      newWaitlist(studentUserId, thisMonth, now);
    }

    return new SchoolCampWaitlistResponse(thisMonth.format(YYYYMM_FORMATTER), now);
  }

  private void reactivate(SchoolCampWaitlist waitlist, LocalDateTime now) {
    if (waitlist.getCancelledAt() == null) {
      throw new CustomException(SchoolCampErrorCode.ALREADY_REGISTERED_WAITLIST);
    }
    waitlist.setCancelledAt(null);
    waitlist.setRegisteredAt(now);
    waitlistRepository.save(waitlist);
  }

  private void newWaitlist(Long studentUserId, YearMonth month, LocalDateTime now) {
    User student = userRepository.getReferenceById(studentUserId);
    SchoolCampWaitlist waitlist = SchoolCampWaitlist.builder()
        .studentUser(student)
        .month(month.atDay(1))
        .registeredAt(now)
        .build();
    try {
      waitlistRepository.save(waitlist);
    } catch (DataIntegrityViolationException e) {
      // 동시에 같은 학생이 같은 순간 두 번 등록을 시도하는 레이스(V14 유니크 제약 위반).
      throw new CustomException(SchoolCampErrorCode.ALREADY_REGISTERED_WAITLIST);
    }
  }

  /**
   * 이번 달 대기를 취소합니다.
   *
   * @param studentUserId 취소하는 학생 사용자 ID(Access Token에서 추출됨)
   * @param now           취소 시각으로 기록할 "지금"(KST)
   */
  @Transactional
  public void cancel(Long studentUserId, LocalDateTime now) {
    YearMonth thisMonth = YearMonth.from(now);
    SchoolCampWaitlist waitlist = waitlistRepository
        .findByStudentUserIdAndMonthAndCancelledAtIsNull(studentUserId, thisMonth.atDay(1))
        .orElseThrow(() -> new CustomException(SchoolCampErrorCode.WAITLIST_NOT_FOUND));

    waitlist.setCancelledAt(now);
    waitlistRepository.save(waitlist);
  }

  /**
   * 이번 달 대기 등록 상태를 조회합니다.
   *
   * @param studentUserId 조회하는 학생 사용자 ID(Access Token에서 추출됨)
   * @param now           기준 시각으로 쓸 "지금"(KST) — 이 시각이 속한 달이 곧 "이번 달"
   * @return 등록 상태
   */
  @Transactional(readOnly = true)
  public SchoolCampWaitlistStatusResponse getStatus(Long studentUserId, LocalDateTime now) {
    YearMonth thisMonth = YearMonth.from(now);
    return waitlistRepository
        .findByStudentUserIdAndMonthAndCancelledAtIsNull(studentUserId, thisMonth.atDay(1))
        .map(waitlist -> new SchoolCampWaitlistStatusResponse(true, waitlist.getRegisteredAt()))
        .orElseGet(() -> new SchoolCampWaitlistStatusResponse(false, null));
  }

  /**
   * 특정 달에 자리가 났음을 그 달의 유효한 대기자 전원에게 알립니다. 선착순 1명이 아니라
   * 전원에게 동시에 보낸다 — 실제 신청 성사는 세션 원자적 점유가 정리해준다.
   *
   * <p>{@code SchoolCampService.cancelApplication}(#70)에서만 호출한다.
   * {@code SchoolCampSessionClaimService.release}(claim 직후 검증 실패로 자기 자신의 점유를
   * 되돌리는 경로)에서는 호출하지 않는다 — 그 경로는 실제로 아무도 그 날짜를 "잃은" 적이
   * 없어, 호출하면 대기자에게 스팸성 알림이 간다.
   *
   * @param month 자리가 난 세션이 속한 달(취소 시점의 "이번 달"이 아니라 그 세션의 캠핑 날짜
   *              기준)
   */
  @Transactional
  public void notifyForMonth(YearMonth month) {
    List<SchoolCampWaitlist> waitlists =
        waitlistRepository.findByMonthAndCancelledAtIsNull(month.atDay(1));
    if (waitlists.isEmpty()) {
      return;
    }

    String title = "스쿨캠핑 자리가 났어요!";
    String body = "%d년 %d월 스쿨캠핑에 취소로 빈 자리가 생겼어요. 캘린더에서 확인하고 신청해보세요!"
        .formatted(month.getYear(), month.getMonthValue());
    waitlists.forEach(waitlist -> notificationService.send(
        waitlist.getStudentUser().getId(), title, body, NotificationType.SCHOOLCAMP));
  }
}
