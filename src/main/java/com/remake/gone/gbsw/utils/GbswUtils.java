package com.remake.gone.gbsw.utils;

import com.remake.gone.gbsw.entity.Gbsw;

/**
 * {@link Gbsw} 관련 공용 포맷 계산 유틸.
 */
public final class GbswUtils {

  private GbswUtils() {}

  /**
   * 학생의 학번(학년+반+번호)을 고정 4자리 문자열로 반환한다. 반이 10개 미만이라는 전제로
   * 학년 1자리 + 반 1자리 + 번호 2자리(0채움)를 그대로 이어붙인다 — 반이 10개 이상으로
   * 늘어나면 이 포맷을 바꿔야 한다({@code AuthService.generateStudentDefaultName}과 근거 공유).
   *
   * @param gbsw 학번을 계산할 학생 학적(반드시 {@code GbswType.STUDENT})
   * @return 고정 4자리 학번 문자열(예: 3학년 2반 18번 → {@code "3218"})
   */
  public static String studentNumber(Gbsw gbsw) {
    return "%d%d%02d".formatted(gbsw.getGrade(), gbsw.getClassNo(), gbsw.getNumber());
  }
}
