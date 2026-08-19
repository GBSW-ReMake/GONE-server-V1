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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 스쿨캠핑 팀원 엔티티.
 *
 * <p>{@code db/migration/V12__add_schoolcamp_application.sql}의 {@code school_camp_member}
 * 테이블에 대응한다. 한 {@link SchoolCampApplication}(팀)에 속한 팀원 1명을 나타내며,
 * 대표 신청자 본인도 {@link #applicant}가 {@code true}인 행으로 여기 포함된다.
 *
 * <p>{@link #studentUser}/{@link #guestName} 중 정확히 하나만 값을 가져야 한다(서비스
 * 레벨 검증 — {@link SchoolCampApplication}의 담당 선생님 검증과 동일 패턴).
 */
@Entity
@Table(name = "school_camp_member")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SchoolCampMember {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "application_id", nullable = false)
  private SchoolCampApplication application;

  /** 가입된 학생을 선택한 경우. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "student_user_id")
  private User studentUser;

  /** "기타"로 이름만 자유 입력한 경우. */
  @Column(name = "guest_name", length = 50)
  private String guestName;

  /** 대표 신청자 본인이면 {@code true}(항상 {@link #studentUser}가 채워짐). */
  @Column(name = "is_applicant", nullable = false)
  private boolean applicant;
}
