package com.remake.gone.timetable.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.redis.RedisKeyType;
import com.remake.gone.common.redis.RedisRepository;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.enums.GbswType;
import com.remake.gone.gbsw.exception.GbswErrorCode;
import com.remake.gone.neis.NeisClient;
import com.remake.gone.neis.dto.NeisTimetableRow;
import com.remake.gone.timetable.dto.PeriodResponse;
import com.remake.gone.timetable.dto.TimetableResponse;
import com.remake.gone.timetable.exception.TimetableErrorCode;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link TimetableService}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class TimetableServiceTest {

  @Mock
  private NeisClient neisClient;

  @Mock
  private RedisRepository redisRepository;

  @Mock
  private UserRepository userRepository;

  @InjectMocks
  private TimetableService timetableService;

  private static final Long USER_ID = 1L;
  private static final LocalDate DATE = LocalDate.of(2026, 3, 23);

  private User userWith(Gbsw gbsw) {
    return User.builder().id(USER_ID).gbsw(gbsw).build();
  }

  private Gbsw studentGbsw(Integer grade, Integer classNo) {
    return Gbsw.builder().type(GbswType.STUDENT).grade(grade).classNo(classNo).build();
  }

  @Nested
  @DisplayName("getMyTimetable")
  class GetMyTimetable {

    @Test
    @DisplayName("캐시가 있으면 NEIS를 호출하지 않고 캐시된 값을 반환한다")
    void returnsCachedResponseWithoutCallingNeis() {
      given(userRepository.findById(USER_ID))
          .willReturn(Optional.of(userWith(studentGbsw(2, 1))));
      TimetableResponse cached = new TimetableResponse("20260323", 2, "1", List.of());
      given(redisRepository.find(RedisKeyType.TIMETABLE, "2:1:20260323", TimetableResponse.class))
          .willReturn(cached);

      TimetableResponse response = timetableService.getMyTimetable(USER_ID, DATE);

      assertThat(response).isEqualTo(cached);
      verify(neisClient, never()).fetch(any(), any(), any(), eq(NeisTimetableRow.class));
    }

    @Test
    @DisplayName("1·2학년은 학과 필터 없이 class_no만으로 조회한다")
    void queriesWithoutDepartmentForLowerGrades() {
      given(userRepository.findById(USER_ID))
          .willReturn(Optional.of(userWith(studentGbsw(2, 4))));
      given(redisRepository.find(RedisKeyType.TIMETABLE, "2:4:20260323", TimetableResponse.class))
          .willReturn(null);
      given(neisClient.fetch(
          eq("/hisTimetable"),
          argThat(params -> !params.containsKey("DDDEP_NM") && "4".equals(params.get("CLASS_NM"))),
          eq("hisTimetable"), eq(NeisTimetableRow.class)))
          .willReturn(List.of(new NeisTimetableRow("1", "자율·자치활동")));

      TimetableResponse response = timetableService.getMyTimetable(USER_ID, DATE);

      assertThat(response.grade()).isEqualTo(2);
      assertThat(response.classNm()).isEqualTo("4");
      assertThat(response.periods()).containsExactly(new PeriodResponse(1, "자율·자치활동"));
    }

    @Test
    @DisplayName("3학년은 하드코딩된 학과 매핑으로 DDDEP_NM/CLASS_NM을 채워 조회한다")
    void queriesWithDepartmentMappingForGradeThree() {
      given(userRepository.findById(USER_ID))
          .willReturn(Optional.of(userWith(studentGbsw(3, 3))));
      given(redisRepository.find(RedisKeyType.TIMETABLE, "3:3:20260323", TimetableResponse.class))
          .willReturn(null);
      given(neisClient.fetch(
          eq("/hisTimetable"),
          argThat(params -> "인공지능소프트웨어과".equals(params.get("DDDEP_NM"))
              && "1".equals(params.get("CLASS_NM"))),
          eq("hisTimetable"), eq(NeisTimetableRow.class)))
          .willReturn(List.of(new NeisTimetableRow("2", "빅데이터 처리시스템 개발")));

      TimetableResponse response = timetableService.getMyTimetable(USER_ID, DATE);

      assertThat(response.periods()).containsExactly(
          new PeriodResponse(2, "빅데이터 처리시스템 개발"));
    }

    @Test
    @DisplayName("교시 순서가 뒤섞여 와도 period 기준으로 정렬해 반환한다")
    void sortsPeriodsByPeriodNumber() {
      given(userRepository.findById(USER_ID))
          .willReturn(Optional.of(userWith(studentGbsw(1, 1))));
      given(redisRepository.find(RedisKeyType.TIMETABLE, "1:1:20260323", TimetableResponse.class))
          .willReturn(null);
      given(neisClient.fetch(eq("/hisTimetable"), ArgumentMatchers.<Map<String, String>>any(),
          eq("hisTimetable"), eq(NeisTimetableRow.class)))
          .willReturn(List.of(
              new NeisTimetableRow("3", "공통영어1"),
              new NeisTimetableRow("1", "자율·자치활동")));

      TimetableResponse response = timetableService.getMyTimetable(USER_ID, DATE);

      assertThat(response.periods()).extracting(PeriodResponse::period).containsExactly(1, 3);
    }

    @Test
    @DisplayName("3학년인데 학과 매핑에 없는 class_no면 500을 던진다")
    void throwsWhenGradeThreeClassNoNotInMapping() {
      given(userRepository.findById(USER_ID))
          .willReturn(Optional.of(userWith(studentGbsw(3, 5))));
      given(redisRepository.find(RedisKeyType.TIMETABLE, "3:5:20260323", TimetableResponse.class))
          .willReturn(null);

      assertThatThrownBy(() -> timetableService.getMyTimetable(USER_ID, DATE))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(TimetableErrorCode.UNKNOWN_CLASS_MAPPING);
    }

    @Test
    @DisplayName("학급 정보가 없는 계정(선생님 등)이면 400을 던진다")
    void throwsWhenNoClassAssigned() {
      Gbsw teacherGbsw = Gbsw.builder().type(GbswType.TEACHER).build();
      given(userRepository.findById(USER_ID))
          .willReturn(Optional.of(userWith(teacherGbsw)));

      assertThatThrownBy(() -> timetableService.getMyTimetable(USER_ID, DATE))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(GbswErrorCode.NO_CLASS_ASSIGNED);
    }

    @Test
    @DisplayName("존재하지 않는 사용자면 인증 만료로 취급해 401을 던진다")
    void throwsWhenUserNotFound() {
      given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

      assertThatThrownBy(() -> timetableService.getMyTimetable(USER_ID, DATE))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(CommonErrorCode.UNAUTHORIZED);
    }
  }
}
