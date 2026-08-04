package com.remake.gone.role.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 역할(권한 단위) 엔티티.
 *
 * <p>{@code db/migration/V6__add_role_and_user_role.sql}의 {@code role} 테이블에 대응한다.
 * 학생/선생님/관리자/부서(학생회, 선도부 등)를 포함하는 시드 데이터가 마이그레이션에 포함되어 있다.
 */
@Entity
@Table(name = "role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Role {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 역할 코드 (예: {@code STUDENT}, {@code DISCIPLINE}). JWT의 {@code roles} 클레임에도 이 값이 담긴다. */
  @Column(nullable = false, unique = true, length = 30)
  private String code;

  /** 화면 표시용 한글명 (예: "선도부"). */
  @Column(nullable = false, length = 30)
  private String name;
}
