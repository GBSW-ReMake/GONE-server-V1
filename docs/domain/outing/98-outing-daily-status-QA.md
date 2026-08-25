# #98 외출증 오늘 전체 현황 조회 API — QA 결과

관련 기획서: [98-outing-daily-status.md](./98-outing-daily-status.md)
코드 리뷰 결과: [98-outing-daily-status-code-review.md](./98-outing-daily-status-code-review.md)

## 검증 환경
- 로컬 MySQL(`gone` 스키마) + 로컬 Redis
- `CONDUCT_DEMERIT_THRESHOLD=10 ./gradlew bootRun --args='--spring.profiles.active=dev'`로
  실서버 기동(포트 9090). `CONDUCT_DEMERIT_THRESHOLD`는 `dev`에 먼저 머지된 상/벌점
  도메인(PR #113)이 요구하는 값인데 `application.yml`/`application-dev.yml`엔 없고 CI
  환경변수로만 공급되고 있어, 로컬 실행 시 별도로 넣어야 했다(#98 범위 밖 기존 이슈,
  팀에 공유함)
- 인증은 `JwtProvider.createAccessToken`을 임시 테스트로 직접 호출해 역할별 토큰 발급
- 검증에 사용한 임시 픽스처는 검증 직후 삭제 완료, 임시 테스트 파일도 커밋하지 않고 삭제함

## 로컬/CI 결과
- `./gradlew build`(compileJava/Test, test, checkstyleMain/Test 포함) 통과
- 전체 테스트 456개 통과(회귀 수정 후 최종 기준)
- GitHub Actions CI(PR #114): `checkstyle`/`build-and-test` pass

## 엔드포인트 실동작 검증 (`GET /api/v1/outings`)

| 케이스 | 요청 | 기대 | 실제 |
|---|---|---|---|
| 인증 없음 | 토큰 없이 요청 | 401 | ✅ |
| 권한 없음 | STUDENT 토큰 | 403 | ✅ |
| 정상(빈 목록) | DISCIPLINE 토큰, 오늘 데이터 없음 | 200, `content: []` | ✅ |
| `date` 형식 오류 | `?date=not-a-date` | 400 `COMMON_001` | ✅ |
| `page` 음수 | `?page=-1` | 400 `OUTING_015` | ✅ |
| `status` 존재하지 않는 값 | `?status=NOT_A_STATUS` | 400 `COMMON_001` | ✅ |
| 정상(하루 전체 흐름) | DISCIPLINE 토큰, 오늘 `PENDING`+`APPROVED` 각 1건 | 200, 2건, `startTime` 오름차순 | ✅ 12:30(PENDING→시간 경과로 MISSED 표시) → 18:00(APPROVED) 순 |
| `date` 필터 | 어제 날짜로 조회 | 어제 등록한 1건만 | ✅ 오늘 등록한 건은 안 보임 |

## 발견된 문제 — 🔴 Critical (발견 즉시 같은 브랜치에서 수정, 커밋 `180a2ad`)

**#96 회귀**: `status=MISSED` 필터가 `OutingMissedScheduler`(#42)에 의해 이미 DB에
`MISSED`로 반영된 행을 놓치는 버그를 실서버 QA 중 발견했다. #98용으로 만든 `PENDING`
픽스처가 서버 기동 중 스케줄러에 의해 실제로 `MISSED`로 바뀌었는데, `?status=MISSED`
조회 결과에서 사라지는 걸 확인했다(Hibernate SQL 바인딩 로그로 원인까지 직접 확인).

이 버그는 #96에서 도입된 `statusEq`/`wantExpired` 공용 패턴에 있어 **이미 머지된
`/me/requests?status=MISSED`, `/me/received?status=MISSED`(#41/#96)에도 동일하게
영향을 준다.** 보스와 상의해 #98 범위를 넘는 수정이지만 같은 브랜치에서 바로 고치기로
확정했다(원인/수정 내용은 `OutingService.resolveStatusFilterParams`,
`OutingRepository`의 3개 쿼리 메서드 Javadoc 참고). 재발 방지를 위해 스케줄러 타이밍에
의존하지 않는 실 DB 통합 테스트(`OutingMissedFilterIntegrationTest`)를 추가했다.

이 발견 외 Critical/High/Medium/Low 없음(코드 리뷰의 Postman 미반영 Medium 1건은
15단계에서 처리).

## 남은 절차
- 15단계: Postman 컬렉션에 `GET /api/v1/outings` 반영
- 16단계: 보스 최종 확인 후 draft PR(#114)을 Ready for review로 전환
