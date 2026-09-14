package com.remake.gone.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * FCM 디바이스 토큰 등록·갱신 요청.
 *
 * @param fcmToken 클라이언트가 Firebase에서 발급받은 현재 기기의 FCM 토큰
 */
public record RegisterDeviceTokenRequest(
    @NotBlank(message = "FCM 토큰은 비어 있을 수 없습니다.")
    @Size(max = 255, message = "FCM 토큰은 255자 이하여야 합니다.")
    String fcmToken
) {
}
