# #144 Aligo SMS API 응답 바디 null 시 NPE 발생 — 기획서

관련 이슈: [#144 fix: PR #140 리뷰 지적사항 수정 (SMS 로그 PII/NPE, Javadoc 불일치)](https://github.com/GBSW-ReMake/GONE-server-V1/issues/144)
관련 PR(원 지적 출처): [#140 dev → staging 승격](https://github.com/GBSW-ReMake/GONE-server-V1/pull/140) — Copilot 리뷰
선행 코드: [`AligoSmsSender`](../../../src/main/java/com/remake/gone/sms/AligoSmsSender.java)

## 개요/목적
`dev → staging` 승격 PR(#140)의 Copilot 리뷰에서 이미 `dev`에 머지된 코드에 대해
"Changes recommended"로 4건이 지적됐다. 이 중 스케줄러 핸들러 미등록 건은 **#141**,
SMS 에러 로그의 전화번호 평문 노출 건은 **#142**, `OutingLocationRepository` Javadoc
불일치는 **#143**으로 이미 각각 별도 이슈·브랜치로 진행 중이라(#141은 PR #145 리뷰 대기,
#142는 PR #146 리뷰 대기, #143은 워크트리에서 구현·코드리뷰까지 진행됨), 이 이슈(#144)에는
**남은 1건 — `AligoSmsSender`의 null 응답 NPE**만 다룬다. 새 엔드포인트나 정책 변경 없이
기존 코드의 결함만 고친다.

## 변경 대상
- `src/main/java/com/remake/gone/sms/AligoSmsSender.java:36-47`

**변경 전**:
```java
try {
  body = aligoRestClient.post()
      .uri("/send/")
      .contentType(MediaType.APPLICATION_FORM_URLENCODED)
      .body(formBody(phoneNumber, message))
      .retrieve()
      .body(JsonNode.class);
} catch (RestClientException e) {
  log.error("Aligo SMS API 호출 실패", e);
  throw new CustomException(AuthErrorCode.SMS_SEND_FAILED);
}

int resultCode = body.path("result_code").asInt();
```
`RestClient` 응답 바디가 비어 있으면 `.body(JsonNode.class)`가 `null`을 반환하고, 바로 다음
줄의 `body.path("result_code")`에서 `NullPointerException`이 발생한다. `RestClientException`
이 아닌 `NullPointerException`이 그대로 튀어나가 `CustomException(SMS_SEND_FAILED)`로
감싸지지 않는다 — 즉 `SmsSender` 인터페이스 계약(실패 시 `CustomException`)을 어기고
처리되지 않은 예외가 호출부(`PhoneAuthService`)까지 전파된다.

**변경 후**:
```java
if (body == null || !body.has("result_code")) {
  log.error("Aligo SMS API 응답 바디 없음 또는 result_code 누락");
  throw new CustomException(AuthErrorCode.SMS_SEND_FAILED);
}

int resultCode = body.path("result_code").asInt();
```
`body`가 `null`이면 `RestClientException`과 동일하게 `CustomException(SMS_SEND_FAILED)`로
명시 처리한다(NPE로 새는 대신 실패 케이스로 정상 합류).

**범위 확장(코드 리뷰 반영, 2026-09-09)**: 코드 리뷰에서 `body`가 non-null이어도
`result_code` 필드 자체가 없으면 `.path(...).asInt()`가 기본값 `0`을 반환해 실패를 성공으로
오판할 수 있다는 Medium 지적이 나왔다. `body == null` 체크와 같은 자리에서 함께 방어할 수
있는 간단한 조건 추가라 이번 이슈 범위로 포함해 같이 고쳤다(`!body.has("result_code")` 조건
추가).

- 영향 코드: `src/main/java/com/remake/gone/sms/AligoSmsSender.java`
- 영향 테스트: `src/test/java/com/remake/gone/sms/AligoSmsSenderTest.java`
  - `RestClient`가 성공 응답(2xx)이지만 빈 바디를 반환하는 경우(`MockRestServiceServer`의
    `withSuccess()`에 바디를 주지 않는 방식)를 흉내 내어, `CustomException(SMS_SEND_FAILED)`를
    던지는지 검증하는 케이스를 추가한다.
  - `result_code` 필드가 없는 JSON 바디를 흉내 내어 동일하게 검증하는 케이스를 추가한다.
  - 코드 리뷰에서 함께 지적된 `test-convention.md`의 `@Nested` 그룹화 규칙 미준수도, 이
    파일이 `send()` 메서드 하나만 다루므로 전체를 `@Nested class Send`로 감싸 같이
    정리한다(기존 3개 테스트 포함).

## 리스크 및 고려사항
- API 디자인 원칙([api-design.md](../../rules/api-design.md))은 새 엔드포인트가 없으므로
  해당 없음.
- 하위 호환성: `SmsSender.send()`가 던지는 예외 타입은 이미 계약상
  `CustomException(SMS_SEND_FAILED)` 하나뿐이었다(NPE는 그 계약을 벗어난 버그였다). 이번
  수정은 실제 예외 타입을 계약대로 되돌리는 것이라 호출부(`PhoneAuthService`) 동작에
  영향 없다.
- 범위: PR #140 리뷰에서 지적된 4건 중 이 NPE 1건만 다룬다. 나머지 3건(#141/#142/#143)은
  각각 별도 이슈로 이미 진행 중이다. 이 문서는 원래 SMS 로그 PII(#142로 분리)와 Javadoc
  불일치(#143으로 분리) 항목도 포함하고 있었으나, 두 항목 모두 별도 이슈가 이미 열려 있어
  중복을 피하려고 이번에 범위를 좁혔다(파일명도 `144-bug-pr140-review-fixes.md`에서
  `144-bug-sms-npe.md`로 변경).
- 코드 리뷰에서 발견된 `NeisClient.java`의 동일 유형(2xx + 빈 바디 → NPE) 버그는 다른
  도메인 파일이라 이번 이슈 범위에 포함하지 않고 별도 백로그 이슈로 등록한다.
