package com.remake.gone.auth.controller;

import com.remake.gone.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/api/v1/auth/")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

//  @PostMapping("/sign-up")
//  public ResponseEntity<Void> signUp() {
//
//
//
//  }
}
