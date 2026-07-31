package com.remake.gone.auth.dto;

public record SignUpRequest (
  String loginId,
  String password,
  String name
) {}
