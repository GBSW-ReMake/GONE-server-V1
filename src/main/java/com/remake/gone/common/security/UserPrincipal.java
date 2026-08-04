package com.remake.gone.common.security;

/**
 * 인증된 요청에서 {@code SecurityContext}에 담기는 최소 정보.
 *
 * <p>현재는 로그아웃 등 "누가 요청했는지"만 필요한 API만 있어 {@code userId}만 담는다.
 * DB 조회가 필요한 {@code UserDetailsService} 기반 인증 대신, Access Token의 클레임만으로
 * 즉시 만들 수 있는 경량 principal이다. 추후 이름/권한 등이 필요해지면 그때 확장한다.
 *
 * @param userId 인증된 사용자의 ID
 */
public record UserPrincipal(Long userId) {}
