# #144 Aligo SMS API 응답 바디 null 시 NPE 발생 — QA 결과

관련 기획서: [144-bug-sms-npe.md](./144-bug-sms-npe.md)
관련 코드 리뷰: [144-bug-sms-npe-code-review.md](./144-bug-sms-npe-code-review.md)

## 검증 범위
이번 변경은 새 엔드포인트나 정책 변경 없이 `AligoSmsSender.send()`에 `body == null` 방어
체크 1건을 추가한다. 정상/에러 케이스 검증은 아래와 같이 진행했다.

## 로컬 검증
- `./gradlew test --tests AligoSmsSenderTest` 통과. 신규 테스트
  `throwsWhenBodyIsEmpty()`가 `MockRestServiceServer`의 `withSuccess()`(200 OK, 빈 바디)로
  `body == null` 분기를 실제로 태워 `CustomException(SMS_SEND_FAILED)`를 던지는지 검증한다.
  코드 리뷰 단계에서 Spring Web 소스(`DefaultRestClient`/`IntrospectingClientHttpResponse`)를
  추적해, 이 테스트가 흉내 내는 "2xx + 빈 바디 → `.body(JsonNode.class)`가 예외 없이 `null`
  반환" 전제 자체가 정확함을 이미 확인했다.
- `./gradlew checkstyleMain` 통과.
- `./gradlew build` 전체(컴파일+테스트+checkstyle) 통과.
- 기존 3개 테스트(정상 응답, `result_code < 0`, 네트워크 오류)는 이번 변경으로 회귀 없이
  그대로 통과한다 — 새로 추가한 `if (body == null)` 체크는 기존 분기들보다 앞에 있지만
  `body`가 non-null인 기존 케이스의 흐름에는 영향을 주지 않는다.

## 실서버 기동 검증 — 미실시(근거)
기획서에 정의된 엔드포인트가 없고(내부 NPE 방어 로직 추가), 이 분기는 `AligoSmsSender`가
활성화되는 비-`dev` 프로필에서 Aligo API가 실제로 2xx와 함께 빈 바디를 반환해야만 실행된다.
실서버로 이 경로를 재현하려면 운영 연동 API의 응답을 강제로 비정상화해야 해서 시도하지
않았다. 대신 코드 리뷰에서 라이브러리 소스까지 확인해 검증한 단위 테스트로 대체한다
(#142 QA 문서에서 채택한 것과 동일한 판단 기준).

## 심각도별 정리
- **Critical**: 없음.
- **High**: 없음.
- **Medium**: 1건 — `AligoSmsSender.java`, `body`는 있는데 `result_code` 필드 자체가
  없으면 `.asInt()`가 기본값 `0`을 반환해 실패를 성공으로 오판할 수 있음. 간단한 수정이라
  **이 브랜치에서 바로 조치**(`!body.has("result_code")` 조건 추가, 검증 테스트
  `throwsWhenResultCodeMissing` 추가).
- **Low**: 1건 — 신규 테스트가 `test-convention.md`의 `@Nested` 그룹화 규칙을 따르지
  않음. **이 브랜치에서 바로 조치**(`AligoSmsSenderTest` 전체를 `@Nested class Send`로
  정리).
- **참고(범위 밖)**: `NeisClient.java:48-61`에 동일 유형(2xx + 빈 바디 → NPE) 버그 발견.
  #144와 무관한 파일이라 이 브랜치에서 다루지 않고 별도 백로그 이슈로 분리 등록 예정
  (코드 리뷰 문서 참고).

## 결론
로컬 빌드/테스트/checkstyle 모두 통과(수정 반영 후 재확인 완료). 실서버 기동 검증은 위
근거로 생략하고, 라이브러리 소스까지 확인한 단위 테스트로 대체했다. 병합을 막을 문제는
발견되지 않았다. `NeisClient.java` 참고 사항만 백로그 이슈로 별도 등록해 후속 처리한다.
