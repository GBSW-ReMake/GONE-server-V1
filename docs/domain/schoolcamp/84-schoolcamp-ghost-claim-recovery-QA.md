# #84 스쿨캠핑 유령 점유(ghost claim) 회수 — QA 결과

관련 기획서: [84-schoolcamp-ghost-claim-recovery.md](./84-schoolcamp-ghost-claim-recovery.md)
관련 코드 리뷰: [84-schoolcamp-ghost-claim-recovery-code-review.md](./84-schoolcamp-ghost-claim-recovery-code-review.md)
(9단계 코드 리뷰 지적 사항(High 1건, Medium 2건)은 QA 이전에 전부 반영 완료 — 이 문서는 QA에서
새로 발견된 것만 다룬다. 코드 리뷰 문서가 자동 테스트로 다루기 어렵다고 명시한 "활성 신청이
있으면 유예시간이 지나도 재점유되지 않는지"는 아래 QA에서 실 HTTP 흐름으로 확인했다)

## 검증 방법/범위
- `./gradlew build`(checkstyle + 전체 테스트) 통과 확인(코드 리뷰 반영 커밋 시점에 이미 확인,
  QA 시작 전 최종 상태 그대로).
- 로컬 서버 실제 기동(`./gradlew bootRun`, `dev` 프로필, 로컬 MySQL(3306)/Redis(6379)).
- `#83` QA와 동일하게, 실제 로그인 대신 `JwtProvider`와 동일한 HS256 서명 방식
  (`application-dev.yml`의 `jwt.access-token-secret`)으로 로컬 전용 토큰을 직접 발급해
  사용했다 — 시크릿 값은 로컬 `application-dev.yml`에 이미 평문으로 존재하는 값이고
  로컬호스트에서만 사용했다.
- 기존 로컬 dev DB의 실 계정(`id=1` STUDENT, `id=2` STUDENT, `id=13` TEACHER+ADMIN)을 그대로
  사용했다. 이번 QA로 새로 등록한 세션 5건(`id=122~126`, `20291106~20291113`)과 그 위에서
  생성된 신청 3건(`id=26~28`)/팀원 3건은 QA 종료 후 전부 삭제해 dev DB를 QA 이전 상태로
  되돌렸다(`#70`/`#83` QA와 동일한 원칙).
- 유령 점유/유예시간 경과 상황은 실제로 서버를 몇 분씩 대기시키는 대신, `taken_at`을 MySQL에서
  직접 과거로 옮겨(`UPDATE ... SET taken_at = NOW() - INTERVAL n MINUTE`) 재현했다 — 값 자체를
  DB에 실제로 써넣는 방식이라, `reclaimIfExpired`가 보는 조건(`taken_at < threshold`)을 실제
  운영 상황과 동일하게 만족시킨다.

## 실제 HTTP E2E 검증

### 정상 신청/취소 플로우 (회귀 확인)
1. ADMIN으로 세션 5건 등록(`122`~`126`) → `201`
2. `id=1`이 세션 `122`에 정상 신청 → `201`, DB에서 `taken_at`이 신청 시각으로 채워짐을 확인
3. `id=1`이 그 신청을 취소 → `200`, DB에서 `session 122`의 `taken_at`이 다시 `NULL`로
   돌아옴을 확인(`release`의 CAS 가드가 정상 취소 경로에서도 정확히 동작)

### claim 이후 검증 실패 시 release(#84 DATETIME 정밀도 수정 검증)
4. `id=2`가 세션 `122`(3번으로 다시 비어있음)에 `teacherUserId=1`(`STUDENT` 역할이라 유효하지
   않은 선생님)로 신청 시도 → `400 SCHOOLCAMP_004`. 직후 DB에서 `session 122`의 `taken_at`이
   `NULL`로 확인됨 — 컨트롤러가 미리 초 단위로 자른 시각을 그대로 `release`의
   `expectedTakenAt`으로 넘겨 CAS 비교가 정확히 일치했음을 실 HTTP 흐름에서 재확인했다(단위/
   통합 테스트 레벨이 아니라 컨트롤러 레벨에서의 검증은 이번이 처음).

### 유령 세션 재점유 (핵심 시나리오)
5. 세션 `123`을 `taken_at = NOW() - 3분`(유예시간 2분 경과), 활성 신청 없음 상태로 DB에서
   직접 재현
6. 캘린더 조회(`GET /api/v1/school-camps?month=202911`) → `123`이 `status: "OPEN"`으로 보임
   (유예시간 경과 유령을 화면에서도 열림으로 정확히 반영)
7. `id=2`가 세션 `123`에 새로 신청 → `201` 성공(재점유). DB에서 `taken_at`이 과거의 유령
   타임스탬프가 아니라 방금 신청한 시각으로 갱신됨을 확인 — `reclaimIfExpired`가 실제로
   동작해 재점유가 이루어졌음을 실 데이터로 확인했다.

### 활성 신청이 있으면 유예시간이 지나도 재점유되지 않는지 (코드 리뷰가 QA로 미룬 항목)
8. 세션 `124`에 `id=1`이 정상 신청(활성 신청 1건 생성) 후, `taken_at`을 `NOW() - 10분`으로
   DB에서 직접 과거로 옮김(유예시간을 크게 초과, 활성 신청은 그대로 유지)
9. 캘린더 조회 → `124`가 여전히 `status: "CLOSED"`, `teacherDisplayName`/`applicantDisplayName`
   모두 정상 표시(유예시간과 무관하게 진짜 예약은 항상 닫힘으로 보임)
10. `id=2`가 세션 `124`에 새로 신청 시도 → `409 SCHOOLCAMP_002`("이미 다른 팀이 신청한
    날짜입니다")로 정상 거부. 직후 DB에서 `session 124`의 `taken_at`이 8번에서 옮긴 과거
    값 그대로 변경되지 않았음을 확인 — `reclaimIfExpired`의 `NOT EXISTS` 조건이 활성 신청을
    정확히 감지해 재점유 `UPDATE` 자체가 0행으로 끝났음을 실 데이터로 확인했다. 이는 코드
    리뷰 문서가 "자동 통합 테스트로 검증하기 어려워 QA에서 실 HTTP 흐름으로 확인"하도록
    명시했던 바로 그 케이스다.

## 발견 사항
Critical/High/Medium/Low 모두 없음 — 코드 리뷰(9단계)에서 지적/반영된 사항(원자적
`reclaimIfExpired`, `release`의 CAS 가드, DATETIME 정밀도 절단)이 실제 HTTP 요청과 DB
레벨에서도 의도대로 동작함을 재확인했고, 특히 코드 리뷰가 자동 테스트로 다루지 못해 QA로
미뤘던 "활성 신청 보호" 케이스(8~10번)도 정상 동작을 확인했다. QA 단계에서 새로 발견된
문제는 없다.
