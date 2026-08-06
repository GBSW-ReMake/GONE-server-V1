package com.remake.gone.outing.entity;

import com.remake.gone.outing.enums.OutingStatus;
import com.remake.gone.outing.enums.OutingTimeSlot;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 외출증(Outing) 엔티티.
 *
 * <p>{@code db/migration/V7__add_outing.sql}의 {@code outing} 테이블에 대응한다. 이 이슈(#29)에서는
 * {@link #status}가 {@link OutingStatus#PENDING}만 실제로 쓰이지만, 승인/거절/출발/도착까지
 * 전체 흐름(후속 이슈 #30/#31)을 고려해 컬럼을 처음부터 다 만들어둔다.
 */
@Entity
@Table(name = "outing")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder(toBuilder = true)
public class Outing {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 프론트에 노출하는 외부 식별자(영숫자 10자리). 내부 PK({@link #id})는 노출하지 않는다. */
  @Column(nullable = false, unique = true, length = 10)
  private String code;

  /** 신청한 학생. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "student_user_id", nullable = false)
  private User student;

  /** 신청 시 지정한 담당 선생님. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "teacher_user_id", nullable = false)
  private User teacher;

  @Column(nullable = false, length = 200)
  private String reason;

  @Column(name = "outing_date", nullable = false)
  private LocalDate outingDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "time_slot", nullable = false, length = 20)
  private OutingTimeSlot timeSlot;

  /** {@link #timeSlot}이 프리셋이면 서버가 채운 값, {@code CUSTOM}이면 검증된 사용자 입력값. */
  @Column(name = "start_time", nullable = false)
  private LocalTime startTime;

  @Column(name = "end_time", nullable = false)
  private LocalTime endTime;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private OutingStatus status;

  @Column(name = "approved_at")
  private LocalDateTime approvedAt;

  @Column(name = "rejected_reason", length = 200)
  private String rejectedReason;

  /** 학생 본인이 "출발" 버튼을 누른 시각. */
  @Column(name = "departed_at")
  private LocalDateTime departedAt;

  /** 학생 본인이 "도착" 버튼을 누른 시각. */
  @Column(name = "returned_at")
  private LocalDateTime returnedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
