package com.remake.gone.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remake.gone.common.security.JwtProvider;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.enums.GbswType;
import com.remake.gone.gbsw.repository.GbswRepository;
import com.remake.gone.notification.entity.DeviceToken;
import com.remake.gone.notification.repository.DeviceTokenRepository;
import com.remake.gone.notification.service.NotificationService;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * FCM 디바이스 토큰 등록·갱신·삭제의 실제 DB·보안 필터 통합 테스트.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationDeviceTokenIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtProvider jwtProvider;

  @Autowired
  private DeviceTokenRepository deviceTokenRepository;

  @Autowired
  private NotificationService notificationService;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private GbswRepository gbswRepository;

  private final List<User> users = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (User user : users) {
      deviceTokenRepository.findByUserId(user.getId())
          .ifPresent(deviceTokenRepository::delete);
      userRepository.deleteById(user.getId());
      gbswRepository.deleteById(user.getGbsw().getId());
    }
  }

  @Test
  @DisplayName("같은 사용자가 토큰을 다시 등록하면 행을 추가하지 않고 최신 값으로 갱신한다")
  void updatesExistingTokenWithoutCreatingDuplicateRow() throws Exception {
    User user = saveUser();

    registerToken(user, "first-fcm-token")
        .andExpect(status().isOk());
    DeviceToken firstSavedToken = deviceTokenRepository.findByUserId(user.getId()).orElseThrow();

    registerToken(user, "second-fcm-token")
        .andExpect(status().isOk());
    DeviceToken updatedToken = deviceTokenRepository.findByUserId(user.getId()).orElseThrow();

    assertThat(updatedToken.getId()).isEqualTo(firstSavedToken.getId());
    assertThat(updatedToken.getFcmToken()).isEqualTo("second-fcm-token");
    assertThat(updatedToken.getUpdatedAt()).isNotNull();
  }

  @Test
  @DisplayName("255자인 FCM 토큰을 실제 DB에 저장한다")
  void persistsMaximumLengthToken() throws Exception {
    User user = saveUser();
    String maximumLengthToken = "a".repeat(255);

    registerToken(user, maximumLengthToken)
        .andExpect(status().isOk());

    DeviceToken savedToken = deviceTokenRepository.findByUserId(user.getId()).orElseThrow();
    assertThat(savedToken.getFcmToken()).hasSize(255).isEqualTo(maximumLengthToken);
  }

  @Test
  @DisplayName("같은 사용자의 동시 토큰 등록은 UNIQUE 충돌 없이 직렬 처리된다")
  void serializesConcurrentRegistrationsForSameUser() throws Exception {
    User user = saveUser();
    CountDownLatch startSignal = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<?> firstRequest = executor.submit(() -> {
        await(startSignal);
        notificationService.registerDeviceToken(user.getId(), "first-concurrent-token");
      });
      Future<?> secondRequest = executor.submit(() -> {
        await(startSignal);
        notificationService.registerDeviceToken(user.getId(), "second-concurrent-token");
      });

      startSignal.countDown();
      firstRequest.get(5, TimeUnit.SECONDS);
      secondRequest.get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    DeviceToken savedToken = deviceTokenRepository.findByUserId(user.getId()).orElseThrow();
    assertThat(savedToken.getFcmToken())
        .isIn("first-concurrent-token", "second-concurrent-token");
  }

  @Test
  @DisplayName("Access Token의 사용자가 삭제됐으면 토큰 등록은 401 COMMON_002를 반환한다")
  void returns401ForStaleAccessToken() throws Exception {
    String staleToken = jwtProvider.createAccessToken(Long.MAX_VALUE, Set.of("STUDENT"));

    mockMvc.perform(put("/api/v1/notifications/device-token")
            .header("Authorization", "Bearer " + staleToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fcmToken\":\"valid-fcm-token\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("COMMON_002"));
  }

  @Test
  @DisplayName("한 사용자의 토큰을 삭제해도 다른 사용자의 토큰은 유지된다")
  void deletesOnlyCurrentUsersToken() throws Exception {
    User owner = saveUser();
    User otherUser = saveUser();
    registerToken(owner, "owner-fcm-token").andExpect(status().isOk());
    registerToken(otherUser, "other-fcm-token").andExpect(status().isOk());

    mockMvc.perform(delete("/api/v1/notifications/device-token")
            .header("Authorization", "Bearer " + accessToken(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    assertThat(deviceTokenRepository.findByUserId(owner.getId())).isEmpty();
    assertThat(deviceTokenRepository.findByUserId(otherUser.getId())).isPresent();
  }

  @Test
  @DisplayName("등록된 토큰이 없어도 삭제 요청은 200을 반환한다")
  void returns200WhenTokenDoesNotExist() throws Exception {
    User user = saveUser();

    mockMvc.perform(delete("/api/v1/notifications/device-token")
            .header("Authorization", "Bearer " + accessToken(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.message").value("디바이스 토큰이 삭제되었습니다."));

    assertThat(deviceTokenRepository.findByUserId(user.getId())).isEmpty();
  }

  private ResultActions registerToken(User user, String fcmToken) throws Exception {
    return mockMvc.perform(put("/api/v1/notifications/device-token")
        .header("Authorization", "Bearer " + accessToken(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"fcmToken\":\"" + fcmToken + "\"}"));
  }

  private User saveUser() {
    int suffix = ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
    Gbsw gbsw = gbswRepository.save(Gbsw.builder()
        .type(GbswType.STUDENT)
        .name("토큰테스트학생" + suffix)
        .phoneNumber("010" + suffix)
        .grade(9)
        .classNo(9)
        .number(suffix)
        .build());
    User user = userRepository.save(User.builder()
        .gbsw(gbsw)
        .loginId("device" + suffix)
        .passwordHash("hash")
        .name("토큰테스트계정" + suffix)
        .phoneNumber("011" + suffix)
        .build());
    users.add(user);
    return user;
  }

  private String accessToken(User user) {
    return jwtProvider.createAccessToken(user.getId(), Set.of("STUDENT"));
  }

  private void await(CountDownLatch startSignal) {
    try {
      startSignal.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("동시성 테스트 대기 중 인터럽트가 발생했습니다.", e);
    }
  }
}
