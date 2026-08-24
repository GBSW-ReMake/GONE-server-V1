package com.remake.gone.conduct.entity;

import com.remake.gone.conduct.enums.ConductStatus;
import com.remake.gone.conduct.enums.ConductType;
import com.remake.gone.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 교사가 학생에게 부여한 상/벌점 기록 엔티티.
 *
 * <p>{@code db/migration/V16__add_conduct_record.sql}의 {@code conduct_record} 테이블에 대응한다.
 * {@link #type}과 {@link #points}는 부여 시점 {@link ConductCategory} 값을 스냅샷한다 — 나중에
 * 카테고리 점수가 변경되더라도 이미 부여된 기록에 소급 적용되지 않는다.
 */
@Entity
@Table(name = "conduct_record")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ConductRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 대상 학생. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "student_user_id", nullable = false)
  private User student;

  /** 부여한 교사. 정정/취소의 소유권 판단 기준이다. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "teacher_user_id", nullable = false)
  private User teacher;

  /** 부여 당시 선택한 카테고리. 카테고리가 비활성화되더라도 이 FK는 계속 유효하다. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "category_id", nullable = false)
  private ConductCategory category;

  /** 부여 시점 카테고리 종류 스냅샷. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ConductType type;

  /** 부여 시점 카테고리 고정 점수 스냅샷(부호 포함). */
  @Column(nullable = false)
  private int points;

  /** 카테고리 외 추가로 남기는 상세 사유(선택). */
  @Column(length = 500)
  private String detail;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ConductStatus status = ConductStatus.ACTIVE;

  /** 취소 시각. 취소된 경우에만 값이 있다. */
  @Column(name = "canceled_at")
  private LocalDateTime canceledAt;

  /** 취소를 실행한 사용자(부여 교사 본인 또는 ADMIN). 취소된 경우에만 값이 있다. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "canceled_by_user_id")
  private User canceledBy;

  /** 취소 사유. 취소된 경우에만 값이 있다. */
  @Column(name = "cancel_reason", length = 500)
  private String cancelReason;

  @Version
  @Column(nullable = false)
  private Long version;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;
}
