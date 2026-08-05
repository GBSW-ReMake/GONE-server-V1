package com.remake.gone.timetable.service;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.redis.RedisKeyType;
import com.remake.gone.common.redis.RedisRepository;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.exception.GbswErrorCode;
import com.remake.gone.neis.NeisClient;
import com.remake.gone.neis.dto.NeisTimetableRow;
import com.remake.gone.timetable.dto.PeriodResponse;
import com.remake.gone.timetable.dto.TimetableResponse;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * NEIS 시간표를 조회하는 서비스.
 *
 * <p>클라이언트가 학년/반을 보내지 않고, 로그인한 본인의 {@link Gbsw} 레코드에서 조회한다.
 */
@Service
@RequiredArgsConstructor
public class TimetableService {

  private static final DateTimeFormatter YMD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

  /**
   * 3학년 학과별 NEIS 조회값 매핑.
   *
   * <p>경북소프트웨어마이스터고는 3학년부터 학과(소프트웨어개발과 2개 반, 인공지능소프트웨어과
   * 1개 반, 게임개발과 1개 반)로 나뉘는데, NEIS가 학과 안에서 반 번호를 다시 1부터 매겨
   * {@code class_no}(학교 전체 기준 1~4반)만으로는 정확한 반을 조회할 수 없다(기획서 참고).
   * 이 매핑은 "현재 3학년 세대"의 실제 학과 배정 결과에 묶여 있는 하드코딩이라, 학년이 바뀌거나
   * 학과별 반 개수가 달라지면 깨진다 — 재검증 필요 시점은 이슈 #27로 추적한다.
   */
  private static final Map<Integer, DepartmentMapping> GRADE_3_DEPARTMENT_MAP = Map.of(
      1, new DepartmentMapping("소프트웨어개발과", 1),
      2, new DepartmentMapping("소프트웨어개발과", 2),
      3, new DepartmentMapping("인공지능소프트웨어과", 1),
      4, new DepartmentMapping("게임개발과", 1)
  );

  private final NeisClient neisClient;
  private final RedisRepository redisRepository;
  private final UserRepository userRepository;

  /**
   * 로그인한 본인 학급의 시간표를 조회한다.
   *
   * @param userId 조회할 사용자 ID (Access Token에서 추출됨)
   * @param date   조회할 날짜
   * @return 시간표 조회 응답. 데이터가 없는 날(공강/방학 등)은 빈 리스트를 담아 정상 응답한다
   */
  public TimetableResponse getMyTimetable(Long userId, LocalDate date) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new CustomException(CommonErrorCode.UNAUTHORIZED));
    Gbsw gbsw = user.getGbsw();
    Integer grade = gbsw.getGrade();
    Integer classNo = gbsw.getClassNo();
    if (grade == null || classNo == null) {
      throw new CustomException(GbswErrorCode.NO_CLASS_ASSIGNED);
    }

    String ymd = date.format(YMD_FORMATTER);
    String cacheKey = grade + ":" + classNo + ":" + ymd;
    TimetableResponse cached =
        redisRepository.find(RedisKeyType.TIMETABLE, cacheKey, TimetableResponse.class);
    if (cached != null) {
      return cached;
    }

    List<PeriodResponse> periods = fetchRows(grade, classNo, ymd).stream()
        .map(row -> new PeriodResponse(Integer.parseInt(row.period()), row.subject()))
        .sorted(Comparator.comparingInt(PeriodResponse::period))
        .toList();
    TimetableResponse response =
        new TimetableResponse(ymd, grade, String.valueOf(classNo), periods);
    redisRepository.save(RedisKeyType.TIMETABLE, cacheKey, response);
    return response;
  }

  private List<NeisTimetableRow> fetchRows(int grade, int classNo, String ymd) {
    Map<String, String> params = new HashMap<>();
    params.put("GRADE", String.valueOf(grade));
    params.put("ALL_TI_YMD", ymd);

    if (grade == 3) {
      DepartmentMapping mapping = GRADE_3_DEPARTMENT_MAP.get(classNo);
      params.put("DDDEP_NM", mapping.department());
      params.put("CLASS_NM", String.valueOf(mapping.neisClassNo()));
    } else {
      params.put("CLASS_NM", String.valueOf(classNo));
    }

    return neisClient.fetch("/hisTimetable", params, "hisTimetable", NeisTimetableRow.class);
  }

  private record DepartmentMapping(String department, int neisClassNo) {}
}
