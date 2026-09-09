# #152 Aligo 응답 Content-Type 오탐 수정 — 기획서

관련 이슈: [#152 fix(sms): Aligo 응답 Content-Type이 text/html일 때 파싱 실패로
SMS_SEND_FAILED(502) 오탐](https://github.com/GBSW-ReMake/GONE-server-V1/issues/152)
계기: staging(`gone-dev.gbsw.hs.kr`) 실제 회원가입 인증번호 발송 테스트 중 재현 확인

## 개요/목적
`AligoSmsSender.send()`가 `RestClient.retrieve().body(JsonNode.class)`로 Aligo 응답을
파싱하는데, Aligo가 JSON 바디를 보내면서 `Content-Type`을 `text/html;charset=UTF-8`로
잘못 내려준다. Spring의 `RestClient`는 선언된 `Content-Type`으로 메시지 컨버터를 고르므로,
실제 바디가 JSON이어도 `UnknownContentTypeException`을 던지고, 이게 기존
`catch (RestClientException e)`에 잡혀 `SMS_SEND_FAILED`(502)로 오탐된다. 실제로는 Aligo가
SMS를 정상 발송했는데도 클라이언트는 "발송 실패"로 안내받는다. 이번 수정은 응답을
`Content-Type`과 무관하게 문자열로 받아 직접 JSON으로 파싱하도록 바꿔 이 오탐을 없앤다.
API 계약(엔드포인트, 요청 폼, 에러코드 체계)은 그대로 유지한다.

## 기존 로직 수정 — 변경 전/후

**변경 전** (`AligoSmsSender.send()`):
```java
JsonNode body;
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
```
`.body(JsonNode.class)`가 응답의 `Content-Type` 헤더로 컨버터를 골라, `text/html`이면
바디가 JSON이어도 파싱을 거부하고 예외를 던진다.

**변경 후**: 응답을 `String`으로 받아 `Content-Type`을 거치지 않고 프로젝트가 이미 쓰는
`tools.jackson.databind.ObjectMapper`(`NeisClient`와 동일 패턴)로 직접 `readTree`한다.
```java
String rawBody;
try {
  rawBody = aligoRestClient.post()
      .uri("/send/")
      .contentType(MediaType.APPLICATION_FORM_URLENCODED)
      .body(formBody(phoneNumber, message))
      .retrieve()
      .body(String.class);
} catch (RestClientException e) {
  log.error("Aligo SMS API 호출 실패", e);
  throw new CustomException(AuthErrorCode.SMS_SEND_FAILED);
}

JsonNode body = parseBody(rawBody);
if (body == null || !body.hasNonNull("result_code")) {
  log.error("Aligo SMS API 응답 바디 없음 또는 result_code 누락");
  throw new CustomException(AuthErrorCode.SMS_SEND_FAILED);
}
// 이후 result_code 판정 로직은 그대로 유지

private JsonNode parseBody(String rawBody) {
  if (rawBody == null || rawBody.isBlank()) {
    return null;
  }
  try {
    return objectMapper.readTree(rawBody);
  } catch (JacksonException e) {
    log.error("Aligo SMS API 응답 파싱 실패");
    return null;
  }
}
```
- `ObjectMapper`는 생성자 주입(`@RequiredArgsConstructor`가 이미 필드 기반으로 생성자를
  만들어주므로 필드 추가만 하면 됨). Spring Boot의 Jackson 3 자동 설정 빈을 그대로 쓴다
  (`NeisClient`, `RestAuthenticationEntryPoint`와 동일).
- 파싱 실패 시에도 (기존과 동일하게) `SMS_SEND_FAILED`로 귀결시켜, 이 메서드가 던지는
  예외 종류는 하나로 유지한다 — 호출부(`PhoneAuthService`)는 변경 없음.
- `result_code` 판정(음수면 실패, `message`/`msg_id` 처리 등) 로직은 기존 그대로 재사용한다
  — `JsonNode`를 다루는 부분이라 파싱 방식만 바뀌었을 뿐 하위 로직은 그대로다.

## 영향받는 기존 코드/테스트
- `AligoSmsSender.java`: `send()`의 응답 수신 방식(`.body(JsonNode.class)` →
  `.body(String.class)` + 수동 파싱)만 변경. `ObjectMapper` 필드 추가.
- `AligoSmsSenderTest.java`: 기존 6개 테스트 케이스는 전부 `MediaType.APPLICATION_JSON`으로
  응답을 흉내 내므로 그대로 통과해야 한다(회귀 확인 대상). 새 케이스 추가:
  - `Content-Type`이 `text/html`이고 바디가 정상 JSON(`result_code>=0`)이면 예외 없이
    끝난다(이번 버그의 핵심 재현 케이스)
  - `Content-Type`이 `text/html`이고 바디가 실패 JSON(`result_code<0`)이면 여전히
    `SMS_SEND_FAILED`를 던진다
  - 바디가 JSON이 아닌 진짜 깨진 문자열이면 `SMS_SEND_FAILED`를 던진다(파싱 자체 실패)

## 리스크 및 고려사항
- Aligo API 엔드포인트/요청 폼/에러코드(`AUTH_009`, 502)는 그대로 유지한다 — 클라이언트가
  보는 계약은 변하지 않는다(단, 이번 수정으로 오탐이 없어져 실제로는 502를 받는 빈도가
  줄어든다).
- `Content-Type`을 무시하고 항상 JSON으로 파싱을 시도하는 방식이라, Aligo가 정말로 HTML
  에러 페이지(장애 페이지 등 실제 비-JSON 응답)를 반환하는 경우도 있을 수 있다 — 이 경우도
  `readTree` 파싱 자체가 실패해 `SMS_SEND_FAILED`로 귀결되므로 기존 동작(발송 실패 처리)과
  다르지 않다. 별도 분기 없이 기존 예외 처리 경로로 자연스럽게 흡수된다.
