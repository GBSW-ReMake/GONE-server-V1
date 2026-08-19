package com.remake.gone.schoolcamp.exception;

import com.remake.gone.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * SchoolCamp(스쿨캠핑) 도메인 에러 코드.
 *
 * <p>코드 네이밍 규칙: {@code SCHOOLCAMP_NNN} (NNN은 3자리 순번). 번호는 마스터 기획서
 * (`docs/domain/schoolcamp/1_schoolcamp-domain.md`)의 전체 목록 중 실제로 구현되는 순서대로
 * 채운다 — #67에서는 005/006만 쓰였고, #68은 001/002/003/004/008을 쓴다. 이 중 001/002는
 * 이번 커밋(세션 원자적 점유 로직)에서, 003/004/008은 후속 커밋(무거운 검증 로직)에서
 * 추가한다.
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

  /** 요청한 날짜 중 금/토/일이 포함되어 있습니다. */
  INVALID_CAMP_DATE(HttpStatus.BAD_REQUEST, "SCHOOLCAMP_005", "신청 가능한 날짜가 아닙니다."),

  /** 요청한 날짜 중 이미 세션이 등록된 날짜가 있습니다. */
  CAMP_DATE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "SCHOOLCAMP_006", "이미 등록된 날짜입니다.");

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
