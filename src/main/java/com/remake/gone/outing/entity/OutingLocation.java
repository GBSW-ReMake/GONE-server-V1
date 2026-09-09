package com.remake.gone.outing.entity;

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

/**
 * 외출증 위치 핑(시계열) 엔티티(#97).
 *
 * <p>{@code db/migration/V20260825160335__add_outing_location.sql}의
 * {@code outing_location} 테이블에 대응한다. 학생 앱이 외출 중({@code DEPARTED}) 주기적으로
 * 전송하는 좌표를 저장한다.
 */
@Entity
@Table(name = "outing_location")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class OutingLocation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "outing_id", nullable = false)
  private Outing outing;

  @Column(nullable = false)
  private Double latitude;

  @Column(nullable = false)
  private Double longitude;

  /** 서버가 핑을 수신한 시각(클라이언트 시각 아님 — 기기 시계 오차/조작 가능성 때문). */
  @Column(name = "recorded_at", nullable = false)
  private LocalDateTime recordedAt;
}
