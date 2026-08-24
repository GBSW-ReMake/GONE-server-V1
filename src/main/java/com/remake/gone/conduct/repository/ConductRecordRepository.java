package com.remake.gone.conduct.repository;

import com.remake.gone.conduct.entity.ConductRecord;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@link ConductRecord} 리포지토리. */
public interface ConductRecordRepository extends JpaRepository<ConductRecord, Long> {}
