package com.remake.gone.conduct.dto;

import com.remake.gone.conduct.entity.ConductRecord;
import com.remake.gone.conduct.enums.ConductStatus;
import com.remake.gone.conduct.enums.ConductType;
import java.time.LocalDateTime;

/**
 * 상/벌점 기록 응답 DTO.
 *
 * @param id               기록 식별자
 * @param studentUserId    대상 학생 사용자 ID
 * @param studentNickname  대상 학생 별명
 * @param teacherUserId    부여 교사 사용자 ID
 * @param teacherNickname  부여 교사 별명
 * @param categoryId       카테고리 ID
 * @param categoryLabel    카테고리 표시명
 * @param type             상점({@code MERIT}) 또는 벌점({@code DEMERIT})
 * @param points           부여 시점 점수 스냅샷(부호 포함)
 * @param detail           추가 상세 사유(없으면 {@code null})
 * @param status           기록 상태
 * @param createdAt        부여 일시
 */
public record ConductRecordResponse(
    Long id,
    Long studentUserId,
    String studentNickname,
    Long teacherUserId,
    String teacherNickname,
    Long categoryId,
    String categoryLabel,
    ConductType type,
    int points,
    String detail,
    ConductStatus status,
    LocalDateTime createdAt
) {

  /**
   * {@link ConductRecord} 엔티티를 응답 DTO로 변환합니다.
   *
   * @param record 변환 대상 기록 엔티티
   * @return 변환된 {@link ConductRecordResponse}
   */
  public static ConductRecordResponse from(ConductRecord record) {
    return new ConductRecordResponse(
        record.getId(),
        record.getStudent().getId(),
        record.getStudent().getName(),
        record.getTeacher().getId(),
        record.getTeacher().getName(),
        record.getCategory().getId(),
        record.getCategory().getLabel(),
        record.getType(),
        record.getPoints(),
        record.getDetail(),
        record.getStatus(),
        record.getCreatedAt()
    );
  }
}
