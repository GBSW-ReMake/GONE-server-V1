package com.remake.gone.auth.dto;

/**
 * 회원가입 요청 DTO.
 *
 * @param loginId     로그인 ID
 * @param password    평문 비밀번호 (서버에서 해시 처리)
 * @param name        서비스 내에서 사용할 별명 (실명이 아님, 실명은 명단(Gbsw)에서 관리)
 * @param phoneNumber 인증된 휴대폰 번호
 * @param ticket      휴대폰 인증 완료 후 발급된 signUpTicket
 */
public record SignUpRequest(
    String loginId,
    String password,
    String name,
    String phoneNumber,
    String ticket
) {}
