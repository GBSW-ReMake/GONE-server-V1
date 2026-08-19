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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 스쿨캠핑 신청(팀) 엔티티.
 *
 * <p>{@code db/migration/V12__add_schoolcamp_application.sql}의 {@code school_camp_application}
 * 테이블에 대응한다. 대표 신청자가 제출한 신청 1건 = 팀 1개를 나타내며, 대표 신청자 본인도
 * {@link SchoolCampMember}에 팀원 1명(is_applicant=true)으로 같이 기록된다.
 *
 * <p>{@link #teacherUser}/{@link #teacherName} 중 정확히 하나만 값을 가져야 한다(서비스
 * 레벨 검증 — 이 프로젝트에 CHECK 제약 사용 전례가 없어 DB 제약 대신 서비스 코드에서
 * 검증한다).
 */
@Entity
@Table(name = "school_camp_application")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SchoolCampApplication {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "session_id", nullable = false)
  private SchoolCampSession session;

  /** 대표 신청자. {@link SchoolCampMember}에도 팀원 1명으로 같이 기록된다. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "applicant_user_id", nullable = false)
  private User applicant;

  /** 가입된 선생님을 선택한 경우. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "teacher_user_id")
  private User teacherUser;

  /** 가입 안 된 선생님을 자유 입력한 경우. */
  @Column(name = "teacher_name", length = 50)
  private String teacherName;

  @CreationTimestamp
  @Column(name = "applied_at", nullable = false, updatable = false)
  private LocalDateTime appliedAt;

  /** 대표 신청자가 취소한 시각. {@code null}이면 유효한 신청. */
  @Column(name = "cancelled_at")
  private LocalDateTime cancelledAt;
}
