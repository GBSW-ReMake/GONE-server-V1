# #119 알림 목록 조회 — QA 결과

관련 기획서: [119-notification-list.md](./119-notification-list.md)
관련 코드 리뷰: [119-notification-list-code-review.md](./119-notification-list-code-review.md)

## 검증 방법 및 범위

- 로컬 테스트 DB(MySQL)와 Redis를 사용해 `./gradlew --no-daemon clean check`를 실행했다.
  컴파일, Checkstyle, 전체 테스트가 모두 통과했다.
- `NotificationControllerTest`는 실제 Spring Security 필터 체인을 포함한 `MockMvc` 요청으로
  아래 계약을 검증했다.
  - 인증된 목록 조회: `200 OK`, 기본 `page=0`, `size=20`
  - `page=-1`: `400 NOTIFICATION_003`
  - `size=101`: `400 NOTIFICATION_003`
  - 미인증 요청: `401 COMMON_002`
- 로컬 개발 서버를 실행해 `GET /api/v1/notifications`의 미인증 실제 HTTP 요청이
  `401 COMMON_002`를 반환하는 것을 확인했다.
- GitHub Actions CI(실행 번호 180): 성공.

## 발견 사항

### Critical / High / Medium

없음.

### Low

없음.

## 결론

알림 목록 조회의 인증, 파라미터 검증, 공통 오류 응답 및 빌드 품질을 검증했다.
테스트·Checkstyle·GitHub Actions CI가 모두 통과했고, QA에서 추가 문제는 발견되지 않았다.
