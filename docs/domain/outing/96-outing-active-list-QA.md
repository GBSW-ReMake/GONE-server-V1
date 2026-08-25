# #96 외출증 실시간 목록 조회 API — QA 결과

관련 기획서: [96-outing-active-list.md](./96-outing-active-list.md)
코드 리뷰 결과: [96-outing-active-list-code-review.md](./96-outing-active-list-code-review.md)

## 검증 환경
- 로컬 MySQL(`gone` 스키마, Flyway 마이그레이션 17개 정상 적용) + 로컬 Redis
- `./gradlew bootRun --args='--spring.profiles.active=dev'`로 실서버 기동(포트 9090)
- 인증은 `JwtProvider.createAccessToken`을 임시 테스트로 직접 호출해 역할별 토큰 발급
  (로그인 플로우를 거치지 않음 — `@PreAuthorize`가 JWT의 `roles` 클레임을 보고, 단건
  상세조회 인가는 `user_role` 테이블을 직접 조회하므로, 두 경로를 각각 검증하기 위해
  실제 DB에 `user_role` 로우가 있는 사용자와 없는 사용자를 구분해서 테스트했다)
- 검증에 사용한 임시 픽스처(외출증 1건, 학생/교사 계정 3건)는 검증 직후 삭제 완료,
  임시 테스트 파일도 커밋하지 않고 삭제함

## 로컬/CI 결과
- `./gradlew build`(compileJava/Test, test, checkstyleMain/Test 포함) 통과
- outing 패키지 테스트 148개 전부 통과(실 DB 기반 `OutingActiveListAuthorizationTest`,
  `OutingDepartReturnOwnershipIntegrationTest` 등 `@SpringBootTest` 통합 테스트 포함)
- GitHub Actions CI(PR #110): `checkstyle` pass(1m7s), `build-and-test` pass(2m9s)
  (`build-and-push-image`/`deploy-staging`는 draft PR이라 skip — 정상)

## 엔드포인트 실동작 검증 (`GET /api/v1/outings/active`)

| 케이스 | 요청 | 기대 | 실제 |
|---|---|---|---|
| 인증 없음 | 토큰 없이 요청 | 401 | ✅ 401 |
| 권한 없음 | STUDENT 토큰 | 403 | ✅ 403 |
| 정상(빈 목록) | DISCIPLINE 토큰, 데이터 없음 | 200, `content: []` | ✅ `{"content":[],"totalElements":0,...}` |
| `page` 음수 | `?page=-1` | 400 `OUTING_015` | ✅ `{"code":"OUTING_015","message":"페이지 파라미터가 올바르지 않습니다..."}` |
| `size` 범위 초과 | `?size=200` | 400 `OUTING_015` | ✅ 동일 |
| 정상(1건) | DISCIPLINE 토큰, `DEPARTED` 외출증 1건 존재 | 200, 기획서 응답 예시와 동일 필드 | ✅ 필드 순서/값 전부 일치(`code`/`studentNickname`/`studentProfileImageUrl`/`studentRealName`/`studentGrade`/`studentClassNo`/`reason`/`timeSlot`/`departedAt`/`endTime`) |

## 단건 상세조회 인가 확장 검증 (`GET /outings/{code}`)
- 담당 아닌 TEACHER 역할 사용자(실제 `user_role` 테이블에 TEACHER 로우 있음)로 조회 →
  **200 정상 응답** 확인. 확장이 의도대로 동작한다.
- (참고: 최초 시도 시 `user_role` 테이블에 역할 로우가 없는 가짜 사용자로 테스트해 403이
  나왔는데, 이는 구현 버그가 아니라 QA 픽스처 설정 실수였다 — `validateDetailAccess`가
  JWT 클레임이 아니라 DB `user_role` 조회 결과를 기준으로 판단하기 때문. 실제 역할이
  부여된 사용자로 재검증해 정상 동작을 확인했다.)

## 발견된 문제
Critical/High/Medium/Low 없음 — 기획서에 정의된 정상/에러 케이스가 실제 서버에서 전부
기획서대로 동작했다.

코드 리뷰(9단계)에서 이미 지적된 **Medium 1건(Postman 컬렉션에 `GET /active` 미반영)**은
QA 항목이 아니라 15단계에서 처리한다(코드 리뷰 문서 참고).

## 남은 절차
- 15단계: Postman 컬렉션에 `GET /active` 반영(이번 QA에서 검증한 요청 재사용)
- 16단계: 보스 최종 확인 후 draft PR(#110)을 Ready for review로 전환
