package com.remake.gone.notification.service;

import com.remake.gone.notification.entity.Notification;
import com.remake.gone.notification.enums.NotificationType;
import com.remake.gone.notification.repository.NotificationRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 알림 저장을 전담하는 공통 발송 모듈.
 *
 * <p>다른 도메인은 이 빈을 주입받아 {@link #send}만 호출하면 알림 저장이 끝난다. 저장 실패는
 * 그대로 예외로 전파한다 — 알림 저장은 이 모듈의 핵심 책임이라, 호출자(향후 {@code outing}/
 * {@code schoolcamp}) 트랜잭션과 함께 롤백되는 게 맞는 동작이다(마스터 기획서 "정책 가정"
 * 참고). FCM 발송(2단계), 조회/읽음 처리 API(후속 이슈)는 이 이슈 범위 밖이다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

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
}
