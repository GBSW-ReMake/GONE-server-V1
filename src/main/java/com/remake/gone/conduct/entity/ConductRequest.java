package com.remake.gone.conduct.entity;

import com.remake.gone.conduct.enums.ConductRequestStatus;
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
 * 선도부가 부여 권한자에게 상/벌점 부여를 요청하는 엔티티.
 *
 * <p>{@code db/migration/V20260901120000__add_conduct_request.sql}의
 * {@code conduct_request} 테이블에 대응한다.
 */
@Entity
@Table(name = "conduct_request")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ConductRequest {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 요청자 (DISCIPLINE). */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "requester_user_id", nullable = false)
  private User requester;

  /** 상/벌점 대상 학생. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "student_user_id", nullable = false)
  private User student;

  /** 처리 담당자 (TEACHER 또는 ADMIN). */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "assignee_user_id", nullable = false)
  private User assignee;

  /** 요청한 카테고리. 승인자가 승인 시점에 변경할 수 있다. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "category_id", nullable = false)
  private ConductCategory category;

  /** 추가 상세 사유(선택). */
  @Column(length = 500)
  private String detail;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ConductRequestStatus status = ConductRequestStatus.PENDING;

  /**
   * 승인 시 생성된 ConductRecord ID. 승인(이슈 #C) 이후에만 값이 있다.
   * 처음부터 컬럼을 포함해 이슈 #C에서 ALTER TABLE 없이 링크할 수 있게 한다.
   */
  @Column(name = "conduct_record_id")
  private Long conductRecordId;

  @Version
  @Column(nullable = false)
  private Long version;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  /** 취소 시각. CANCELED 상태인 경우에만 값이 있다. */
  @Column(name = "canceled_at")
  private LocalDateTime canceledAt;
}
