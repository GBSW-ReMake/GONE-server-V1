package com.remake.gone.schoolcamp.scheduler;

import com.remake.gone.schoolcamp.service.SchoolCampService;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 08:30(KST)에 오늘 스쿨캠핑 참여 학생 중 점심 외출증 미신청자에게 리마인더 알림을
 * 보내는 백그라운드 스케줄러({@code #71}).
 *
 * <p>{@link com.remake.gone.outing.scheduler.OutingMissedScheduler}(#42)와 같은 얇은
 * 컴포넌트다 — 시각만 구해 {@link SchoolCampService}에 위임하고, 실제 로직은 단위 테스트에서
 * 임의의 날짜를 주입할 수 있도록 서비스 쪽에 둔다. "하루 1번, 정확히 08:30에"라는 고정 시각
 * 요구사항이라 {@code fixedDelay}(주기적 폴링)가 아니라 {@code cron}을 쓴다(이 프로젝트에서
 * cron 기반 {@code @Scheduled}를 쓰는 첫 사례).
 */
@Component
@RequiredArgsConstructor
public class SchoolCampReminderScheduler {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final SchoolCampService schoolCampService;

  /** 매일 08:30(KST)에 1회 실행됩니다. */
  @Scheduled(cron = "0 30 8 * * *", zone = "Asia/Seoul")
  public void remindOutingForTodayCampers() {
    schoolCampService.sendOutingReminders(LocalDate.now(KST));
  }
}
