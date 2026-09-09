# #142 Aligo SMS 발송 실패 로그에 전화번호 평문 노출 — 기획서

관련 이슈: [#142 fix(auth): Aligo SMS 발송 실패 로그에 전화번호 평문 노출](https://github.com/GBSW-ReMake/GONE-server-V1/issues/142)
관련 PR(원 지적 출처): [#140 dev → staging 승격](https://github.com/GBSW-ReMake/GONE-server-V1/pull/140) — Copilot 리뷰
선행 코드: [`AligoSmsSender`](../../../src/main/java/com/remake/gone/sms/AligoSmsSender.java)

## 개요/목적
`AligoSmsSender.send()`가 `RestClientException`으로 실패했을 때
`log.error("Aligo SMS API 호출 실패: phoneNumber={}", phoneNumber, e)`로 수신자 전화번호를
그대로 로그에 남긴다. 운영 로그/모니터링 시스템을 통해 개인정보(전화번호)가 노출될 수 있으므로
이 로그 지점에서 전화번호를 제외한다. 새 엔드포인트나 정책 변경 없이 기존 로그 문구 1건만
고친다.

**참고**: 같은 파일의 NPE 이슈(`body`가 `null`일 때 `body.path(...)` 호출)와
`OutingLocationRepository` Javadoc 불일치는 별도 이슈 [#144](https://github.com/GBSW-ReMake/GONE-server-V1/issues/144)에서
다룬다. #144는 원래 이 로그 노출 건도 포함하고 있었으나, 이슈 #142가 별도로 이미 열려 있어
로그 노출 수정은 #142로, 나머지(NPE·Javadoc)는 #144로 범위를 나눈다. #144 문서는 이 결정에
맞춰 별도로 갱신한다.

## 변경 대상
- `src/main/java/com/remake/gone/sms/AligoSmsSender.java:43`

**변경 전**:
```java
} catch (RestClientException e) {
  log.error("Aligo SMS API 호출 실패: phoneNumber={}", phoneNumber, e);
  throw new CustomException(AuthErrorCode.SMS_SEND_FAILED);
}
```

**변경 후**:
```java
} catch (RestClientException e) {
  log.error("Aligo SMS API 호출 실패", e);
  throw new CustomException(AuthErrorCode.SMS_SEND_FAILED);
}
```

- 로그 메시지에서 `phoneNumber`를 마스킹하지 않고 완전히 제외한다.
  - 이유: 이 프로젝트에 기존 마스킹 유틸리티가 없고, 실패 원인 파악에는 예외 스택트레이스와
    Aligo 응답 코드(같은 파일 49번 줄의 `result_code`/`message` 로그)로 충분하다 — 전화번호가
    로그에 있어야만 파악되는 정보가 아니다. 새 마스킹 로직을 도입하는 대신 제외로 단순하게
    처리한다.
- 같은 파일 49번 줄(`log.error("Aligo SMS 발송 실패: result_code={}, message={}", ...)`)은
  전화번호를 남기지 않으므로 변경 대상이 아니다.
- `sms` 패키지 내 다른 로그 지점(`ConsoleSmsSender.send()`의 `System.out.println`)은
  `@Profile("dev")` 전용으로 로컬 개발자 콘솔에만 찍히고 운영 로그/모니터링 시스템으로
  나가지 않으므로 이번 수정 범위에 포함하지 않는다.

## 영향 받는 기존 코드/테스트
- 영향 코드: `src/main/java/com/remake/gone/sms/AligoSmsSender.java` (로그 메시지 1줄만 변경,
  동작/예외 처리 로직 변경 없음)
- 영향 테스트: `src/test/java/com/remake/gone/sms/AligoSmsSenderTest.java` — 기존
  `throwsOnServerError()`는 로그 내용을 검증하지 않으므로 그대로 통과한다. 로그에
  `phoneNumber`가 더 이상 남지 않는지 검증하는 케이스를 하나 추가한다(예: Logback
  `ListAppender`로 로그 이벤트를 캡처해 메시지에 `PHONE_NUMBER` 값이 없는지 확인).

## 리스크 및 고려사항
- API 디자인 원칙([api-design.md](../../rules/api-design.md))은 새 엔드포인트가 없으므로
  해당 없음.
- 하위 호환성: 로그 포맷 변경은 클라이언트에 영향 없음. 로그를 파싱하는 별도 모니터링 룰이
  있다면 영향받을 수 있으나, 현재 프로젝트에 그런 룰이 문서화되어 있지 않아 고려 대상에서
  제외한다.
- 범위: PR #140 리뷰에서 지적된 4건 중 이 로그 노출 1건만 다룬다. 나머지 3건(스케줄러
  핸들러 미등록 → #141, NPE·Javadoc → #144)은 각각 별도 이슈로 진행한다.
