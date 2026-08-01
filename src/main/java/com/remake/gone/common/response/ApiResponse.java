package com.remake.gone.common.response;

/**
 * API 공통 응답 포맷.
 *
 * <p>모든 API 엔드포인트는 이 레코드를 통해 일관된 구조로 응답을 반환합니다.
 * 성공 여부({@code success}), 실제 데이터({@code data}), 부가 메시지({@code message})를 포함합니다.
 *
 * @param <T>     응답 데이터의 타입
 * @param success 요청 처리 성공 여부 — {@code true}면 성공, {@code false}면 실패
 * @param data    실제 응답 데이터. 데이터가 없는 경우 {@code null}
 * @param message 응답에 대한 부가 설명 메시지
 * @param code    실패 시 클라이언트가 분기 처리할 수 있는 에러 식별 코드(예: {@code "AUTH_005"}).
 *                성공 응답이거나 코드가 없는 경우 {@code null}
 */
public record ApiResponse<T>(boolean success, T data, String message, String code) {

  /**
   * 성공 응답을 생성합니다.
   *
   * @param <T>     응답 데이터의 타입
   * @param data    응답에 포함할 데이터
   * @param message 성공 메시지
   * @return {@code success = true}로 설정된 {@link ApiResponse} 인스턴스
   */
  public static <T> ApiResponse<T> success(T data, String message) {
    return new ApiResponse<T>(true, data, message, null);
  }

  /**
   * 실패 응답을 생성합니다.
   *
   * @param <T>     응답 데이터의 타입
   * @param data    오류와 함께 반환할 데이터. 없으면 {@code null}
   * @param message 실패 원인을 설명하는 메시지
   * @return {@code success = false}로 설정된 {@link ApiResponse} 인스턴스
   */
  public static <T> ApiResponse<T> fail(T data, String message) {
    return new ApiResponse<T>(false, data, message, null);
  }

  /**
   * 에러 식별 코드를 포함한 실패 응답을 생성합니다.
   *
   * @param <T>     응답 데이터의 타입
   * @param data    오류와 함께 반환할 데이터. 없으면 {@code null}
   * @param message 실패 원인을 설명하는 메시지
   * @param code    클라이언트가 분기 처리할 수 있는 에러 식별 코드
   * @return {@code success = false}로 설정된 {@link ApiResponse} 인스턴스
   */
  public static <T> ApiResponse<T> fail(T data, String message, String code) {
    return new ApiResponse<T>(false, data, message, code);
  }
}
