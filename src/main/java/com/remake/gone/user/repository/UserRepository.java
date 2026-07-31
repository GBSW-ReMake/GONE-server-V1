package com.remake.gone.user.repository;

import com.remake.gone.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link User} 리포지토리.
 */
public interface UserRepository extends JpaRepository<User, Long> {
}
