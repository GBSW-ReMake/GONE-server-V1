package com.remake.gone.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.security.JwtProvider;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.enums.GbswType;
import com.remake.gone.gbsw.repository.GbswRepository;
import com.remake.gone.notification.entity.Notification;
import com.remake.gone.notification.repository.NotificationRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 알림 읽음 처리의 실제 DB·보안 필터 통합 테스트.
 *
 * <p>서비스 단위 테스트만으로는 LAZY 수신자 조회·벌크 갱신·트랜잭션 종료 시점의 변경 감지를
 * 검증할 수 없다. 이 테스트는 실제 사용자·알림을 저장한 뒤 HTTP 요청을 보내 {@code isRead}
 * 변경과 소유권 검증을 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationReadIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtProvider jwtProvider;

  @Autowired
  private NotificationRepository notificationRepository;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private GbswRepository gbswRepository;

  private final List<Notification> notifications = new ArrayList<>();
  private User owner;
  private User otherUser;

  @AfterEach
  void tearDown() {
    for (Notification notification : notifications) {
      if (notification.getId() != null) {
        notificationRepository.deleteById(notification.getId());
      }
    }
    deleteUser(otherUser);
    deleteUser(owner);
  }

  @Test
  @DisplayName("본인 알림을 읽음 처리하면 실제 DB의 isRead가 true로 저장된다")
  void persistsReadStatusForOwner() throws Exception {
    owner = saveUser();
    Notification notification = saveNotification(owner, false);

    mockMvc.perform(patch("/api/v1/notifications/{id}/read", notification.getId())
            .header("Authorization", "Bearer " + accessToken(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    Notification persisted = notificationRepository.findById(notification.getId()).orElseThrow();
    assertThat(persisted.isRead()).isTrue();
  }

  @Test
  @DisplayName("이미 읽은 본인 알림을 다시 읽음 처리해도 200을 반환한다")
  void returns200ForAlreadyReadNotification() throws Exception {
    owner = saveUser();
    Notification notification = saveNotification(owner, true);

    mockMvc.perform(patch("/api/v1/notifications/{id}/read", notification.getId())
            .header("Authorization", "Bearer " + accessToken(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    Notification persisted = notificationRepository.findById(notification.getId()).orElseThrow();
    assertThat(persisted.isRead()).isTrue();
  }

  @Test
  @DisplayName("다른 사용자가 알림을 읽음 처리하면 403 NOTIFICATION_002를 반환한다")
  void returns403ForOtherUsersNotification() throws Exception {
    owner = saveUser();
    otherUser = saveUser();
    Notification notification = saveNotification(owner, false);

    mockMvc.perform(patch("/api/v1/notifications/{id}/read", notification.getId())
            .header("Authorization", "Bearer " + accessToken(otherUser)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOTIFICATION_002"));

    Notification persisted = notificationRepository.findById(notification.getId()).orElseThrow();
    assertThat(persisted.isRead()).isFalse();
  }

  @Test
  @DisplayName("전체 읽음 처리는 본인의 읽지 않은 알림만 실제 DB에서 갱신한다")
  void marksOnlyCurrentUsersUnreadNotificationsAsRead() throws Exception {
    owner = saveUser();
    otherUser = saveUser();
    Notification firstOwnerNotification = saveNotification(owner, false);
    Notification secondOwnerNotification = saveNotification(owner, false);
    Notification otherUsersNotification = saveNotification(otherUser, false);

    mockMvc.perform(patch("/api/v1/notifications/read-all")
            .header("Authorization", "Bearer " + accessToken(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    assertThat(notificationRepository.findById(firstOwnerNotification.getId())
        .orElseThrow().isRead())
        .isTrue();
    assertThat(notificationRepository.findById(secondOwnerNotification.getId())
        .orElseThrow().isRead())
        .isTrue();
    assertThat(notificationRepository.findById(otherUsersNotification.getId())
        .orElseThrow().isRead())
        .isFalse();
  }

  @Test
  @DisplayName("읽지 않은 알림이 없어도 전체 읽음 처리는 200을 반환한다")
  void returns200WhenThereAreNoUnreadNotifications() throws Exception {
    owner = saveUser();
    saveNotification(owner, true);

    mockMvc.perform(patch("/api/v1/notifications/read-all")
            .header("Authorization", "Bearer " + accessToken(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  private void deleteUser(User user) {
    if (user != null && user.getId() != null) {
      userRepository.deleteById(user.getId());
      gbswRepository.deleteById(user.getGbsw().getId());
    }
  }

  private User saveUser() {
    int suffix = ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
    Gbsw gbsw = gbswRepository.save(Gbsw.builder()
        .type(GbswType.STUDENT)
        .name("알림테스트학생" + suffix)
        .phoneNumber("010" + suffix)
        .grade(9)
        .classNo(9)
        .number(suffix)
        .build());
    return userRepository.save(User.builder()
        .gbsw(gbsw)
        .loginId("notification" + suffix)
        .passwordHash("hash")
        .name("알림테스트계정" + suffix)
        .phoneNumber("011" + suffix)
        .build());
  }

  private Notification saveNotification(User user, boolean isRead) {
    Notification notification = notificationRepository.save(Notification.builder()
        .user(user)
        .title("알림 읽음 처리 통합 테스트")
        .body("실제 DB의 읽음 상태를 확인합니다.")
        .isRead(isRead)
        .build());
    notifications.add(notification);
    return notification;
  }

  private String accessToken(User user) {
    return jwtProvider.createAccessToken(user.getId(), Set.of("STUDENT"));
  }
}
