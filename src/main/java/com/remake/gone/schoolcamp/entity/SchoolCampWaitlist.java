package com.remake.gone.schoolcamp.entity;

import com.remake.gone.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 스쿨캠핑 "자리나면 알림받기" 대기 등록 엔티티.
 *
 * <p>{@code db/migration/V14__add_schoolcamp_waitlist.sql}의 {@code school_camp_waitlist}
 * 테이블에 대응한다. 학생 1명의 "지금 이 순간의 이번 달" 대기 등록 1건을 나타낸다 — 세션(날짜)
 * 단위가 아니라 달 단위다({@code 83-schoolcamp-waitlist-notification.md} 참고).
 *
 * <p>{@code (student_user_id, month)} 유니크 제약(V14)으로 학생 1명당 달 1개에 대해 행이 딱
 * 하나만 영구히 존재한다 — 같은 달 안에서 취소 후 재등록하면 새 행을 만들지 않고 기존 행을
 * 재활성화한다({@link #cancelledAt}을 {@code null}로, {@link #registeredAt}을 갱신).
 */
@Entity
@Table(name = "school_camp_waitlist")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SchoolCampWaitlist {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "student_user_id", nullable = false)
  private User studentUser;

  /** 등록 시점에 계산한 "그 순간의 이번 달", 항상 그 달의 1일로 저장한다. */
  @Column(name = "month", nullable = false)
  private LocalDate month;

  /** 등록(또는 재등록) 시각. {@code @CreationTimestamp}를 쓰지 않는다 — 재활성화 시 직접 갱신한다. */
  @Column(name = "registered_at", nullable = false)
  private LocalDateTime registeredAt;

  /** 취소 시각. {@code null}이면 유효한 대기 등록. */
  @Column(name = "cancelled_at")
  private LocalDateTime cancelledAt;
}
