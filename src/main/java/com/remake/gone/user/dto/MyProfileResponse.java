package com.remake.gone.user.dto;

/**
 * 내 프로필 조회 응답 DTO.
 *
 * @param name             현재 닉네임(가입 시 자동 생성된 기본값일 수 있음)
 * @param hasProfileImage  프로필 사진 설정 여부
 * @param profileImageUrl  프로필 사진 presigned URL. {@code hasProfileImage}가 {@code false}면
 *                         {@code null}
 * @param realName         실명({@code Gbsw.name})
 * @param grade            학년({@code Gbsw.grade}). 선생님 계정이면 {@code null}
 * @param classNo          반({@code Gbsw.classNo}). 선생님 계정이면 {@code null}
 */
public record MyProfileResponse(
    String name,
    boolean hasProfileImage,
    String profileImageUrl,
    String realName,
    Integer grade,
    Integer classNo
) {}
