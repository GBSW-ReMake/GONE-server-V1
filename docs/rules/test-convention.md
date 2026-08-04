# 테스트 코드 규칙

- 프레임워크: JUnit5 + Mockito(`BDDMockito`의 `given/willReturn`) + AssertJ
- 클래스 구조:
  - 메서드 단위로 `@Nested` 클래스 + `@DisplayName("메서드명")`으로 그룹화
  - 각 테스트 케이스는 `@Test @DisplayName("한글로 시나리오 설명")`
- Mock: `@Mock` + `@InjectMocks` 사용, 실제 Redis/외부 API(SMS 등)는 호출하지 않고 목킹한다.
- 검증 범위: 정상 케이스뿐 아니라 예외 케이스(`CustomException` + 해당 도메인 `ErrorCode`)까지 반드시 작성한다.
- 새 서비스/컨트롤러 로직을 추가하면 대응하는 테스트를 반드시 함께 작성한다 (기획서에 정의된 엔드포인트당 최소 1개 이상).
- 커밋 전 `./gradlew test` 통과를 확인한다.
