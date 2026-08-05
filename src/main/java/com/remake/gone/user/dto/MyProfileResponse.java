package com.remake.gone.user.dto;

/**
 * 내 프로필 조회 응답 DTO.
 *
 * @param name            현재 닉네임(가입 시 자동 생성된 기본값일 수 있음)
 * @param hasProfileImage 프로필 사진 설정 여부
 */
public record MyProfileResponse(
    String name,
    boolean hasProfileImage
) {}
