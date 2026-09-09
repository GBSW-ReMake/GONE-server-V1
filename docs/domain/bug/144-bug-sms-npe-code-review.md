# #144 SMS/NEIS 외부 API 응답 바디 null 시 NPE 발생 — 코드 리뷰 결과

> 이 문서의 본문(범위/컨벤션 확인, 발견 사항 1~2)은 `AligoSmsSender` 수정만 있던 시점의
> 리뷰다. 이후 보스 지시로 `NeisClient`(위 참고 섹션에서 지적된 동일 유형 버그)까지 이슈
> 범위에 포함해 추가로 고쳤고, 그 부분의 리뷰 결과는 맨 아래
> "`NeisClient` 추가분 리뷰" 절에 별도로 기록한다.

리뷰 대상: `git diff dev...fix/#144-aligo-sms-npe` (커밋 3개 — 기획서, null 체크 구현, 테스트 추가)
리뷰 방식: [code-review-isolation.md](../../rules/code-review-isolation.md)에 따라 구현 맥락 없는
별도 에이전트(`code-review` 스킬)에 diff·기획서만 전달해 독립 리뷰.

## 범위/컨벤션 확인
- 기획서([144-bug-sms-npe.md](144-bug-sms-npe.md))가 명시한 범위(`AligoSmsSender`의 null
  바디 NPE 1건)를 벗어난 변경 없음. `#141`/`#142`/`#143` 관련 코드는 건드리지 않았다.
- Spring Web 7.0.8 소스(`DefaultRestClient.readWithMessageConverters`,
  `IntrospectingClientHttpResponse.hasEmptyMessageBody()`)를 직접 추적해 확인한 결과, 기획서의
  전제(2xx + 빈 바디 → `.body(JsonNode.class)`가 예외 없이 `null` 반환)가 정확하다. 응답이
  읽을 수 없는 형식인 경우는 Spring이 이미 `RestClientException`으로 감싸서 던지므로 기존
  `catch` 블록이 처리하며, 이번에 추가된 `null` 체크와 영역이 겹치지 않는다.
- 에러 코드 재사용(`AuthErrorCode.SMS_SEND_FAILED`)이 기존 `catch (RestClientException e)`
  블록과 동일해 `SmsSender` 계약(실패 시 항상 `CustomException` 하나만 던짐)을 그대로 유지한다.
- 신규 테스트(`throwsWhenBodyIsEmpty`)는 `MockRestServiceServer`의 `withSuccess()`(바디 없음)로
  실제로 `body == null` 경로를 태우는지 확인했다 — 기존 `catch` 블록으로 우회 통과하는 게
  아니라 이번에 추가한 분기를 실제로 검증한다.

## 발견 사항 및 조치

> **후속 조치(2026-09-09, 보스 지시)**: 아래 Medium 1건과 Low 1건은 "간단하면 이번 PR에서
> 바로 고친다"는 판단에 따라 이 브랜치에서 수정했다. NeisClient 참고 사항은 다른 파일이라
> 그대로 별도 백로그 이슈로 남긴다.

### 1. 🟡 Medium — `resultCode` 판정이 `body`가 non-null이어도 `result_code` 필드 자체가 없으면 조용히 성공 처리됨 → **조치 완료**

**문제**: `AligoSmsSender.java:52` (이번 diff로 수정되지 않은 기존 줄)
`int resultCode = body.path("result_code").asInt();` 는 `body`가 `null`이 아니어도 JSON에
`result_code` 키가 없으면 `JsonNode.path(...)`가 `MissingNode`를 반환하고 `.asInt()`는 기본값
`0`을 준다. `resultCode`가 `0`은 `< 0` 조건을 만족하지 않으므로 `AligoSmsSender.send()`가
예외 없이 정상 종료된다. 즉 Aligo가(또는 중간 프록시가) 2xx와 함께 `{}`나 예상과 다른 JSON
구조를 반환하면, 실제로는 문자가 발송되지 않았는데도 `PhoneAuthService`는 발송 성공으로
간주하고 쿨다운 등 후속 처리를 진행한다. 이번 이슈(#144)가 다루는 "`body`가 아예 null인 경우"
와는 다른 케이스지만, 같은 파싱 방어 로직에 인접해 있고 같은 실패 유형(엔진 응답이 기대와
다를 때 NPE 대신 그냥 통과)에 속한다.

**적용한 조치**: 방안 1을 택해 `if (body == null || !body.has("result_code"))`로 두 조건을
같은 자리에서 함께 방어하도록 수정했다. `result_code` 필드가 없는 JSON 바디를 흉내 낸 테스트
(`throwsWhenResultCodeMissing`)를 추가해 검증했다.

### 2. 🟢 Low — 신규 테스트가 `test-convention.md`의 `@Nested` 그룹화 규칙을 따르지 않음 → **조치 완료**

**문제**: [test-convention.md](../../rules/test-convention.md)는 "메서드 단위로 `@Nested`
클래스 + `@DisplayName("메서드명")`으로 그룹화"를 요구하는데, `AligoSmsSenderTest.java`는 이번
diff 이전부터 이미 4개(이번 추가분 포함 시) 테스트 메서드가 모두 최상위 레벨에 평면적으로
나열돼 있고 `@Nested` 그룹이 없다. 이번에 추가된 `throwsWhenBodyIsEmpty()`(91번째 줄)도 기존
파일 구조를 그대로 따라 최상위에 추가됐다. 이번 diff가 새로 만든 위반은 아니지만(기존 파일이
이미 컨벤션을 어기고 있었음), 신규 테스트도 같은 방식으로 추가되어 위반을 그대로 이어간다.

**적용한 조치**: 방안 1을 택해 `AligoSmsSenderTest` 전체(기존 3개 + 신규 2개, 총 5개
테스트)를 `@Nested @DisplayName("send") class Send`로 감쌌다. 이 파일은 `send()` 메서드
하나만 다루므로 리팩터링 범위가 파일 전체에 그치고, `GlobalExceptionHandlerTest`와 동일한
패턴이 된다.

## 참고: 이번 PR 범위 밖에서 발견한 동일 유형 버그 (별도 이슈 권장)
`src/main/java/com/remake/gone/neis/NeisClient.java:48-61`의 `fetch()`도 동일한 버그 유형을
갖고 있다 — `.body(JsonNode.class)`가 2xx + 빈 바디에서 `null`을 반환할 수 있는데, `catch
(RestClientException e)` 이후 `root`를 곧바로 `parseRows(root, ...)`에 넘기고, 그 안에서
`root.path("RESULT")`(`NeisClient.java:76`)가 `root`를 역참조한다. NEIS API가 200과 함께 빈
바디를 반환하면 `CustomException(NeisErrorCode.EXTERNAL_API_ERROR)` 대신 처리되지 않은
`NullPointerException`이 그대로 전파된다 — #144가 `AligoSmsSender`에 대해 고친 것과 정확히
같은 결함이다. 이 이슈(#144)는 기획서상 `AligoSmsSender` 하나로 범위가 명시적으로 좁혀져
있으므로 이번 PR에서는 다루지 않고, 별도 이슈로 등록해 동일한 방식(`null` 체크 후
`CustomException` throw)으로 고치는 것을 권장한다.

## 종합
Critical/High 없음. 이번 diff가 다루는 "2xx + 빈 바디 NPE" 문제 자체의 수정(구현 코드,
테스트)은 기획서 의도와 정확히 일치하고 정확하다. Medium 1건/Low 1건은 모두 간단한 수정이라
이 브랜치에서 바로 조치했다(위 각 항목 참고). `NeisClient.java`의 동일 유형 버그는 다른
파일이라 그대로 별도 백로그 이슈로 남긴다.

## `NeisClient` 추가분 리뷰

리뷰 대상: 커밋 `cc8aaae`(`fix(neis): #144 NEIS API 응답 바디 null 시 NPE 대신 명시적 실패
처리`, `NeisClient.java`)와 `697ec8f`(`test(neis): #144 응답 바디 null 시
EXTERNAL_API_ERROR 던지는지 검증 테스트 추가`, `NeisClientTest.java`) 두 개를 한 단위로
묶어 리뷰.
리뷰 방식: 위와 동일하게 [code-review-isolation.md](../../rules/code-review-isolation.md)에
따라 구현 맥락 없는 별도 에이전트(`code-review` 스킬)에 두 커밋의 diff와 `AligoSmsSender`
선행 수정 맥락(같은 버그 유형이 이미 리뷰·수정된 사례)만 전달해 독립 리뷰.

### 범위/컨벤션 확인
- `NeisClient.fetch()`의 `catch (RestClientException e)` 블록과 `parseRows(...)` 호출
  사이에 `root == null` 체크를 추가한 것이 전부다. 위 "참고" 절에서 지적된 것과 정확히 같은
  자리, 같은 방식(`AligoSmsSender`의 `body == null` 체크와 동일 패턴)이며 범위를 벗어난
  변경은 없다.
- `MealService`/`TimetableService` 등 `NeisClient.fetch()` 호출부를 확인한 결과 어느 쪽도
  `NullPointerException`을 별도로 잡지 않으므로, NPE → `CustomException(EXTERNAL_API_ERROR)`
  로 바뀌는 이번 변경은 호출부 동작에 부작용 없는 순수 개선이다.
- 새 로그 메시지(`"NEIS API 응답 바디 없음: path={}"`)는 바로 위 `RestClientException` catch
  블록의 로그 스타일과 형식이 일치하고, PII(전화번호 등)를 포함하지 않는다.
- 신규 테스트(`throwsWhenBodyIsEmpty`)는 `AligoSmsSenderTest.throwsWhenBodyIsEmpty`와 동일한
  `MockRestServiceServer.withSuccess()`(바디 없음) 패턴을 사용해, 기존 `catch` 블록이나 다른
  분기로 우회되지 않고 이번에 추가한 `root == null` 분기를 실제로 태우는지 검증한다. 기존
  4개 테스트(정상/데이터없음/진짜에러/서버에러)와 케이스가 겹치지 않는다.
- `NeisClientTest.java`가 파일 전체에 걸쳐 flat `@Test` 구조(`@Nested` 미적용)를 쓰는 것은
  이번 diff 이전부터 있던 상태이고, [144-bug-sms-npe.md](144-bug-sms-npe.md)에 이번 이슈
  범위 밖으로 명시돼 있어 지적하지 않았다(신규 테스트도 기존 스타일을 그대로 따름).
- 체크스타일 관점(줄 길이, 중괄호 스타일, import 순서 등)에서도 걸리는 부분 없음.
  `Javadoc` 규칙 위반도 없음(이번에 추가된 분기는 `private` 흐름 내부라 신규 public API가
  아님).

### 발견 사항 및 조치
Critical/High/Medium 없음.

#### 1. 🟢 Low(참고, 조치 보류) — 동일한 "2xx + 빈 바디 → null" NPE 방어 코드가 클라이언트마다
개별적으로 중복 구현됨

**문제**: `AligoSmsSender`(`body == null` 체크)에 이어 `NeisClient.java:61`
(`root == null` 체크)까지, 같은 버그 유형에 대한 방어 로직이 공통 메커니즘 없이 각 도메인
클래스마다 호출부별로 따로 작성되고 있다. 두 클라이언트 모두 `RestClient`의
`.retrieve().body(JsonNode.class)`를 쓰지만 이 null 체크는 `NeisConfig`/`AligoConfig`나
공유 `RestClient` 커스터마이저·인터셉터가 아니라 각 클래스의 `fetch`/`send` 메서드 안에만
있다. 앞으로 새로운 외부 API 클라이언트를 추가할 때 이 패턴을 알지 못한 채
`retrieve().body(...)` 호출만 복사하면, 이번 PR과 `AligoSmsSender` PR이 각각 따로 고쳐야
했던 것과 똑같은 NPE가 조용히 재발할 수 있다.

**제안(조치하지 않음)**: 공통 `RestClient.Builder` 커스터마이저나 작은 유틸리티
(예: `body == null`이면 지정한 `CustomException`을 던지는 헬퍼)로 추출해 두 클라이언트가
공유하도록 리팩터링하는 것을 고려할 수 있다. 다만 이는 기존 동작을 바꾸지 않는 순수 리팩터링
성격이고 이번 이슈(#144)의 "NPE를 명시적 실패로 바꾼다"는 범위를 벗어나므로, 이번 PR에서는
조치하지 않고 참고 사항으로만 남긴다. 세 번째 외부 API 클라이언트가 추가되는 시점에 별도
이슈로 다시 검토하는 것을 권장한다.

### 종합
Critical/High/Medium 없음. `NeisClient`의 `root == null` 체크와 신규 테스트 모두
`AligoSmsSender`에서 이미 검증된 패턴을 정확히 재사용했고, 로그·에러 코드·테스트 방식이
일관돼 별도 수정 없이 그대로 반영 가능하다. Low 1건(동일 방어 로직의 클라이언트별 중복)은
사소한 아키텍처 참고 사항이라 이번 PR에서 조치하지 않고 향후 참고용으로만 기록한다.
