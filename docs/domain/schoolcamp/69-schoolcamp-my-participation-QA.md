# #69 스쿨캠핑 본인 참여 내역 조회 API — QA 결과

관련 기획서: [69-schoolcamp-my-participation.md](./69-schoolcamp-my-participation.md)
관련 코드 리뷰: [69-schoolcamp-my-participation-code-review.md](./69-schoolcamp-my-participation-code-review.md)
(9단계 코드 리뷰 지적 사항은 Medium 1건뿐이었고, 코드 결함이 아니라 "참여자 아님 403
경로가 통합 테스트로 커버되지 않는다"는 테스트 커버리지 갭이었다 — 문서가 제시한 대안 중
"10단계 QA에서 실서버로 수동 재현" 방식을 택했다. 아래 "1번 항목 재확인" 참고)

## 검증 방법/범위
- `./gradlew build`(checkstyle + 전체 테스트) 통과 확인.
- 로컬 서버 실제 기동(`./gradlew bootRun`, `dev` 프로필, 로컬 MySQL/Redis).
- 기존 QA 계정(`testuser01`/`testuser02`, `#67`~`#71`과 동일)을 재사용했다. 세션 등록을
  위해 `testuser01`에 ADMIN 역할을 임시로 부여했고(비밀번호는 이미 쓰던 값 그대로), 검증
  후 전부 원복했다(부여한 역할 삭제, 등록한 세션/신청/팀원 레코드 전부 삭제, DB 재조회로
  원복 확인).
- 한글 페이로드 인코딩 문제 없이 curl로 직접 검증(이번엔 한글 자유 입력 필드를 쓰지 않아
  `#68`/`#70` QA에서 겪은 Windows Git Bash 인코딩 이슈가 애초에 발생하지 않았다).

## 실제 HTTP E2E 검증

### `GET /api/v1/school-camps/me` (목록)
1. `testuser02`가 세션(20260824)에 단독 신청(대표만, 팀원 없음) 후 `month=202608`로 조회
   → `200`, `content`에 1건, `myRole: APPLICANT`, `cancelledAt: null` — 목록 응답에
   `members` 필드가 없음(기획서/코드 리뷰가 확인한 대로 요약 응답)도 함께 확인
2. `month=202609`(참여 없는 다른 달)로 조회 → `200`, `content: []`
3. `page=-1` → `400 SCHOOLCAMP_012`
4. `size=101` → `400 SCHOOLCAMP_012`

### `GET /api/v1/school-camps/applications/{id}` (상세)
5. 본인(대표 신청자, `testuser02`)이 자기 신청 상세 조회 → `200`, `myRole: APPLICANT`,
   `members`에 팀원 전체 포함
6. **`testuser01`(그 신청과 무관한, 참여자가 아닌 인증된 STUDENT)이 같은 신청 상세 조회
   시도 → `403 SCHOOLCAMP_013`("본인이 참여한 신청만 조회할 수 있습니다.")** — 코드
   리뷰 1번 항목(참여자 아님 403 경로가 통합 테스트로 커버되지 않음)이 실제
   인증·`@PreAuthorize`·서비스 로직을 전부 거치는 end-to-end 경로에서 정확히 의도대로
   동작함을 재확인했다. 자동화 테스트로 상시 회귀 검증은 되지 않지만(코드 리뷰 문서가
   이미 밝힌 트레이드오프), 최소 이번 구현 시점에는 실제로 동작함을 직접 확인했다.
7. 존재하지 않는 신청(`id=999999`) → `404 SCHOOLCAMP_010`

### 취소 이력 포함 확인 (설계 변경 핵심 검증)
8. `testuser02`가 5번 신청을 `DELETE /applications/{id}`로 취소(`#70` 엔드포인트 재사용,
   회귀 확인 겸) → `200`, 세션(`school_camp_session.taken_at`)이 DB에서 다시 `NULL`로
   돌아옴을 확인(`#70`이 이미 검증한 원자적 반환 로직의 회귀 없음)
9. 취소 직후 `GET /me?month=202608` 재조회 → **취소된 신청도 목록에서 사라지지 않고
   `cancelledAt`이 실제 취소 시각으로 채워짐**을 확인(설계 변경 1번 핵심 요구사항)
10. 취소 직후 `GET /applications/{id}` 재조회 → **여전히 `200`으로 상세가 조회됨**(취소
    전과 동일한 `members`/`myRole`)을 확인

## 발견 사항
Critical/High/Medium/Low 모두 새로 발견된 것은 없다. 코드 리뷰 Medium 1번(위 6번 케이스)은
결함이 아니라 실제로 정상 동작함을 실서버로 재확인했다 — 다만 자동화 통합 테스트가 없다는
근본 갭 자체는 코드 리뷰 문서에 남긴 트레이드오프 그대로 유지한다(이 프로젝트에 아직
소유권 레벨 픽스처 기반 통합 테스트 선례가 없어, `outing` 도메인과 동일하게 이번에도
비용 대비 QA 재확인으로 대체하기로 한 기존 판단을 따른다).

## 결론
기획서에 정의된 목록/상세 두 엔드포인트의 정상/에러 케이스, 코드 리뷰가 지적한 참여자
아님 403 경로, 취소 이력 포함 요구사항(설계 변경의 핵심)이 전부 실제 HTTP 요청으로
확인됐다. `#70` 취소 엔드포인트와의 상호작용(세션 반환)도 회귀 없이 동작한다. 이 이슈의
완료 조건(로컬 빌드/테스트 통과)을 충족하며, CI 통과 여부는 PR 생성(16단계) 후 확인한다.

## 추가 QA — 선생님 조회 확장(설계 변경 3, 2026-08-20)

머지 전 추가된 선생님 참여 내역 조회를 위 방식과 동일하게 실서버로 검증했다(관련 코드
리뷰: `69-schoolcamp-my-participation-code-review.md`의 "추가 리뷰" 절, 신규 이슈 0건).

### 검증 방법
`testuser01`에 ADMIN(세션 등록용)에 더해 TEACHER 역할을 임시로 추가 부여했다 —
검증 후 두 역할 모두 원복. 세션(20260901, 9월)을 등록하고, `testuser02`가 `testuser01`을
담당 선생님(`teacherUserId`)으로 지정해 신청했다.

### 실제 HTTP E2E 검증
1. `testuser01`(담당 선생님)이 `GET /me?month=202609` 조회 → `200`, `content`에 1건,
   `myRole: TEACHER`, `teacherDisplayName`이 본인 실명("정문경")과 일치
2. `testuser01`이 `GET /applications/{id}` 상세 조회 → `200`, `myRole: TEACHER`,
   `members`에 대표 신청자(`testuser02`)가 정상적으로 포함됨
3. `testuser02`(대표 신청자)가 같은 신청을 `GET /me?month=202609`로 재조회(회귀 확인)
   → `200`, `myRole: APPLICANT`로 그대로 유지 — 선생님 조회 경로 추가가 기존 학생 조회
   경로에 영향을 주지 않음을 확인

## 발견 사항(추가)
새로 발견된 결함 없음. `resolveTeacherRole`/`collectMyParticipationSources`가 실제 DB
데이터에 대해서도 코드 리뷰·단위 테스트와 일치하게 동작함을 확인했다.

## 결론(갱신)
선생님 조회(설계 변경 3)까지 포함해 기획서에 정의된 모든 시나리오가 실제 HTTP 요청으로
검증됐다. 추가로 발견된 문제는 없어 16단계(PR)로 그대로 진행 가능하다고 판단한다.
