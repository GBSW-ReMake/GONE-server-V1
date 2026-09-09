package com.remake.gone.conduct.dto;

import com.remake.gone.conduct.entity.ConductRequest;
import com.remake.gone.conduct.enums.ConductRequestStatus;
import com.remake.gone.conduct.enums.ConductType;
import java.time.LocalDateTime;

/**
 * 상/벌점 요청 응답 DTO.
 *
 * @param id               요청 식별자
 * @param requesterUserId  요청자(선도부) 사용자 ID
 * @param requesterNickname 요청자 별명
 * @param studentUserId    대상 학생 사용자 ID
 * @param studentNickname  대상 학생 별명
 * @param assigneeUserId   처리 담당자 사용자 ID
 * @param assigneeNickname 처리 담당자 별명
 * @param categoryId       카테고리 ID
 * @param categoryLabel    카테고리 표시명
 * @param type             상점({@code MERIT}) 또는 벌점({@code DEMERIT})
 * @param detail           추가 상세 사유(없으면 {@code null})
 * @param status           요청 상태
 * @param createdAt        요청 등록 일시
 */
public record ConductRequestResponse(
    Long id,
    Long requesterUserId,
    String requesterNickname,
    Long studentUserId,
    String studentNickname,
    Long assigneeUserId,
    String assigneeNickname,
    Long categoryId,
    String categoryLabel,
    ConductType type,
    String detail,
    ConductRequestStatus status,
    LocalDateTime createdAt
) {

  /**
   * {@link ConductRequest} 엔티티를 응답 DTO로 변환합니다.
   *
   * @param request 변환 대상 요청 엔티티
   * @return 변환된 {@link ConductRequestResponse}
   */
  public static ConductRequestResponse from(ConductRequest request) {
    return new ConductRequestResponse(
        request.getId(),
        request.getRequester().getId(),
        request.getRequester().getName(),
        request.getStudent().getId(),
        request.getStudent().getName(),
        request.getAssignee().getId(),
        request.getAssignee().getName(),
        request.getCategory().getId(),
        request.getCategory().getLabel(),
        request.getCategory().getType(),
        request.getDetail(),
        request.getStatus(),
        request.getCreatedAt()
    );
  }
}
