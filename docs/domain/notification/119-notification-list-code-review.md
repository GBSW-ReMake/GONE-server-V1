# #119 알림 목록 조회 — 코드 리뷰 결과

관련 기획서: [119-notification-list.md](./119-notification-list.md)

## 리뷰 범위 및 방법

- 대상: `dev...feat/#119-notification-list` 변경 사항
- 확인 범위: Controller·Service·Repository·DTO·Security 설정·테스트
- CodeRabbit 증분 리뷰와 프로젝트 통합 테스트 결과를 함께 확인했다.

## 발견 사항 및 반영 결과

### 1. 🟢 Low — 초기 Controller 테스트가 HTTP 보안 경로를 우회함

**문제**: 초기 테스트는 `@WebMvcTest(addFilters = false)`와 Controller 직접 호출을 사용해
요청 매핑, `@RequestParam` 바인딩, `@AuthenticationPrincipal`, Spring Security 필터를 함께
검증하지 못했다.

**해결 방안**:

1. `@SpringBootTest`와 `@AutoConfigureMockMvc`로 전환해 실제 필터 체인과 HTTP 요청을
   검증한다 — 프로젝트의 보안 통합 테스트 방식과 일치하며, 인증·파라미터 처리 회귀를 막는다.
2. 기존 슬라이스 테스트를 유지하고 Controller를 직접 호출한다 — 실행은 가볍지만 보안과 요청
   바인딩을 검증하지 못하므로 채택하지 않는다.

**반영 결과**: 1번을 적용했다. 인증된 `200`, 잘못된 `page`·`size`의
`400 NOTIFICATION_003`, 미인증 `401 COMMON_002`를 `MockMvc`로 검증한다.

## 결론

Critical/High/Medium 없음. Low 1건은 `d326409`
(`test(notification): CodeRabbit 리뷰 반영`)에서 수정했고, 이후 전체 검증을 통과했다.
