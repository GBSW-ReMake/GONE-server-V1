package com.remake.gone.user.entity;

import com.remake.gone.gbsw.entity.Gbsw;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
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
 * 실사용자가 가입하면서 만드는 계정 엔티티.
 *
 * <p>{@code db/migration/V2__init_user_and_gbsw.sql}의 {@code user} 테이블에 대응한다.
 */
@Entity
@Table(name = "user")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 학교 명단(Gbsw)과 1:1로 연결되는 FK. */
  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "gbsw_id", nullable = false, unique = true)
  private Gbsw gbsw;

  @Column(name = "login_id", nullable = false, unique = true, length = 20)
  private String loginId;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  /** 서비스 내에서 사용하는 별명. {@link Gbsw#getName()}(실명)과는 다르다. */
  @Column(name = "name", nullable = false, unique = true, length = 20)
  private String name;

  /** 가입 시 자동 생성된 기본 닉네임을 사용자가 직접 바꿨는지 여부(접근 제어 용도 아님). */
  @Column(name = "name_customized", nullable = false)
  private boolean nameCustomized;

  @Column(name = "phone_number", nullable = false, unique = true, length = 20)
  private String phoneNumber;

  /** R2에 저장된 프로필 이미지 객체 key. */
  @Column(name = "profile_image_key", length = 500)
  private String profileImageKey;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /** 소프트 삭제 시각 (null이면 활성 계정). */
  @Column(name = "deleted_at")
  private LocalDateTime deletedAt;
}