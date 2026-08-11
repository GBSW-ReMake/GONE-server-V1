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
- `POST /api/v1/auth/login` 실제 HTTP 요청으로 로그인 시도.

## 발견 사항

### High
없음.

### Medium
1. **로그인/재발급/로그아웃 실제 HTTP 흐름을 끝까지 검증하지 못함(환경 제약)** —
   `POST /api/v1/auth/login` 호출 시 `500`(`COMMON_007`)이 반환됐다. 원인은 이 이슈의
   변경과 무관하게 **로컬 Redis가 현재 세션 환경에 떠 있지 않아서**다(로그 확인:
   `io.lettuce.core.RedisConnectionException: Unable to connect to localhost/<unresolved>:6379`,
   `Connection refused`). `AuthService.login`은 `RedisRepository`(실제 Redis 필요)로
   Refresh Token을 저장하므로 Redis 없이는 로그인 자체가 불가능하다 — 이 프로젝트의
   기존 구조상 당연한 실패이지 `JwtProvider`/`JwtProperties` 변경으로 인한 회귀가
   아니다(스택트레이스에 JWT 관련 코드는 전혀 등장하지 않는다).
   로컬에 Redis를 새로 띄우거나(Docker 데몬도 이 환경에서 연결되지 않아 즉시 띄우기
   어려움) 보스의 환경에서 재확인이 필요하다. 대신 아래처럼 이 변경이 실제로 건드리는
   범위(서명/검증 로직)는 단위 테스트로 이미 실제 키 값을 써서 직접 검증됐다:
   - `JwtProviderTest`가 `JwtProvider`를 목(mock) 없이 실제 인스턴스로 생성해 발급/검증을
     왕복시키므로, "로그인 API가 호출하는 코드 경로"의 핵심 로직(서명 생성/검증)은
     이미 실제 조건과 동일하게 검증된 상태다. Redis 연결은 이 이슈가 건드리지 않은
     기존 인프라라, 로그인 API 자체의 종단 간(E2E) 재확인만 남아 있다.

### Low
없음.

## 결론
Critical/High/Low 없음. 코드 변경 자체(서명 키 분리, 교차 위조 거부)는 단위 테스트로
실제 값을 써서 직접 검증됐고, 새 설정 스키마로 실제 서버가 정상 기동하는 것도 확인했다.
**다만 로그인 API를 실제 HTTP 요청으로 끝까지(로그인→재발급→로그아웃) 확인하는 건 이
세션의 Redis 미기동으로 완료하지 못했다** — Redis가 있는 환경(로컬 Redis 기동 또는
보스 환경)에서 Postman(`GONE - Auth API` 컬렉션)으로 재확인이 필요하다.
