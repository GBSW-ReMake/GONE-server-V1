package com.remake.gone.conduct.entity;

import com.remake.gone.conduct.enums.ConductType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * 상/벌점 부여 사유를 사전 정의한 카테고리 엔티티.
 *
 * <p>{@code db/migration/V15__add_conduct_category.sql}의 {@code conduct_category} 테이블에
 * 대응한다. 코드 상수(enum)가 아니라 DB 테이블로 관리해 관리자가 추가/비활성화할 수 있게 한다.
 * "삭제"는 {@link #active}를 {@code false}로 바꾸는 소프트 삭제로만 처리한다 — 이미 이 카테고리를
 * 참조 중인 {@code ConductRecord}의 FK 무결성이 깨지지 않아야 하기 때문이다.
 */
@Entity
@Table(name = "conduct_category")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ConductCategory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 부여 화면에 표시하는 사유 문구. */
  @Column(nullable = false, unique = true, length = 500)
  private String label;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ConductType type;

  /** 고정 점수(부호 포함 — 상점은 양수, 벌점은 음수). */
  @Column(nullable = false)
  private int points;

  /**
   * 활성 여부. {@code false}이면 부여 화면 선택 목록에서 제외된다. 이미 이 카테고리를 참조 중인
   * 과거 {@code ConductRecord}는 행이 남아있으므로 FK 무결성에는 영향이 없다.
   */
  @Builder.Default
  @Column(nullable = false)
  private boolean active = true;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
