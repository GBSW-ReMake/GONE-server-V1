package com.remake.gone.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 별명(닉네임) 변경 요청 DTO.
 *
 * @param name 새로 설정할 별명
 */
public record UpdateNameRequest(
    @NotBlank
    @Size(max = 20, message = "별명은 20자 이하로 입력해주세요")
    String name
) {}
