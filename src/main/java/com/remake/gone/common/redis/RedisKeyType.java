package com.remake.gone.common.redis;

import java.time.Duration;

/** 프로젝트 전체에서 사용하는 Redis 키 접두사와 TTL을 한곳에서 관리한다. */
public enum RedisKeyType {
  /** 휴대폰 인증번호 저장 키. value: 인증번호(6자리 숫자 문자열), TTL 5분. */
  PHONE_VERIFICATION("auth:phone:verify:", Duration.ofMinutes(5)),

  /** 휴대폰 인증 성공 후 발급하는 회원가입용 티켓 저장 키. value: 인증된 휴대폰 번호, TTL 10분. */
  SIGN_UP_TICKET("auth:signup:ticket:", Duration.ofMinutes(10)),

  /** 인증번호 재발송 쿨다운 키. value 없이 존재 여부만 사용, TTL 30초. */
  PHONE_SEND_COOLDOWN("auth:phone:send-cooldown:", Duration.ofSeconds(30)),

  /** 휴대폰 인증번호 확인 실패 횟수 저장 키. value: 누적 실패 횟수, TTL 5분. */
  PHONE_VERIFICATION_FAIL_COUNT("auth:phone:fail-count:", Duration.ofMinutes(5)),

  /**
   * 로그인 시 발급한 Refresh Token 저장 키. identifier: userId, value: 최신 Refresh Token 문자열, TTL 14일.
   *
   * <p>재발급(reissue) 때마다 값을 새 토큰으로 교체(rotation)한다. TTL은
   * {@code jwt.refresh-token-expiration}(application.yml)과 값을 맞춰야 한다.
   */
  REFRESH_TOKEN("auth:refresh:", Duration.ofDays(14)),

  /** NEIS 급식 정보 캐시 키. identifier: 날짜(yyyyMMdd), value: 그날 급식 응답, TTL 6시간. */
  MEAL_INFO("neis:meal:", Duration.ofHours(6)),

  /** NEIS 시간표 캐시 키. identifier: {@code grade:classNo:날짜}, value: 시간표 응답, TTL 6시간. */
  TIMETABLE("neis:timetable:", Duration.ofHours(6)),

  /**
   * 위치 기반 복귀 리마인더(#99) 재발송 쿨다운 키. identifier: outingId, value 없이 존재
   * 여부만 사용, TTL 5분(재발송 간격). {@code ConcurrentHashMap}을 인메모리로 직접 관리하던
   * 이전 방식은 outing이 타임아웃 cap을 넘겨 handler 호출 자체가 끊기면 정리될 기회가 없어
   * 항목이 영구히 남았다(#99 CodeRabbit 지적 C) — TTL로 어떤 경로로 감시가 끝나든 자동
   * 정리되게 한다.
   */
  OUTING_LOCATION_REMINDER_COOLDOWN("outing:location-reminder:", Duration.ofMinutes(5));

  private final String prefix;
  private final Duration ttl;

  RedisKeyType(String prefix, Duration ttl) {
    this.prefix = prefix;
    this.ttl = ttl;
  }

  /** 실제 키값을 만든다 (prefix + 식별자). */
  public String key(String identifier) {
    return prefix + identifier;
  }

  /** TTL을 반환한다. */
  public Duration ttl() {
    return ttl;
  }
}
