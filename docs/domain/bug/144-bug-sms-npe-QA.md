# #144 SMS/NEIS 외부 API 응답 바디 null 시 NPE 발생 — QA 결과

관련 기획서: [144-bug-sms-npe.md](./144-bug-sms-npe.md)
관련 코드 리뷰: [144-bug-sms-npe-code-review.md](./144-bug-sms-npe-code-review.md)

## 검증 범위
이번 변경은 새 엔드포인트나 정책 변경 없이 아래 2건의 방어 로직을 추가한다.
1. `AligoSmsSender.send()`: `body == null || !body.has("result_code")` 체크
2. `NeisClient.fetch()`: `root == null` 체크 (코드 리뷰에서 발견해 보스 지시로 범위 포함)

## 로컬 검증
- `./gradlew build` 전체(컴파일+테스트+checkstyle) 통과 — 두 파일의 변경을 모두 반영한
  최종 상태 기준으로 재확인 완료.
- `AligoSmsSenderTest`(총 5개, `@Nested class Send`로 정리):
  - 기존 3개(정상 응답, `result_code < 0`, 네트워크 오류)는 회귀 없이 그대로 통과.
  - 신규 `throwsWhenBodyIsEmpty()` — `withSuccess()`(빈 바디)로 `body == null` 분기 검증.
  - 신규 `throwsWhenResultCodeMissing()` — `result_code` 필드 없는 JSON으로
    `!body.has("result_code")` 분기 검증.
- `NeisClientTest`(총 5개, 기존 flat `@Test` 구조 유지):
  - 기존 4개(정상 응답, 데이터 없음, 진짜 에러, 네트워크 오류)는 회귀 없이 그대로 통과.
  - 신규 `throwsWhenBodyIsEmpty()` — `withSuccess()`(빈 바디)로 `root == null` 분기 검증.
- 코드 리뷰 단계에서 Spring Web 소스(`DefaultRestClient`/`IntrospectingClientHttpResponse`)를
  추적해, "2xx + 빈 바디 → `.body(JsonNode.class)`가 예외 없이 `null` 반환" 전제 자체가
  정확함을 확인했다(두 클라이언트 모두 같은 `RestClient` 메커니즘을 사용하므로 동일하게
  적용됨).

## 실서버 기동 검증 — 미실시(근거)
기획서에 정의된 엔드포인트가 없고(내부 NPE 방어 로직 추가), 두 분기 모두 각 외부 API(Aligo,
NEIS)가 실제로 2xx와 함께 비정상 바디를 반환해야만 실행된다. 실서버로 이 경로를 재현하려면
운영 연동 API의 응답을 강제로 비정상화해야 해서 시도하지 않았다. 대신 코드 리뷰에서
라이브러리 소스까지 확인해 검증한 단위 테스트로 대체한다(#142 QA 문서에서 채택한 것과 동일한
판단 기준).

## 심각도별 정리
- **Critical**: 없음.
- **High**: 없음.
- **Medium**: 1건 — `AligoSmsSender`, `body`는 있는데 `result_code` 필드 자체가 없으면
  실패를 성공으로 오판할 수 있음. **이 브랜치에서 조치 완료**.
- **Low**: 2건
  1. `AligoSmsSenderTest`가 `@Nested` 그룹화 규칙 미준수. **이 브랜치에서 조치 완료**
     (`@Nested class Send`로 정리).
  2. (`NeisClient` 추가분 리뷰) `AligoSmsSender`/`NeisClient` 양쪽에 동일한 null 방어
     로직이 공통 메커니즘 없이 클라이언트별로 개별 구현됨 — 세 번째 외부 API 클라이언트가
     추가될 때 재발 가능성이 있는 아키텍처 관찰. 순수 리팩터링 성격이라 이번 PR 범위 밖으로
     판단해 조치하지 않고 참고 사항으로만 기록(코드 리뷰 문서 참고).
- **참고**: `NeisClient.java`의 동일 유형(2xx + 빈 바디 → NPE) 버그는 원래 별도 백로그
  이슈로 분리할 계획이었으나, 보스 지시로 이번 이슈에 함께 포함해 위 Medium과 같은 방식으로
  조치 완료.

## 결론
로컬 빌드/테스트/checkstyle 모두 통과(SMS + NEIS 양쪽 수정 반영 후 재확인 완료). 실서버
기동 검증은 위 근거로 생략하고, 라이브러리 소스까지 확인한 단위 테스트로 대체했다. 병합을
막을 문제는 발견되지 않았다. Low 2번(공통 방어 로직 부재)만 향후 참고 사항으로 남기고
당장 별도 이슈로 등록하지는 않는다.
