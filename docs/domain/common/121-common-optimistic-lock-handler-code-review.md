# #121 낙관적 락 409 핸들러 — 코드 리뷰 결과

기획서: [121-common-optimistic-lock-handler.md](./121-common-optimistic-lock-handler.md)

## 코드 리뷰 (9단계, 별도 에이전트)

diff 범위: `docs/domain/common/121-common-optimistic-lock-handler.md` +
`src/test/java/com/remake/gone/common/exception/GlobalExceptionHandlerTest.java`

### 발견된 항목

**지적 1 — `response.getBody()` null 미확인**

`assertThat(response.getBody().success())` 형태로 null 체크 없이 body에 바로 접근한다.
→ **수정 안 함.** 파일 내 다른 핸들러 테스트(`handleMissingServletRequestParameter` 등)
모두 동일 패턴을 사용하고 있고, 핸들러는 항상 body를 세팅하므로 실제 NPE 발생 경로가 없다.
프로젝트 컨벤션을 따라 일관성을 유지한다.

**지적 2 — 테스트 DisplayName "500이 아니라" 표현**

"500이 아니라 409"보다 "409를 반환한다"가 더 긍정적 표현이라는 의견.
→ **수정 안 함.** 파일 내 `handleAccessDenied`, `handleNoResourceFound` 등 기존 테스트가
모두 "500이 아니라 NNN COMMON_NNN으로 응답한다" 패턴을 사용 중이다. 일관성 유지.

### 결론

문제 없음. 추가 수정 없이 다음 단계 진행.
