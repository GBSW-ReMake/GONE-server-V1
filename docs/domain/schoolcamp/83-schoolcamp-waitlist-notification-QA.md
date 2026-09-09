# #83 스쿨캠핑 자리나면 알림받기(대기자 알림) 기능 — QA 결과

관련 기획서: [83-schoolcamp-waitlist-notification.md](./83-schoolcamp-waitlist-notification.md)
관련 코드 리뷰: [83-schoolcamp-waitlist-notification-code-review.md](./83-schoolcamp-waitlist-notification-code-review.md)
(9단계 코드 리뷰 지적 사항(High 1건, Low 1건)은 QA 이전에 전부 반영 완료 — 이 문서는 QA에서
새로 발견된 것만 다룬다)

## 검증 방법/범위
- `./gradlew build`(checkstyle + 전체 테스트, `V14` 마이그레이션이 실 DB에 적용되는지 포함)
  통과 확인.
- 로컬 서버 실제 기동(`./gradlew bootRun`, `dev` 프로필, 로컬 MySQL(3306)/Redis(6379)).
- 실제 로그인 절차 대신, `JwtProvider`와 동일한 HS256 서명 방식(`application-dev.yml`의
  `jwt.access-token-secret`)으로 로컬 전용 토큰을 직접 발급해 사용했다 — 시크릿 값은 로컬
  `application-dev.yml`에 이미 평문으로 존재하는 값이고 로컬호스트에서만 사용했다.
- 기존 로컬 dev DB의 실 계정(`id=1` STUDENT, `id=2` STUDENT, `id=13` TEACHER+ADMIN)을
  그대로 사용했다. 이번 QA로 새로 만든 세션 1건(`id=101`, `20260826`)/신청 1건(`id=25`)/
  알림 1건은 QA 종료 후 전부 삭제했고, 등록했던 대기(`school_camp_waitlist id=1`)도
  삭제해 dev DB를 QA 이전 상태로 되돌렸다(`#70` QA와 동일한 원칙).
- 한글 페이로드는 `#68`/`#70` QA에서 확인된 Windows Git Bash 인코딩 이슈를 피하기 위해
  Write 도구로 UTF-8 파일을 먼저 만들고 `curl --data-binary @file`로 전송했다.

## 실제 HTTP E2E 검증

### `POST/DELETE /api/v1/school-camps/waitlist`, `GET /api/v1/school-camps/waitlist/me`
1. 등록 전 상태 조회 → `200`, `{"registered": false, "registeredAt": null}`
2. 정상 등록(파라미터 없음) → `201`, `{"month": "202608", "registeredAt": "..."}` — 서버가
   요청 시점 기준 "이번 달"을 정확히 계산함을 확인
3. 등록 직후 상태 조회 → `registered: true`
4. 같은 달에 중복 등록 시도 → `409 SCHOOLCAMP_014`
5. 취소 → `200`
6. 취소 직후 상태 조회 → `registered: false`
7. 등록된 적 없는 상태에서 다시 취소 시도 → `404 SCHOOLCAMP_015`
8. 취소했던 같은 달에 재등록 → `201`. DB에서 `school_camp_waitlist` 행이 여전히 1건뿐임을
   확인(새 행이 아니라 기존 행이 재활성화됨, `id=1` 그대로 `cancelled_at=NULL`로 갱신)
9. 인증 없이 등록 시도 → `401`
10. `TEACHER` 역할로 등록 시도 → `403`(`STUDENT` 전용)

### 취소 발생 시 대기자 알림 발송 (기획서 핵심 시나리오)
11. `id=1`이 이번 달(2026-08) 대기 등록된 상태에서, ADMIN으로 새 세션(`20260826`,
    `id=101`) 등록 → `id=2`가 그 세션에 신청(`id=25`) → `id=2`가 본인 신청을 취소.
    직후 `notification` 테이블에서 `id=1`에게 새 알림이 실제로 생겼음을 확인:
    `title="스쿨캠핑 자리가 났어요!"`,
    `body="2026년 8월 스쿨캠핑에 취소로 빈 자리가 생겼어요. 캘린더에서 확인하고 신청해보세요!"`
    — 기획서가 명시한 "취소 시점이 아니라 세션의 캠핑 날짜가 속한 달" 기준이 실제로도
    정확히 8월로 계산됨을 실 데이터로 확인했다.

### `releaseQuietly` 경로가 알림을 보내지 않는지 (코드 리뷰 High 1번 재검증)
12. 위 11번과 별개로, `id=2`가 같은 세션(재오픈된 `id=101`)에 `teacherUserId=1`(`STUDENT`
    역할이라 유효하지 않은 선생님)로 신청을 시도 → 세션 claim 이후 검증에서
    `400 SCHOOLCAMP_004`로 실패, `releaseQuietly`로 세션이 다시 반환됨(`taken_at`이 다시
    `NULL`로 확인됨). 이 실패 직후 `notification` 테이블의 `id=1` 최신 알림 id가 11번
    검증 때와 동일하게 유지됨을 확인 — 실패한 신청 시도가 대기자에게 스팸 알림을 보내지
    않음을 실제 DB 상태로 재확인했다(단위 테스트는 코드 리뷰 반영 시 이미 추가함).

## 발견 사항
Critical/High/Medium/Low 모두 없음 — 코드 리뷰(9단계)에서 지적/반영된 사항(특히 High
1번, `releaseQuietly` 무알림)이 실제 HTTP 요청과 DB 레벨에서도 의도대로 동작함을
재확인했고, QA 단계에서 새로 발견된 문제는 없다.
