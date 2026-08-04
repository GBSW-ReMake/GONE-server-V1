package com.remake.gone.common.security;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증이 필요한 API에 인증 정보 없이(또는 무효한 토큰으로) 접근했을 때의 응답을 처리한다.
 *
 * <p>{@link com.remake.gone.common.exception.GlobalExceptionHandler}는 컨트롤러 내부에서 던진
 * 예외만 잡을 수 있고, Security 필터 체인 단계에서 걸리는 인증 실패는 잡지 못한다. 그래서 이
 * 진입점에서 직접 나머지 API와 동일한 {@link ApiResponse} 포맷으로 응답을 만든다.
 *
 * <p>{@code @Component}가 아니라 {@code SecurityConfig}의 {@code @Bean} 메서드로 등록한다
 * ({@link JwtProvider} 클래스 주석 참고).
 */
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException
  ) throws IOException {
    CommonErrorCode errorCode = CommonErrorCode.UNAUTHORIZED;

    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");

    ApiResponse<Void> body =
        ApiResponse.fail(null, errorCode.getDefaultMessage(), errorCode.getCode());
    response.getWriter().write(objectMapper.writeValueAsString(body));
  }
}
