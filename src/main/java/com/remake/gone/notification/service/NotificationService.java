package com.remake.gone.notification.service;

import com.remake.gone.notification.entity.Notification;
import com.remake.gone.notification.repository.NotificationRepository;
import com.remake.gone.user.entity.User;
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

  /**
   * 알림을 저장합니다.
   *
   * @param userId 수신자 사용자 ID
   * @param title  알림 제목
   * @param body   알림 본문
   * @param type   발송 도메인이 붙이는 분류 태그(nullable)
   */
  public void send(Long userId, String title, String body, String type) {
    Notification notification = Notification.builder()
        .user(User.builder().id(userId).build())
        .title(title)
        .body(body)
        .type(type)
        .isRead(false)
        .build();
    notificationRepository.save(notification);
  }
}
