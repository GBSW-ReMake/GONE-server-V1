# #35 탈퇴/졸업 사용자 상태 관리 — QA 결과

관련 기획서: [35-user-deleted-filter.md](./35-user-deleted-filter.md)
관련 코드 리뷰: [35-user-deleted-filter-code-review.md](./35-user-deleted-filter-code-review.md)
(9단계 코드 리뷰 지적 사항은 QA 이전에 전부 반영 완료 — 이 문서는 QA에서 새로 발견된 것만
다룬다)

## 검증 방법/범위
- `./gradlew build`(전체: checkstyle + 모든 테스트) 통과 확인.
- 로컬 서버 실제 기동(`./gradlew bootRun`, `dev` 프로필) — 정상 기동 확인. 기동 중
  `V10__add_user_status.sql`이 기존 로컬 dev DB에 실제로 적용되는지도 함께 확인했다
  (`Schema gone is up to date` — 별도 수동 마이그레이션 없이 자동 적용됨).
- 로컬 Redis가 처음엔 떠 있지 않았으나, 보스가 WSL(Ubuntu)에서 Redis를 띄워준 뒤
  `localhost:6379`로 정상 연결을 확인하고 진행했다(로그인 성공 경로는 Refresh Token을
  Redis에 저장하므로 Redis 없이는 검증 불가).
- 기존 dev DB의 실 계정(`user1`/`1234`, 학생, 마이그레이션으로 전부 `ACTIVE`가 된 상태)을
  대상으로, DB에서 `status` 컬럼만 직접 바꿔가며(`WITHDRAWN`→`GRADUATED`→`ACTIVE`로 복원)
  실제 HTTP 요청으로 검증했다 — 상태를 바꾸는 관리자 화면 자체가 이번 범위 밖(기획서
  "정책" 절)이라 이 방법이 유일한 경로다. 검증 후 `user1`은 원래 상태(`ACTIVE`)로 복원해
  dev DB를 QA 이전 상태로 되돌렸다.

## 실제 HTTP E2E 검증

### 1. 기준선(둘 다 `ACTIVE`)
- `POST /api/v1/auth/login`(`user1`/`1234`) → `200`, 토큰 정상 발급
- `GET /api/v1/auth/login-id/check?loginId=user1` → `available: false`("이미 사용 중")
- `GET /api/v1/users/search?query=테스트`(teacher1 토큰으로 인증) → `user1`(테스트학생),
  `teacher1`(테스트선생님) 둘 다 포함

### 2. `user1.status = WITHDRAWN`
- `POST /api/v1/auth/login`(`user1`/`1234`, 올바른 비밀번호) → **`401` `AUTH_007`
  "아이디 또는 비밀번호가 일치하지 않습니다."**
- `POST /api/v1/auth/login`(`user1`/틀린 비밀번호) → 동일하게 `401` `AUTH_007`, **메시지
  완전히 동일함을 직접 대조 확인** — 계정 미존재/탈퇴/비밀번호 불일치 세 경우가 응답에서
  구분되지 않는다는 기획서 "리스크" 절의 핵심 전제가 실제 요청에서도 그대로 성립
- `GET /api/v1/auth/login-id/check?loginId=user1` → `available: false`(변경 없음 확인 —
  탈퇴 계정도 아이디 재사용 불가 유지)
- `GET /api/v1/users/search?query=테스트` → `teacher1`만 포함, `user1` 제외

### 3. `user1.status = GRADUATED`
- `POST /api/v1/auth/login`(`user1`/`1234`) → **`200`, 토큰 정상 발급**(졸업생 로그인
  허용 확인)
- `GET /api/v1/users/me`(발급받은 토큰으로) → `200`, 프로필 정상 조회(조회성 기능은
  계속 이용 가능하다는 정책과 일치)
- `GET /api/v1/users/search?query=테스트` → `teacher1`만 포함, `user1` 제외(졸업생도
  검색 결과에서는 제외된다는 정책과 일치)
- `GET /api/v1/auth/name/check?name=새별명389`(`user1`의 현재 별명) → `available: false`
  (졸업 계정도 별명 재사용 불가 유지)

## 발견 사항
Critical/High/Medium/Low 모두 없음.

## 결론
기획서 "변경 사항" 1~5번과 "정책" 절에 명시된 동작(검색은 `ACTIVE`만, 자퇴/퇴학은
계정 미존재와 구분 불가능한 로그인 거부, 졸업은 로그인·조회는 허용하되 검색에서는 제외,
아이디/별명 재사용 불가는 상태 무관하게 유지)이 실제 HTTP 요청 레벨에서 전부 관찰된
그대로 동작한다. 이 이슈의 완료 조건(로컬 빌드/테스트 통과)을 충족하며, CI 통과 여부는
PR 생성(16단계) 후 확인한다 — 이 프로젝트의 CI 워크플로우는 `main`/`dev`로의 PR·push
에서만 트리거되어 기능 브랜치 단독 push로는 미리 확인할 수 없다.
