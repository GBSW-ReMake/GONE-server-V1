package com.remake.gone.schoolcamp.scheduler;

import static org.mockito.Mockito.verify;

import com.remake.gone.schoolcamp.service.SchoolCampService;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link SchoolCampReminderScheduler}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class SchoolCampReminderSchedulerTest {

  @Mock
  private SchoolCampService schoolCampService;

  @InjectMocks
  private SchoolCampReminderScheduler scheduler;

  @Test
  @DisplayName("KST 기준 오늘 날짜를 그대로 sendOutingReminders에 위임한다")
  void delegatesTodayToSendOutingReminders() {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    scheduler.remindOutingForTodayCampers();

    verify(schoolCampService).sendOutingReminders(today);
  }
}
