# #142 Aligo SMS 발송 실패 로그 전화번호 노출 — QA 결과

관련 기획서: [142-auth-sms-log-pii.md](./142-auth-sms-log-pii.md)
관련 코드 리뷰: [142-auth-sms-log-pii-code-review.md](./142-auth-sms-log-pii-code-review.md)

## 검증 범위
이번 변경은 새 엔드포인트나 정책 변경 없이 `AligoSmsSender.send()`의 에러 로그 문구
1줄만 바꾼다. 정상/에러 케이스 검증은 아래와 같이 진행했다.

## 로컬 검증
- `./gradlew build` 전체(컴파일 + 테스트 + checkstyle) 통과 확인.
- `./gradlew test --tests AligoSmsSenderTest` 개별 실행 통과 확인, 신규 테스트
  `doesNotLogPhoneNumberOnServerError`가 `RestClientException` 경로에서 로그 이벤트가
  `phoneNumber` 값을 포함하지 않음을 검증.
- `./gradlew checkstyleMain` 통과 확인.

## 실서버 기동 검증 — 미실시(근거)
기획서에 정의된 엔드포인트가 없고(내부 에러 로그 문구 변경), 이 로그는 `AligoSmsSender`가
활성화되는 비-`dev` 프로필(`staging`/`prod`)에서 실제 Aligo API 호출이
`RestClientException`으로 실패해야만 찍힌다. 실서버를 기동해 이 경로를 재현하려면
Aligo API 호출을 실제로 실패시켜야 하는데(네트워크 차단, 잘못된 인증키 등), 운영 연동
API를 상대로 강제 실패를 유발하는 것은 부작용 위험이 있어 시도하지 않았다. 대신
`MockRestServiceServer`로 동일한 실패 응답을 흉내 낸 단위 테스트가 정확히 이 로그 문구를
캡처해 검증하므로, 실서버 기동 없이도 변경 사항이 의도대로 동작함을 확인했다고 판단한다.
(보스 확인 후 실서버 검증 생략에 동의 — 2026-09-09)

## 심각도별 정리
- **Critical**: 없음.
- **High**: 없음.
- **Medium**: 없음.
- **Low**: 없음(코드 리뷰 단계에서 발견된 Low 2건은
  [142-auth-sms-log-pii-code-review.md](./142-auth-sms-log-pii-code-review.md)에 이미
  정리했고, 둘 다 기존 파일의 기존 패턴을 그대로 따른 것이라 이번 PR 범위 밖으로 판단해
  별도 조치하지 않는다).

## 결론
로컬 빌드/테스트/checkstyle 모두 통과. 실서버 기동 검증은 위 근거로 생략하고 단위 테스트로
대체했다. 병합을 막을 문제는 발견되지 않았다.
