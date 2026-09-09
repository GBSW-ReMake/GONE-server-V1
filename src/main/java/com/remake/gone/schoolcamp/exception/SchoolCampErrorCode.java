package com.remake.gone.schoolcamp.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * SchoolCamp(스쿨캠핑) 도메인 에러 코드.
 *
 * <p>코드 네이밍 규칙: {@code SCHOOLCAMP_NNN} (NNN은 3자리 순번). 번호는 마스터 기획서
 * (`docs/domain/schoolcamp/1_schoolcamp-domain.md`)의 전체 목록 중 실제로 구현되는 순서대로
 * 채운다 — #67에서는 005/006만 쓰였고, #68은 001/002/003/004/008을, #70은
 * 007/009/010/011을, #69는 012/013을, #83은 014/015를 쓴다.
 *
 * @see ErrorCode
 */
public enum SchoolCampErrorCode implements ErrorCode {

  /**
   * 존재하지 않는 세션에 신청을 시도했습니다(PK 단건 조회 실패).
   *
   * <p>{@code SchoolCampSessionRepository.claim}을 시도하기 전, {@code sessionId}로 세션이
   * 실제로 존재하는지 먼저 확인할 때 쓴다 — 존재하지 않는 PK로 {@code claim}을 호출하면
   * {@code WHERE} 절이 아예 매칭되지 않아 영향받은 행이 {@code 0}이 되는데, 이건
   * {@link #SESSION_ALREADY_TAKEN}(이미 점유됨)과 원인이 다르므로 별도 코드로 구분한다.
   */
  SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "SCHOOLCAMP_001", "해당 날짜에 스쿨캠핑 일정이 없습니다."),

  /**
   * 이미 다른 신청이 이 세션(날짜)을 점유했습니다(선착순 마감).
   *
   * <p>{@code SchoolCampSessionRepository.claim}이 영향받은 행 {@code 0}을 반환했을 때(=
   * {@code SchoolCampSessionClaimService.claim}이 {@code false}를 반환했을 때) 쓴다. 팀
   * 인원수와 무관하게 그 날짜를 통째로 못 쓴다는 뜻이라, 인원 초과 같은 형식 오류
   * ({@code SCHOOLCAMP_004}, 후속 커밋)와 구분한다.
   */
  SESSION_ALREADY_TAKEN(HttpStatus.CONFLICT, "SCHOOLCAMP_002", "이미 다른 팀이 신청한 날짜입니다."),

  /**
   * 대표 신청자 본인 또는 팀원 중 이번 달에 이미 참여(대표/팀원 구분 없이)한 사람이
   * 포함되어 있습니다.
   *
   * <p>응답의 {@code data}에 걸린 학생을 특정한
   * {@code SchoolCampParticipationConflictResponse}가 실린다({@code #81}).
   */
  ALREADY_PARTICIPATED_THIS_MONTH(
      HttpStatus.CONFLICT, "SCHOOLCAMP_003", "이번 달에 이미 참여한 사용자가 포함되어 있습니다."),

  /**
   * 담당 선생님 정보 형식이 올바르지 않습니다 — {@code teacherUserId}/{@code teacherName}
   * 중 정확히 하나가 아니거나, 지정한 {@code teacherUserId}가 {@code TEACHER} 역할이
   * 아니거나, 총원(대표 포함)이 8명을 초과한 경우 모두 이 코드를 쓴다.
   */
  INVALID_APPLICATION_FORMAT(HttpStatus.BAD_REQUEST, "SCHOOLCAMP_004", "신청 정보가 올바르지 않습니다."),

  /** 요청한 날짜 중 금/토/일이 포함되어 있습니다. */
  INVALID_CAMP_DATE(HttpStatus.BAD_REQUEST, "SCHOOLCAMP_005", "신청 가능한 날짜가 아닙니다."),

  /** 요청한 날짜 중 이미 세션이 등록된 날짜가 있습니다. */
  CAMP_DATE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "SCHOOLCAMP_006", "이미 등록된 날짜입니다."),

  /**
   * 본인 신청이 아닌 {@code SchoolCampApplication}을 취소/수정하려고 시도했습니다.
   *
   * <p>{@code #70}의 취소({@code DELETE .../applications/{id}})/수정
   * ({@code PATCH .../applications/{id}})가 소유권 확인에 실패했을 때 쓴다.
   */
  NOT_APPLICATION_OWNER(HttpStatus.FORBIDDEN, "SCHOOLCAMP_007", "본인 신청만 취소/수정할 수 있습니다."),

  /**
   * 팀원 정보가 올바르지 않습니다 — {@code additionalMembers}의 각 항목이
   * {@code studentUserId}/{@code guestName} 중 정확히 하나가 아니거나, 존재하지 않는
   * {@code studentUserId}이거나, 같은 {@code studentUserId}가 중복되거나, 대표 신청자
   * 본인이 포함된 경우 모두 이 코드를 쓴다.
   */
  INVALID_MEMBER_INFO(HttpStatus.BAD_REQUEST, "SCHOOLCAMP_008", "팀원 정보가 올바르지 않습니다."),

  /**
   * 캠핑 당일에는 취소할 수 없습니다({@code #70}).
   *
   * <p>{@code SchoolCampApplication.session.campDate}가 오늘이면 취소 요청을 거부할 때
   * 쓴다 — 담당 선생님/팀원 정보 수정({@code PATCH})은 이 제약과 무관하게 계속 허용한다.
   */
  CANCEL_NOT_ALLOWED_ON_CAMP_DAY(
      HttpStatus.BAD_REQUEST, "SCHOOLCAMP_009", "캠핑 당일에는 취소할 수 없습니다."),

  /**
   * 존재하지 않거나 이미 취소된 {@code SchoolCampApplication}입니다({@code #70}).
   *
   * <p>{@code SESSION_NOT_FOUND}(세션 대상 조회 실패)와 대상이 다르다 — 이 코드는
   * {@code SchoolCampApplication} 조회 실패에만 쓴다({@code #70}의 취소/수정 두 엔드포인트).
   * 마스터 기획서 초안은 {@code SCHOOLCAMP_001} 재사용을 제안했지만, 그 코드의 기본 메시지
   * ("해당 날짜에 스쿨캠핑 일정이 없습니다")가 신청 조회 실패 상황과 맞지 않아 별도 코드로
   * 분리했다.
   */
  APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "SCHOOLCAMP_010", "해당 신청을 찾을 수 없습니다."),

  /**
   * 같은 신청에 대한 동시 수정 요청이 충돌했습니다({@code #70} 코드 리뷰 대응).
   *
   * <p>{@code updateApplication}이 새 팀원을 삽입하는 순간, 동시에 처리된 다른 수정 요청이
   * 이미 같은 학생을 같은 신청에 팀원으로 추가해
   * {@code (application_id, student_user_id)} 유니크 제약(V13 마이그레이션)을 위반했을 때
   * 쓴다 — {@code DataIntegrityViolationException}을 그대로 COMMON_006/500으로 흘려보내지
   * 않고, 재시도하면 해결될 수 있는 충돌임을 명시적으로 알린다.
   */
  CONCURRENT_UPDATE_CONFLICT(
      HttpStatus.CONFLICT, "SCHOOLCAMP_011", "다른 요청과 동시에 처리되어 반영하지 못했습니다. 다시 시도해주세요."),

  /** {@code page}가 음수이거나 {@code size}가 1~100 범위 밖입니다({@code #69}). */
  INVALID_PAGE_PARAMS(
      HttpStatus.BAD_REQUEST, "SCHOOLCAMP_012",
      "페이지 파라미터가 올바르지 않습니다(page>=0, 1<=size<=100)."),

  /**
   * 본인이 참여자(대표 또는 팀원)가 아닌 {@code SchoolCampApplication}의 상세를
   * 조회하려고 시도했습니다({@code #69}).
   *
   * <p>{@link #NOT_APPLICATION_OWNER}(취소/수정 인가, 대표 신청자 전용)와 원인이 다르다 —
   * 이 코드는 읽기 전용 상세 조회에서, 대표든 팀원이든 참여자가 아니면 쓴다.
   */
  NOT_APPLICATION_PARTICIPANT(
      HttpStatus.FORBIDDEN, "SCHOOLCAMP_013", "본인이 참여한 신청만 조회할 수 있습니다."),

  /**
   * 이번 달에 이미 유효한 "자리나면 알림받기" 대기 등록이 있습니다({@code #83}).
   *
   * <p>{@code (student_user_id, month)} 유니크 제약(V14) 위반({@code DataIntegrityViolation
   * Exception}, 동시 등록 레이스)도 이 코드로 변환한다.
   */
  ALREADY_REGISTERED_WAITLIST(HttpStatus.CONFLICT, "SCHOOLCAMP_014", "이미 대기 등록되어 있습니다."),

  /**
   * 이번 달에 유효한 "자리나면 알림받기" 대기 등록을 찾을 수 없습니다({@code #83}).
   *
   * <p>등록된 적이 없거나 이미 취소된 상태에서 다시 취소를 시도할 때 쓴다.
   */
  WAITLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "SCHOOLCAMP_015", "유효한 대기 등록을 찾을 수 없습니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String defaultMessage;

  SchoolCampErrorCode(HttpStatus status, String code, String defaultMessage) {
    this.httpStatus = status;
    this.code = code;
    this.defaultMessage = defaultMessage;
  }

  @Override
  public HttpStatus getStatus() {
    return httpStatus;
  }

  @Override
  public String getCode() {
    return code;
  }

  @Override
  public String getDefaultMessage() {
    return defaultMessage;
  }
}
