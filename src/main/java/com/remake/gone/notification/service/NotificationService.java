package com.remake.gone.notification.service;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.notification.dto.NotificationResponse;
import com.remake.gone.notification.entity.Notification;
import com.remake.gone.notification.enums.NotificationType;
import com.remake.gone.notification.exception.NotificationErrorCode;
import com.remake.gone.notification.repository.NotificationRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 저장과 조회를 처리하는 서비스.
 *
 * <p>다른 도메인은 이 빈을 주입받아 {@link #send}만 호출하면 알림 저장이 끝난다. 저장 실패는
 * 그대로 예외로 전파한다 — 알림 저장은 이 모듈의 핵심 책임이라, 호출자(향후 {@code outing}/
 * {@code schoolcamp}) 트랜잭션과 함께 롤백되는 게 맞는 동작이다(마스터 기획서 "정책 가정"
 * 참고). FCM 발송(후속 이슈)은 이 이슈 범위 밖이다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

  private static final int MIN_PAGE_SIZE = 1;
  private static final int MAX_PAGE_SIZE = 100;
  private static final Sort LIST_QUERY_SORT = Sort.by(
      Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final NotificationRepository notificationRepository;
  private final UserRepository userRepository;

  /**
   * 알림을 저장합니다.
   *
   * <p>{@code title}/{@code body}는 각각 100자/500자를 넘으면 안 된다 — 초과 시 DB 컬럼
   * 길이 제약({@code notification} 테이블) 위반으로 저장이 실패하므로, 호출자가 값을 넘기기
   * 전에 그 범위 안인지 보장해야 한다.
   *
   * @param userId 수신자 사용자 ID
   * @param title  알림 제목(100자 이하)
   * @param body   알림 본문(500자 이하)
   * @param type   알림 분류(nullable)
   */
  public void send(Long userId, String title, String body, NotificationType type) {
    User user = userRepository.getReferenceById(userId);
    Notification notification = Notification.builder()
        .user(user)
        .title(title)
        .body(body)
        .type(type)
        .isRead(false)
        .build();
    notificationRepository.save(notification);
  }

  /**
   * 현재 사용자가 받은 알림을 최신순으로 페이지 조회합니다.
   *
   * @param userId 현재 인증 사용자 ID
   * @param page   페이지 번호(0부터 시작)
   * @param size   페이지 크기(1~100)
   * @return 페이지네이션된 알림 목록
   */
  @Transactional(readOnly = true)
  public PageResponse<NotificationResponse> getNotifications(Long userId, int page, int size) {
    validatePageParams(page, size);
    Pageable pageable = PageRequest.of(page, size, LIST_QUERY_SORT);
    Page<NotificationResponse> notifications = notificationRepository
        .findByUserId(userId, pageable)
        .map(NotificationResponse::from);
    return PageResponse.of(notifications);
  }

  /**
   * 현재 사용자가 받은 알림 하나를 읽음 상태로 변경합니다.
   *
   * <p>다른 사용자의 알림은 처리할 수 없으며, 이미 읽은 알림을 다시 요청해도 성공합니다.
   * 변경 감지는 트랜잭션이 끝날 때 읽음 상태를 저장합니다.
   *
   * @param userId 현재 인증 사용자 ID
   * @param notificationId 읽음 처리할 알림 ID
   */
  @Transactional
  public void markAsRead(Long userId, Long notificationId) {
    Notification notification = notificationRepository.findById(notificationId)
        .orElseThrow(() -> new CustomException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));

    if (!notification.getUser().getId().equals(userId)) {
      throw new CustomException(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED);
    }

    if (!notification.isRead()) {
      notification.markAsRead();
    }
  }

  /**
   * 현재 사용자가 받은 읽지 않은 알림을 모두 읽음 상태로 변경합니다.
   *
   * <p>벌크 갱신을 사용하므로, 읽지 않은 알림이 없어도 별도 조회 없이 성공합니다.
   *
   * <p>처리 건수는 HTTP 응답에 노출하지 않지만, 호출자가 벌크 갱신 결과를 확인할 수 있도록
   * 반환한다.
   *
   * @param userId 현재 인증 사용자 ID
   * @return 읽음 처리된 알림 수
   */
  @Transactional
  public int markAllAsRead(Long userId) {
    return notificationRepository.markAllAsReadByUserId(userId);
  }

  private void validatePageParams(int page, int size) {
    if (page < 0 || size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
      throw new CustomException(NotificationErrorCode.INVALID_PAGE_PARAMS);
    }
  }
}
