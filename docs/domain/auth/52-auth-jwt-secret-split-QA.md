# #52 JWT Access/Refresh Token 서명키 분리 — QA 결과

관련 기획서: [52-auth-jwt-secret-split.md](./52-auth-jwt-secret-split.md)
관련 코드 리뷰: [52-auth-jwt-secret-split-code-review.md](./52-auth-jwt-secret-split-code-review.md)
(9단계 코드 리뷰 지적 사항은 QA 이전에 전부 반영 완료 — 이 문서는 QA에서 새로 발견된
것만 다룬다)

## 검증 방법/범위
- `./gradlew build`(전체: checkstyle + 모든 테스트) 통과 확인 — `JwtProviderTest`(키
  분리/교차 위조 거부 케이스 포함), `AuthServiceTest`, `@SpringBootTest` 컨텍스트 로딩
  포함 전부 통과.
- 로컬 서버 실제 기동(`./gradlew bootRun`, `dev` 프로필, 분리된
  `application-dev.yml`의 `access-token-secret`/`refresh-token-secret` 사용) — 정상
  기동 확인(`Started GoneServerV1Application in 16.397 seconds`). `JwtProperties`의
  `@NotBlank` 검증과 프로퍼티 바인딩이 실제 설정 파일 기준으로도 문제없이 동작함을
  확인했다.
- 최초 시도 시 로컬 Redis가 떠 있지 않아 로그인이 `500`(`COMMON_007`,
  `RedisConnectionException`)으로 실패했으나(이 이슈의 변경과 무관 — 스택트레이스에
  JWT 관련 코드 없음), 보스가 로컬 Redis를 기동해준 뒤 재시도해 아래 전체 흐름을 실제
  HTTP 요청으로 끝까지 검증했다.

## 실제 HTTP E2E 검증 (Redis 기동 후)
로컬 서버(`http://localhost:9091`)에 실제 계정으로 로그인부터 로그아웃까지 순서대로
호출했다.

1. `POST /api/v1/auth/login` → `200`, `accessToken`/`refreshToken` 정상 발급
2. `POST /api/v1/auth/reissue`(발급받은 `refreshToken`으로) → `200`, 새
   `accessToken`/`refreshToken` 발급(회전 확인)
3. 재발급받은 `accessToken`으로 `GET /api/v1/users/me` → `200`, 정상 인증 통과
4. **옛(이미 재발급에 쓴) `refreshToken`으로 다시 재발급 시도 → `401` `AUTH_008`**
   (재사용 차단, 회전 정상 동작)
5. **`accessToken`을 `refreshToken` 자리에 넣어 재발급 시도 → `401` `AUTH_008`**
   (교차 사용 차단 — 이번 이슈가 목표한 "AT/RT 키 분리" 효과가 실제 요청에서도 그대로
   관찰됨. 서명 키가 분리되기 전에도 `tokenType` 클레임 검사로 같은 응답이 나왔을
   경로라 이 결과만으로 "서명 검증에서 막혔는지"까지는 HTTP 응답에서 구분되지 않지만,
   그 지점은 `JwtProviderTest`의 신규 위조 토큰 테스트에서 이미 화이트박스로 확인했다)
6. `POST /api/v1/auth/logout` → `200`

## 발견 사항
Critical/High/Medium/Low 모두 없음.

## 결론
코드 변경 자체(서명 키 분리, 교차 위조 거부)는 단위 테스트로 실제 값을 써서 직접
검증됐고, 이번에 실제 서버 + Redis로 로그인→재발급(회전)→인증→재사용 차단→교차 사용
차단→로그아웃까지 전체 흐름을 HTTP 레벨로 재확인해 기존 동작이 그대로 유지됨을
확인했다. 이 이슈의 완료 조건(Definition of Done)을 모두 충족한다.
