# #144 SMS/NEIS 외부 API 응답 바디 null 시 NPE 발생 — 기획서

관련 이슈: [#144 fix: PR #140 리뷰 지적사항 수정 (SMS 로그 PII/NPE, Javadoc 불일치)](https://github.com/GBSW-ReMake/GONE-server-V1/issues/144)
관련 PR(원 지적 출처): [#140 dev → staging 승격](https://github.com/GBSW-ReMake/GONE-server-V1/pull/140) — Copilot 리뷰
선행 코드: [`AligoSmsSender`](../../../src/main/java/com/remake/gone/sms/AligoSmsSender.java),
[`NeisClient`](../../../src/main/java/com/remake/gone/neis/NeisClient.java)

## 개요/목적
`dev → staging` 승격 PR(#140)의 Copilot 리뷰에서 이미 `dev`에 머지된 코드에 대해
"Changes recommended"로 4건이 지적됐다. 이 중 스케줄러 핸들러 미등록 건은 **#141**,
SMS 에러 로그의 전화번호 평문 노출 건은 **#142**, `OutingLocationRepository` Javadoc
불일치는 **#143**으로 이미 각각 별도 이슈·브랜치로 진행 중이라(#141은 PR #145 리뷰 대기,
#142는 PR #146 리뷰 대기, #143은 워크트리에서 구현·코드리뷰까지 진행됨), 이 이슈(#144)는
원래 **남은 1건 — `AligoSmsSender`의 null 응답 NPE**만 다루기로 좁혔다.

이후 코드 리뷰 과정에서 발견된 것들도 보스 지시로 이번 이슈에 함께 포함했다:
1. `AligoSmsSender`의 `result_code` 필드 누락 시 오판 방지 (아래 1번, 원래 계획 범위)
2. `NeisClient`의 동일 유형(null 바디) NPE (아래 2번, 코드 리뷰에서 발견해 범위 추가)

새 엔드포인트나 정책 변경 없이 기존 코드의 결함만 고친다.

## 1. `AligoSmsSender` — null 응답 바디 / `result_code` 누락 시 NPE·오판

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

또한 `body`가 non-null이어도 `result_code` 필드 자체가 없으면 `.path(...).asInt()`가 기본값
`0`을 반환해, `resultCode < 0` 조건을 만족하지 않으므로 실패를 성공으로 오판한다(코드 리뷰
Medium 지적, 아래 "변경 후"에서 같은 자리에서 함께 방어).

**변경 후**:
```java
if (body == null || !body.has("result_code")) {
  log.error("Aligo SMS API 응답 바디 없음 또는 result_code 누락");
  throw new CustomException(AuthErrorCode.SMS_SEND_FAILED);
}

int resultCode = body.path("result_code").asInt();
```
`body`가 `null`이거나 `result_code` 필드가 없으면 `RestClientException`과 동일하게
`CustomException(SMS_SEND_FAILED)`로 명시 처리한다(NPE·오판 대신 실패 케이스로 정상 합류).

- 영향 코드: `src/main/java/com/remake/gone/sms/AligoSmsSender.java`
- 영향 테스트: `src/test/java/com/remake/gone/sms/AligoSmsSenderTest.java`
  - `RestClient`가 성공 응답(2xx)이지만 빈 바디를 반환하는 경우(`MockRestServiceServer`의
    `withSuccess()`에 바디를 주지 않는 방식)를 흉내 내어, `CustomException(SMS_SEND_FAILED)`를
    던지는지 검증하는 케이스를 추가한다.
  - `result_code` 필드가 없는 JSON 바디를 흉내 내어 동일하게 검증하는 케이스를 추가한다.
  - 코드 리뷰에서 함께 지적된 `test-convention.md`의 `@Nested` 그룹화 규칙 미준수도, 이
    파일이 `send()` 메서드 하나만 다루므로 전체를 `@Nested class Send`로 감싸 같이
    정리한다(기존 3개 테스트 포함).

## 2. `NeisClient` — null 응답 바디 NPE (코드 리뷰에서 발견, 보스 지시로 범위 포함)

**변경 전**: `fetch()`가 `RestClientException` 처리 이후 곧바로 `parseRows(root, ...)`를
호출하고, 그 안에서 `root.path("RESULT")`(`NeisClient.java:76`)가 `root`를 역참조한다.
NEIS API가 200과 함께 빈 바디를 반환하면(`.body(JsonNode.class)`가 `null` 반환)
`CustomException(NeisErrorCode.EXTERNAL_API_ERROR)` 대신 처리되지 않은
`NullPointerException`이 그대로 전파된다 — `AligoSmsSender`에 대해 고친 것과 정확히 같은
결함이다.

**변경 후**: `catch (RestClientException e)` 블록과 `parseRows(...)` 호출 사이에
`root == null` 체크를 추가해 `CustomException(NeisErrorCode.EXTERNAL_API_ERROR)`로 명시
처리한다.

- 영향 코드: `src/main/java/com/remake/gone/neis/NeisClient.java`
- 영향 테스트: `src/test/java/com/remake/gone/neis/NeisClientTest.java` — `RestClient`가
  성공 응답(2xx)이지만 빈 바디를 반환하는 경우를 흉내 내어
  `CustomException(EXTERNAL_API_ERROR)`를 던지는지 검증하는 케이스를 추가한다. 기존 파일이
  이미 flat `@Test` 구조라(`@Nested` 컨벤션 미적용), 이번 추가도 기존 스타일을 그대로
  따른다 — 파일 구조 정리는 이 이슈 범위 밖으로 별도 이슈 대상이다.

## 리스크 및 고려사항
- API 디자인 원칙([api-design.md](../../rules/api-design.md))은 새 엔드포인트가 없으므로
  해당 없음.
- 하위 호환성: `SmsSender.send()`/`NeisClient.fetch()` 모두 이미 계약상 던지는 예외 타입이
  하나씩 정해져 있었다(각각 `CustomException(SMS_SEND_FAILED)`,
  `CustomException(EXTERNAL_API_ERROR)`) — NPE는 그 계약을 벗어난 버그였다. 이번 수정은
  실제 예외 타입을 계약대로 되돌리는 것이라 각 호출부(`PhoneAuthService`, `meal`/`timetable`
  서비스) 동작에 영향 없다.
- 범위: PR #140 리뷰에서 지적된 4건 중 이 이슈는 원래 NPE 1건(`AligoSmsSender`)만 다룰
  계획이었다. 나머지 3건(#141/#142/#143)은 각각 별도 이슈로 이미 진행 중이다. 이 문서는
  원래 SMS 로그 PII(#142로 분리)와 Javadoc 불일치(#143으로 분리) 항목도 포함하고
  있었으나, 두 항목 모두 별도 이슈가 이미 열려 있어 중복을 피하려고 범위를 좁혔다(파일명도
  `144-bug-pr140-review-fixes.md`에서 `144-bug-sms-npe.md`로 변경).
- 범위 확장(보스 지시, 2026-09-09): 코드 리뷰 과정에서 발견된 `AligoSmsSender`의
  `result_code` 누락 오판(위 1번)과 `NeisClient`의 동일 유형 NPE(위 2번, 원래는 다른
  도메인이라 별도 백로그 이슈로 분리할 계획이었음)를 이번 이슈에 함께 포함해 고쳤다.
