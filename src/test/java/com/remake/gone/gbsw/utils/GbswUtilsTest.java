package com.remake.gone.gbsw.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.enums.GbswType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GbswUtils}에 대한 단위 테스트.
 */
class GbswUtilsTest {

  private static Gbsw studentGbsw(int grade, int classNo, int number) {
    return Gbsw.builder()
        .type(GbswType.STUDENT)
        .grade(grade)
        .classNo(classNo)
        .number(number)
        .name("정문경")
        .build();
  }

  @Test
  @DisplayName("학년+반+번호를 그대로 이어붙인 고정 4자리 문자열을 반환한다")
  void returnsFixedFourDigitStudentNumber() {
    String result = GbswUtils.studentNumber(studentGbsw(3, 2, 18));

    assertThat(result).isEqualTo("3218");
  }

  @Test
  @DisplayName("번호가 한 자리면 0을 채워 두 자리로 만든다")
  void padsSingleDigitNumber() {
    String result = GbswUtils.studentNumber(studentGbsw(1, 1, 5));

    assertThat(result).isEqualTo("1105");
  }
}
