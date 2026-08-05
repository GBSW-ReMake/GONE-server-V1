package com.remake.gone.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 회원가입 요청 DTO.
 *
 * <p>별명(닉네임)은 여기서 받지 않는다 — 학적(Gbsw) 정보를 이용해 서버가 자동으로 기본값을
 * 채우고, 가입 후 {@code PATCH /api/v1/users/me/name}으로 자유롭게 바꾸도록 유도한다
 * (자세한 배경은 {@code docs/20.md} 참고).
 *
 * @param loginId     로그인 ID. 영문/숫자 4~20자만 허용.
 * @param password    평문 비밀번호 (서버에서 해시 처리). 영문 + 숫자 + 특수문자를 모두 포함한
 *                    8~20자만 허용(대문자 강제 없음).
 * @param phoneNumber 인증된 휴대폰 번호. 하이픈 없이 {@code 01012345678} 형식만 허용.
 * @param ticket      휴대폰 인증 완료 후 발급된 signUpTicket
 */
public record SignUpRequest(
    @NotBlank
    @Pattern(
        regexp = "^[a-zA-Z0-9]{4,20}$",
        message = "로그인 ID는 영문, 숫자로만 4자 이상 20자 이하로 입력해주세요"
    )
    String loginId,

    @Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s]).{8,20}$",
        message = "비밀번호는 영문, 숫자, 특수문자를 모두 포함하여 8자 이상 20자 이하로 입력해주세요"
    )
    String password,

    @NotBlank
    @Pattern(regexp = "^01[0-9]\\d{7,8}$", message = "휴대폰 번호는 하이픈 없이 01012345678 형식으로 입력해주세요")
    String phoneNumber,

    @NotBlank
    String ticket
) {}
