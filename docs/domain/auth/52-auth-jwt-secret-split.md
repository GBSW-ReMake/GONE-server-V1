# #52 JWT Access/Refresh Token 서명키 분리 — 기획서

관련 이슈: [#52 JWT Access/Refresh Token 서명키 분리](https://github.com/GBSW-ReMake/GONE-server-V1/issues/52)

## 개요/목적
`JwtProvider`는 지금 Access Token(AT)과 Refresh Token(RT)에 같은 서명 키
(`JwtProperties.secret`)를 쓰고, `tokenType` 클레임만으로 용도를 구분한다. AT는 매
요청마다 `Authorization` 헤더로 전송되어 노출 빈도가 훨씬 높고, RT는 로그인/재발급
시점에만 오간다 — 키를 분리하면 한쪽이 유출돼도 반대쪽 토큰은 위조할 수 없어 피해
범위가 줄어든다. 새 엔드포인트는 없다. 기존 `AuthController`/`AuthService`의 로그인/
재발급/로그아웃 흐름은 그대로 두고, 서명에 쓰는 키 개수만 1개에서 2개로 바꾼다.

`#51`(dev EC2 CI/CD) 작업 중 발견된 필요라 그 이슈에서 파생됐지만, 이 변경 자체는
`auth`/`common/security` 도메인 코드를 직접 고치므로 별도 이슈로 분리했다(`#51`의
"작업 범위"에 명시). `#51`의 dev 배포 시크릿 목록(`JWT_SECRET` → 2개로 교체)은 이
이슈가 머지된 뒤 `#51` 쪽에서 갱신한다 — 이 이슈 자체에는 포함하지 않는다.

## 변경 전/후

### `JwtProperties`
**변경 전**
```java
public record JwtProperties(
    @NotBlank String secret,
    @NotNull Long accessTokenExpiration,
    @NotNull Long refreshTokenExpiration
) {}
```

**변경 후**
```java
public record JwtProperties(
    @NotBlank String accessTokenSecret,
    @NotBlank String refreshTokenSecret,
    @NotNull Long accessTokenExpiration,
    @NotNull Long refreshTokenExpiration
) {}
```
필드명은 기존 `accessTokenExpiration`/`refreshTokenExpiration`과 같은 네이밍 규칙을
그대로 따른다(`api-design.md` 원칙 3 "직관적 일관성"). Spring Boot relaxed binding에
따라 프로퍼티 키는 `jwt.access-token-secret`/`jwt.refresh-token-secret`, 환경변수는
`JWT_ACCESS_TOKEN_SECRET`/`JWT_REFRESH_TOKEN_SECRET`이 된다.

### `JwtProvider`
**변경 전**: `createAccessToken`/`createRefreshToken`/`claims(...)` 모두 `key()`
하나(`jwtProperties.secret()` 기반)를 공유해서 쓴다.

**변경 후**: 토큰 종류별로 다른 키를 쓰도록 `key()`를 `key(String tokenType)`로 바꾼다.
```java
public String createAccessToken(Long userId, Set<String> roleCodes) {
  ...
  .signWith(key(TOKEN_TYPE_ACCESS))
  ...
}

public String createRefreshToken(Long userId) {
  ...
  .signWith(key(TOKEN_TYPE_REFRESH))
  ...
}

private Claims claims(String token, String expectedTokenType) {
  Claims claims = Jwts.parser()
      .verifyWith(key(expectedTokenType))
      .build()
      .parseSignedClaims(token)
      .getPayload();
  // tokenType 클레임 검사는 그대로 유지(아래 "보안 개선" 참고)
  ...
}

private SecretKey key(String tokenType) {
  String secret = TOKEN_TYPE_ACCESS.equals(tokenType)
      ? jwtProperties.accessTokenSecret()
      : jwtProperties.refreshTokenSecret();
  return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
}
```
`claims(token, expectedTokenType)`는 이미 호출부(`parseAccessToken`/
`getUserIdFromRefreshToken`)가 "어떤 종류를 기대하는지"를 알고 호출하므로, 검증 시에도
그 기댓값으로 올바른 키를 먼저 선택한 뒤 서명을 확인할 수 있다 — 별도 분기 추가 비용이
거의 없다.

**보안 개선(부수 효과)**: 지금은 "AT를 RT로 사용/그 반대"를 막는 방어가 `tokenType`
클레임 검사(서명 검증 *이후* 단계) 하나뿐이었다. 키를 분리하면 애초에 다른 키로 서명된
토큰은 **서명 검증 단계에서부터** 실패한다(`JwtException`) — `tokenType` 클레임 검사는
방어선으로 그대로 남기되(같은 키를 실수로 재사용하는 미래의 회귀를 잡아주는 안전망 역할),
실제 방어의 1차 저지선이 서명 검증으로 옮겨간다.

클래스 상단 Javadoc의 "두 토큰 모두 같은 서명 키를 쓰지만" 설명도 "각자 다른 서명 키를
쓰고, tokenType 클레임은 부가 방어선으로 남긴다"로 함께 수정한다.

## 데이터 모델 변경
없음 — JWT는 서버가 상태를 갖지 않는 서명 토큰이라 DB/Redis 스키마에 영향이 없다.

## 영향 받는 기존 코드/설정
- `JwtProperties`: 필드 변경(위 참고).
- `JwtProvider`: `key()` → `key(String tokenType)`, 클래스 Javadoc 수정.
- `application-dev.yml`: `jwt.secret` 한 줄을 `jwt.access-token-secret`/
  `jwt.refresh-token-secret` 두 줄로 교체(로컬 실행이 계속 되려면 실제 값 2개가 필요 —
  기존 값을 `access-token-secret`에 재사용하고 `refresh-token-secret`은 새로 생성해
  실제로 다른 값이 되게 한다. 둘을 같은 값으로 두면 "키 분리"라는 목적 자체가 무의미해짐).
- `.github/workflows/ci.yml`: `build-and-test` job의 `env:` 블록에서 `JWT_SECRET:
  dummy-jwt-secret-for-ci-context-loading-only` 한 줄을
  `JWT_ACCESS_TOKEN_SECRET`/`JWT_REFRESH_TOKEN_SECRET` 두 줄로 교체(둘 다 더미 값,
  `contextLoads()`만 통과하면 됨 — 기존 R2/NEIS 더미 값과 같은 성격).
- `JwtProviderTest`: `new JwtProperties(...)` 호출부 2곳(`JwtProviderTest.java:28`,
  `:71`)의 인자 개수/순서 갱신. 신규 테스트 케이스 추가(아래 "테스트 방법" 참고).
- `AuthServiceTest`는 `JwtProperties`를 Mockito `@Mock`으로 쓰고 `.secret()`을
  스텁하는 곳이 없어(`accessTokenExpiration()`만 스텁) 변경 불필요.
- `#51`(dev EC2 CI/CD)의 GitHub Secrets 목록/워크플로우는 이 이슈가 머지된 뒤 별도로
  갱신(이 이슈 범위 밖, 위 "개요/목적" 참고).

## API 설계 6원칙 체크
1. **한 가지를 잘하기**: 새 엔드포인트 없음, 서명 키 구조만 바꾼다.
2. **빠르게 시작하기**: 해당 없음(내부 인프라 변경, 외부에 노출되는 요청/응답 스키마
   변화 없음).
3. **일관성**: 신규 필드명이 기존 `accessTokenExpiration`/`refreshTokenExpiration`
   네이밍과 대칭을 이룬다.
4. **의미 있는 오류**: 클라이언트가 관찰하는 에러 동작은 바뀌지 않는다(서명 위조 시
   여전히 `AuthErrorCode.INVALID_REFRESH_TOKEN` 등 기존 경로로 처리).
5. **확장성/성능**: 해당 없음.
6. **하위 호환성**: **서버 설정(환경변수/프로퍼티) 차원의 breaking change다** — 배포
   환경에 `JWT_SECRET` 대신 `JWT_ACCESS_TOKEN_SECRET`/`JWT_REFRESH_TOKEN_SECRET`을
   등록해야 앱이 뜬다. 클라이언트(API 계약) 관점에서는 영향 없음 — 이미 발급된 토큰은
   이 배포 시점에 서버가 재시작되며 무효화되지만(서명 키 자체가 바뀌므로), 이 프로젝트가
   아직 실사용자가 없는 개발 단계라 영향이 제한적이다(`api-design.md` "하위 호환성"
   원칙이 명시한 예외 상황과 동일한 근거).

## 리스크 및 고려사항
- **배포 시 두 시크릿을 모두 등록해야 앱이 기동한다** — `@NotBlank`라 하나라도 빠지면
  `contextLoads()`/실제 기동 모두 즉시 실패한다(기존 R2/NEIS와 같은 실패 모드, 새로운
  리스크는 아님).
- **`access-token-secret`과 `refresh-token-secret`을 같은 값으로 등록하면 분리 효과가
  없다** — 실수 방지를 위해 로컬(`application-dev.yml`) 값부터 실제로 다른 문자열로
  채운다(위 "영향 받는 기존 코드/설정" 참고).
- **`#51`과의 순서 의존성**: `#51`의 `deploy-dev`가 이 이슈보다 먼저 머지/배포되면
  `JWT_SECRET` 하나만 있는 상태로 배포되므로 문제가 없다(이 이슈가 아직 안 들어갔으니
  앱도 여전히 `secret` 필드 하나만 기대함). 반대로 이 이슈가 먼저 머지되면 `dev`
  브랜치의 앱은 두 시크릿을 요구하는데 `#51`이 아직 안 갱신됐다면 dev EC2 배포가
  실패한다(`.env`에 없는 프로퍼티) — 병합 순서를 보스와 맞추거나, 이 이슈 머지 직후
  바로 `#51`의 시크릿 목록을 갱신해야 한다.

## 테스트 방법
1. `JwtProviderTest`의 기존 두 생성자 호출부를 새 필드 순서로 갱신 — 이미 있는 라운드
   트립(발급→파싱) 케이스들은 그대로 통과해야 한다(AT는 AT 키로, RT는 RT 키로 서명/검증
   하므로).
2. 신규 케이스 추가: "AT 서명 키로 만든 토큰으로 `getUserIdFromRefreshToken` 호출 시
   `JwtException`", 그 반대 방향(RT 키 토큰을 `parseAccessToken`에)도 동일하게 추가 —
   지금까지는 같은 키였기 때문에 성립할 수 없었던 새 실패 시나리오라, 이번에 처음 검증
   가능해진다.
3. 로컬 서버 기동 후 회원가입/로그인/재발급/로그아웃 흐름을 Postman(`GONE - Auth API`
   컬렉션)으로 재검증 — 기존 요청/응답 스키마가 그대로인지 확인(계약 자체는 안 바뀜).
4. `./gradlew build`, `./gradlew checkstyleMain` 로컬 통과 + CI 통과 확인(새 더미
   시크릿 2개로 `contextLoads()` 통과 확인).
