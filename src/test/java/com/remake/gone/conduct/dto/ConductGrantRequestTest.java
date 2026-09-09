package com.remake.gone.conduct.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConductGrantRequestTest {

  private final Validator validator =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  @DisplayName("detail이 500자이면 검증을 통과한다")
  void acceptsDetailOf500Chars() {
    ConductGrantRequest request = new ConductGrantRequest(1L, 1L, "a".repeat(500));

    Set<ConstraintViolation<ConductGrantRequest>> violations = validator.validate(request);

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("detail이 501자이면 검증에 실패한다")
  void rejectsDetailExceeding500Chars() {
    ConductGrantRequest request = new ConductGrantRequest(1L, 1L, "a".repeat(501));

    Set<ConstraintViolation<ConductGrantRequest>> violations = validator.validate(request);

    assertThat(violations).hasSize(1);
    assertThat(violations.iterator().next().getPropertyPath().toString())
        .isEqualTo("detail");
  }
}
