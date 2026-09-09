package com.remake.gone.conduct.dto;

import com.remake.gone.conduct.entity.ConductRecord;
import com.remake.gone.conduct.enums.ConductStatus;
import com.remake.gone.conduct.enums.ConductType;
import java.time.LocalDateTime;

/**
 * 학생 본인 상/벌점 이력 응답 DTO.
 *
 * <p>{@link ConductRecordResponse}에서 학생 본인 정보({@code studentUserId},
 * {@code studentNickname})를 제외한 축약형이다 — 학생이 본인 이력을 조회할 때
 * 항목마다 자신의 정보를 반복 노출할 필요가 없다.
 *
 * @param id              기록 식별자
 * @param teacherUserId   부여 교사 사용자 ID
 * @param teacherNickname 부여 교사 별명
 * @param categoryId      카테고리 ID
 * @param categoryLabel   카테고리 표시명
 * @param type            상점({@code MERIT}) 또는 벌점({@code DEMERIT})
 * @param points          부여 시점 점수 스냅샷(부호 포함)
 * @param detail          추가 상세 사유(없으면 {@code null})
 * @param status          기록 상태
 * @param createdAt       부여 일시
 */
public record ConductStudentRecordResponse(
    Long id,
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
   * {@link ConductRecord} 엔티티를 학생 본인 조회용 응답 DTO로 변환합니다.
   *
   * @param record 변환 대상 기록 엔티티
   * @return 변환된 {@link ConductStudentRecordResponse}
   */
  public static ConductStudentRecordResponse from(ConductRecord record) {
    return new ConductStudentRecordResponse(
        record.getId(),
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
