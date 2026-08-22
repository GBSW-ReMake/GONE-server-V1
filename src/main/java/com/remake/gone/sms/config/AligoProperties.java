package com.remake.gone.sms.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 알리고(Aligo) SMS API 연동에 필요한 설정 값.
 *
 * <p>{@code staging}/{@code prod} 환경 구분 없이 동일한 값 하나를 사용한다.
 *
 * @param key    알리고 API 인증키
 * @param userId 알리고 계정 아이디
 * @param sender 발신번호(알리고에 사전 등록된 번호만 사용 가능)
 */
@ConfigurationProperties(prefix = "aligo")
@Validated
public record AligoProperties(
    @NotBlank String key,
    @NotBlank String userId,
    @NotBlank String sender
) {}
