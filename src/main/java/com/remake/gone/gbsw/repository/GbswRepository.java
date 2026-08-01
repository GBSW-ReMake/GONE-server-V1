package com.remake.gone.gbsw.repository;

import com.remake.gone.gbsw.entity.Gbsw;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link Gbsw} 리포지토리.
 */
public interface GbswRepository extends JpaRepository<Gbsw, Long> {

  /**
   * 휴대폰 번호로 명단 레코드를 조회합니다.
   *
   * @param phoneNumber 조회할 휴대폰 번호
   * @return 일치하는 {@link Gbsw}, 없으면 빈 {@link Optional}
   */
  Optional<Gbsw> findByPhoneNumber(String phoneNumber);
}
