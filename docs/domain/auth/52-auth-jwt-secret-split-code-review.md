# #52 JWT Access/Refresh Token 서명키 분리 — 코드 리뷰 결과

리뷰 대상: `dev..feat/#52-jwt-secret-split` (커밋 `9d61fb6`, `c5652e0`, `89d90ed`, `cedb547`)
기준 문서: [52-auth-jwt-secret-split.md](./52-auth-jwt-secret-split.md)(기획서),
[code-review-template.md](../../rules/code-review-template.md),
[code-review-isolation.md](../../rules/code-review-isolation.md)

## 리뷰 범위 확인
- `JwtProperties.java`(필드 분리), `JwtProvider.java`(`key(String tokenType)` 도입),
  `JwtProviderTest.java`(생성자 갱신 + 신규 위조 토큰 테스트 2건), `.github/workflows/ci.yml`
  (더미 시크릿 2개 분리) 4개 파일 diff를 모두 읽었다.
- 기획서의 "변경 전/후" 코드 스니펫과 실제 구현을 한 줄씩 대조했다 — `key(String tokenType)`의
  삼항 분기, `claims(token, expectedTokenType)`의 키 선택 순서, 클래스/레코드 Javadoc 수정
  내용이 기획서 그대로 반영됐다.
- `grep -rn "new JwtProperties"` (전체 `src/`)로 다른 곳에서 구 3-인자 생성자를 쓰는 코드가
  남아있는지 확인 — `JwtProviderTest.java` 2곳 외에는 없다. `AuthServiceTest`는 기획서가
  명시한 대로 `@Mock JwtProperties`를 쓰고 `.secret()`을 스텁하지 않아 변경이 불필요했고
  실제로 손대지 않았다.
- `grep -rln "JWT_SECRET"` (repo 전체, `.yml`/`.yaml`/`.md`/`.json`)로 다른 워크플로우/설정에
  구 단일 시크릿 이름이 남아있는지 확인 — 기획서 본문(의도적으로 과거형 서술) 외에는 없다.
  `.github/workflows/` 아래 CI 워크플로우는 `ci.yml`이 유일하고(`#51`의 배포 워크플로우는
  아직 존재하지 않음), 그 안의 더미 값도 `JWT_ACCESS_TOKEN_SECRET`/
  `JWT_REFRESH_TOKEN_SECRET` 두 줄로 정확히 갱신됐다.
- `application.yml`(git 추적 대상)에는 애초 `jwt.*` 설정이 없다 — 로컬 값은 전부
  `application-dev.yml`(gitignored)에만 있어 지시받은 대로 diff에서 보이지 않는 부분은
  리뷰 대상에서 제외했다.
- `JwtProviderTest.java`의 신규 헬퍼 `forgeTokenWithClaimTypeButWrongKey`가 만드는 위조
  토큰을 직접 추적: `tokenType` 클레임은 기대값과 **일치**시키고 서명 키만 반대쪽 키로 바꿔서
  만든다. 이 토큰을 `parseAccessToken`/`getUserIdFromRefreshToken`에 넣으면
  `claims(token, expectedTokenType)`가 `key(expectedTokenType)`(자신의 access/refresh 키)로
  `verifyWith`를 시도하는데, 토큰은 반대쪽 키로 서명됐으므로 `parseSignedClaims()`가
  클레임을 읽기도 전에 서명 불일치로 `JwtException`을 던진다 — `tokenType` 클레임 값은
  기대값과 같으므로 클레임 검사(코드상 서명 검증 다음 단계)는 통과할 조건이라, 이 테스트가
  통과한다면 그 원인은 오직 서명 검증 실패뿐이다. 즉 "서명 검증이 1차 방어선"이라는
  기획서의 핵심 주장을 실제로 증명하는 테스트다(클레임 검사만으로 우연히 통과하는 경우가
  아님).
- checkstyle 규칙(100자 제한, import 정렬) 위반 여부를 실제 문자 길이 기준으로 다시 측정—
  Javadoc의 한글 텍스트를 바이트 길이로 잘못 셀 경우 오탐이 나므로 `String.length()`
  (UTF-16 코드 유닛, Java `char` 개수와 동일 기준)로 재측정했다. 변경된 3개 Java 파일 모두
  100자를 넘는 줄이 없다. import 순서도 정적 import → 일반 import 그룹, 알파벳 순 정렬을
  그대로 지킨다.
- 테스트 시크릿 문자열(`ACCESS_SECRET`/`REFRESH_SECRET` 등) 길이를 바이트 단위로 계산 —
  최소 53바이트로 HMAC-SHA 최소 요구치(32바이트) 이상이라 `Keys.hmacShaKeyFor()`가 예외 없이
  키를 생성한다.

## Critical
없음 — 위 "리뷰 범위 확인"에서 서술한 대로 키 선택 로직의 모든 실제 호출 경로(4곳: AT
발급/파싱, RT 발급/파싱)를 추적했고, 서명 검증을 우회하거나 두 키가 뒤섞여 쓰이는 경로를
찾지 못했다.

## High
없음 — 기획서 범위를 벗어난 변경(새 엔드포인트, 인증 흐름 변경 등)이 없는지 diff 4개 파일
전체를 확인했고, `AuthController`/`AuthService`/`SecurityConfig`/`JwtAuthenticationFilter`
등 이 이슈가 건드리지 않기로 한 파일은 실제로 diff에 없다(`git diff --stat`으로 재확인).

## Medium
없음 — 기존 컨벤션(코드 스타일, 테스트 구조, Javadoc 배치) 위반을 확인했으나 해당 사항
없음. `R2Properties`/`NeisProperties`와 비교해 필드 순서(민감정보 필드를 앞쪽에 묶고 나머지를
뒤에 배치)가 크게 어긋나지 않고, `@Param` Javadoc 순서도 레코드 필드 선언 순서와 일치한다.

## Low

### 1. 🟢 Low — `key(String tokenType)`의 암묵적 else 분기가 알 수 없는 값도 조용히 refresh 키로 처리한다

**문제**: `src/main/java/com/remake/gone/common/security/JwtProvider.java:131-136`

```java
private SecretKey key(String tokenType) {
  String secret = TOKEN_TYPE_ACCESS.equals(tokenType)
      ? jwtProperties.accessTokenSecret()
      : jwtProperties.refreshTokenSecret();
  return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
}
```

`tokenType`이 `"access"`와 같은지만 검사하고, 그 외 모든 값(`"refresh"`, 오타,
`null`, 향후 추가될 제3의 토큰 타입 등)을 전부 refresh 키로 매핑한다. 지금은 이 메서드를
부르는 곳이 4곳뿐이고(`createAccessToken`/`createRefreshToken`/`claims` 내부 2회 호출
경로), 모두 클래스 내부의 `TOKEN_TYPE_ACCESS`/`TOKEN_TYPE_REFRESH` 상수만 넘기므로 실제
오작동 사례는 없다. 다만 향후 이 메서드에 세 번째 토큰 종류(예: 이메일 인증 토큰)를 추가하며
새 문자열 상수를 넘기는 실수를 하면, 컴파일 에러도 런타임 예외도 없이 refresh 키로 서명/검증
되어버린다 — 이 이슈가 막으려던 "키가 뒤섞이는" 상황을 코드 자체가 조용히 재현할 수 있는
구조다. 오늘 시점에는 재현 불가능한 잠재적 리스크이며, 실제 발생 시에도 즉시 auth 관련 테스트
실패로 드러날 가능성이 높다는 점에서 심각도는 낮게 본다.

**해결 방안**:
1. 삼항 연산자를 `if/else if` + 알 수 없는 값에 대한 명시적 예외(`IllegalArgumentException`
   또는 `IllegalStateException`)로 바꾼다 — 잘못된 `tokenType`이 들어오면 즉시 실패해서
   원인을 바로 찾을 수 있다. 다만 지금은 호출부가 전부 내부 상수뿐이라 실질적으로 트리거될
   경로가 없어 코드량만 늘어나는 방어적 코드가 될 수 있다.
2. 현재 상태를 유지한다 — 호출부가 이 클래스 내부로 완전히 통제되고(외부에서 임의
   문자열을 넘길 수 없음), 두 상수 외 다른 값이 들어오는 경로 자체가 존재하지 않는다.
   추가 방어 코드 없이도 "이 메서드를 호출하는 새 코드를 작성할 때 상수를 재사용한다"는
   기존 관례로 충분히 안전하다고 볼 수 있고, 기획서에 명시된 구현과 정확히 일치시켜
   리뷰/승인 범위를 벗어나지 않는 이점이 있다.

이번 PR을 막을 정도의 문제는 아니라고 판단해 병합 자체를 지연시키지 않되, 다음에 토큰
종류를 하나라도 더 추가하는 변경이 있을 때 이 메서드부터 다시 살펴보는 것을 권장한다.

## 반영 결과 (9단계 자체 점검 직후 즉시 반영)
- 1. 🟢 Low — **반영**: 해결 방안 1(명시적 분기 + 알 수 없는 값에 대한
  `IllegalArgumentException`) 채택. 비용이 낮고, 세 번째 토큰 종류를 추가할 때 발생할
  실수를 컴파일 타임이 아니라도 최소한 즉시 예외로 드러나게 한다.
