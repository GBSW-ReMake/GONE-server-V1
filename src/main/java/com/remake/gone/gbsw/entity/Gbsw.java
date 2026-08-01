package com.remake.gone.gbsw.entity;

import com.remake.gone.gbsw.enums.GbswType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
 * 학교가 엑셀로 미리 삽입하는 원본 명단(학생+선생님 통합) 엔티티.
 *
 * <p>{@code db/migration/V2__init_user_and_gbsw.sql}의 {@code gbsw} 테이블에 대응한다.
 */
@Entity
@Table(
    name = "gbsw",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_class_slot",
        columnNames = {"grade", "class_no", "number"}
    )
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Gbsw {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private GbswType type;

  @Column(nullable = false, length = 50)
  private String name;

  @Column(name = "phone_number", nullable = false, unique = true, length = 20)
  private String phoneNumber;

  @Column(name = "birth_date")
  private LocalDate birthDate;

  /** 학생 전용 필드 (선생님이면 null). */
  @Column
  private Integer grade;

  /** 학생 전용 필드 (선생님이면 null). */
  @Column(name = "class_no")
  private Integer classNo;

  /** 학생 전용 필드 (선생님이면 null). */
  @Column
  private Integer number;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}