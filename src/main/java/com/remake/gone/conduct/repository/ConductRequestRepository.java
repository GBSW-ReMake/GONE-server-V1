package com.remake.gone.conduct.repository;

import com.remake.gone.conduct.entity.ConductRequest;
import org.springframework.data.jpa.repository.JpaRepository;

/** 상/벌점 요청 JPA 레포지토리. */
public interface ConductRequestRepository extends JpaRepository<ConductRequest, Long> {}
