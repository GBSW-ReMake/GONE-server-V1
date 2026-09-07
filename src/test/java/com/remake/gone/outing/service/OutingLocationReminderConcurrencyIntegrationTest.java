package com.remake.gone.outing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.remake.gone.common.redis.RedisKeyType;
import com.remake.gone.common.redis.RedisRepository;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.enums.GbswType;
import com.remake.gone.gbsw.repository.GbswRepository;
import com.remake.gone.notification.repository.NotificationRepository;
import com.remake.gone.outing.dto.OutingLocationRequest;
import com.remake.gone.outing.entity.Outing;
import com.remake.gone.outing.enums.OutingStatus;
import com.remake.gone.outing.enums.OutingTimeSlot;
import com.remake.gone.outing.repository.OutingLocationRepository;
import com.remake.gone.outing.repository.OutingRepository;
import com.remake.gone.outing.utils.OutingCodeGenerator;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

/**
 * {@link OutingService#recordLocationPing}의 위치 기반 리마인더 쿨다운 동시성 통합
 * 테스트(#99 CodeRabbit 지적 A). {@code OutingServiceTest}의 Mockito 단위 테스트는
 * {@code RedisRepository}를 mock하므로 실제 Redis {@code SETNX}가 동시 요청 중 정확히
 * 하나만 통과시키는지 검증하지 못한다 — 순차 curl 호출로 QA를 진행했던 {@code
 * 99-outing-return-reminder-QA.md} 케이스 6/7도 같은 이유로 이 경합 구간을 검증하지
 * 못한다는 지적을 받았다. 이 테스트는 실 Redis(및 실 DB)에 붙어 진짜 동시 요청으로 그
 * 경합 구간을 통과시킨다.
 *
 * <p>{@code application.yml}이 기본 활성화하는 {@code dev} 프로필의 학교 좌표 더미값
 * (0.0/0.0, 반경 200m)을 그대로 "학교 반경 안"으로 사용한다({@code 43}/{@code 99} QA와
 * 동일한 관례).
 */
@SpringBootTest
class OutingLocationReminderConcurrencyIntegrationTest {

  @Autowired
  private OutingService outingService;

  @Autowired
  private OutingRepository outingRepository;

  @Autowired
  private OutingLocationRepository outingLocationRepository;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private GbswRepository gbswRepository;

  @Autowired
  private NotificationRepository notificationRepository;

  @Autowired
  private RedisRepository redisRepository;

  private Outing outing;
  private User student;
  private User teacher;

  @AfterEach
  void tearDown() {
    if (outing != null && outing.getId() != null) {
      redisRepository.delete(
          RedisKeyType.OUTING_LOCATION_REMINDER_COOLDOWN, outing.getId().toString());
      // 동시 핑 20건이 각각 OutingLocation 행을 하나씩 남기므로(#97, 스로틀과 무관하게
      // 항상 저장됨), outing을 지우기 전에 먼저 지워야 FK 제약을 위반하지 않는다.
      outingLocationRepository.deleteAll(
          outingLocationRepository.findByOutingIdOrderByRecordedAtAscIdAsc(outing.getId()));
      outingRepository.deleteById(outing.getId());
    }
    deleteUser(student);
    deleteUser(teacher);
  }

  private void deleteUser(User user) {
    if (user != null && user.getId() != null) {
      // 이 테스트가 실제로 저장시킨 알림(학생에게 보낸 "도착 확인" 알림)이 남아있으면
      // user 삭제가 FK 제약에 걸린다.
      notificationRepository.findByUserId(user.getId(), PageRequest.of(0, 50))
          .forEach(notificationRepository::delete);
      userRepository.deleteById(user.getId());
      gbswRepository.deleteById(user.getGbsw().getId());
    }
  }

  private static int uniqueSuffix() {
    return ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
  }

  private User saveStudent() {
    int suffix = uniqueSuffix();
    Gbsw gbsw = gbswRepository.save(Gbsw.builder()
        .type(GbswType.STUDENT)
        .name("학생" + suffix)
        .phoneNumber("010" + suffix)
        .grade(9)
        .classNo(9)
        .number(suffix)
        .build());
    return userRepository.save(User.builder()
        .gbsw(gbsw)
        .loginId("stu" + suffix)
        .passwordHash("hash")
        .name("학생계정" + suffix)
        .phoneNumber("011" + suffix)
        .build());
  }

  private User saveTeacher() {
    int suffix = uniqueSuffix();
    Gbsw gbsw = gbswRepository.save(Gbsw.builder()
        .type(GbswType.TEACHER)
        .name("선생님" + suffix)
        .phoneNumber("012" + suffix)
        .build());
    return userRepository.save(User.builder()
        .gbsw(gbsw)
        .loginId("tch" + suffix)
        .passwordHash("hash")
        .name("선생님계정" + suffix)
        .phoneNumber("013" + suffix)
        .build());
  }

  @Test
  @DisplayName("같은 외출증에 학교 반경 안 위치 핑을 동시에 여러 번 보내도 "
      + "도착 확인 알림은 정확히 한 건만 발송된다")
  void onlyOneNotificationSentWhenConcurrentPingsWithinSchoolRadius() throws Exception {
    student = saveStudent();
    teacher = saveTeacher();
    outing = outingRepository.save(Outing.builder()
        .code(OutingCodeGenerator.generate())
        .student(student)
        .teacher(teacher)
        .reason("위치 리마인더 동시성 통합 테스트")
        .outingDate(LocalDate.now().plusDays(30))
        .timeSlot(OutingTimeSlot.CUSTOM)
        .startTime(LocalTime.of(9, 0))
        .endTime(LocalTime.of(10, 0))
        .status(OutingStatus.DEPARTED)
        .build());
    LocalDateTime now = LocalDateTime.now();
    OutingLocationRequest request = new OutingLocationRequest(0.0, 0.0);

    int concurrentRequests = 20;
    ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
    CountDownLatch readyLatch = new CountDownLatch(concurrentRequests);
    CountDownLatch startLatch = new CountDownLatch(1);

    List<Callable<Void>> tasks = IntStream.range(0, concurrentRequests)
        .<Callable<Void>>mapToObj(i -> () -> {
          readyLatch.countDown();
          startLatch.await();
          outingService.recordLocationPing(
              student.getId(), outing.getCode(), request, now.plusNanos(i));
          return null;
        })
        .toList();

    try {
      List<Future<Void>> futures = tasks.stream().map(executor::submit).toList();
      readyLatch.await(5, TimeUnit.SECONDS);
      startLatch.countDown();
      for (Future<Void> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdown();
    }

    long notificationCount = notificationRepository
        .findByUserId(student.getId(), PageRequest.of(0, 50))
        .getTotalElements();
    assertThat(notificationCount).isEqualTo(1);
  }
}
