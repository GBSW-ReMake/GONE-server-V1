package com.remake.gone.schoolcamp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 스쿨캠핑 세션(날짜) 엔티티.
 *
 * <p>{@code db/migration/V11__add_schoolcamp_session.sql}의 {@code school_camp_session}
 * 테이블에 대응한다. 하루에 팀 하나만 신청 가능하므로, 인원수를 세는 카운터 대신 이 날짜가
 * 점유됐는지만 나타내는 {@link #takenAt}을 쓴다.
 */
@Entity
@Table(name = "school_camp_session")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SchoolCampSession {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "camp_date", nullable = false, unique = true)
  private LocalDate campDate;

  /** 신청이 성사되어 이 날짜가 점유된 시각. {@code null}이면 아직 신청 가능한 빈 날짜. */
  @Column(name = "taken_at")
  private LocalDateTime takenAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
