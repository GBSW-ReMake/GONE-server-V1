# #70 스쿨캠핑 신청 취소/수정 API — QA 결과

관련 기획서: [70-schoolcamp-cancel-update.md](./70-schoolcamp-cancel-update.md)
관련 코드 리뷰: [70-schoolcamp-cancel-update-code-review.md](./70-schoolcamp-cancel-update-code-review.md)
(9단계 코드 리뷰 지적 사항(Medium 2건)은 QA 이전에 전부 반영 완료 — 이 문서는 QA에서 새로
발견된 것만 다룬다)

## 검증 방법/범위
- `./gradlew build`(checkstyle + 전체 테스트, `V13` 마이그레이션이 실 DB에 적용되는지
  포함) 통과 확인.
- 로컬 서버 실제 기동(`./gradlew bootRun`, `dev` 프로필).
- 기존 dev DB의 실 계정(`user1`/`teacher1`)에 더해, 팀원/월중복 시나리오 검증을 위해
  `testuser01`/`testuser02`에 QA 목적으로 STUDENT 역할을 임시로 부여했고(비밀번호는 각
  계정이 이미 쓰던 실제 값 그대로 사용, 별도 변경 없음), `teacher1`에는 `#68` QA와 동일하게
  ADMIN 역할을 임시로 부여했다 — **검증 후 전부 원복**(부여한 역할 삭제, 로그인 재시도로
  원복 확인). 등록한 세션 9건/신청 4건/알림도 QA 종료 후 전부 삭제해 dev DB를 QA 이전
  상태로 되돌렸다.
- 한글 페이로드는 `#68` QA에서 확인된 Windows Git Bash 인코딩 이슈를 피하기 위해 Write
  도구로 UTF-8 파일을 먼저 만들고 `curl --data-binary @file`로 전송했다.

## 실제 HTTP E2E 검증

### `DELETE /api/v1/school-camps/applications/{id}` (취소)
1. 본인 신청이 아닌 계정으로 취소 시도 → `403 SCHOOLCAMP_007`
2. 존재하지 않는 신청(`id=999999`) → `404 SCHOOLCAMP_010`
3. 정상 취소 → `200 OK`, 이후 캘린더 재조회에서 해당 세션이 다시 `OPEN`으로 돌아옴을 확인
4. 이미 취소된 신청을 재취소 시도 → `404 SCHOOLCAMP_010`(3번에서 취소한 그 신청으로 재확인)
5. 캠핑 당일(오늘 날짜로 등록한 세션에 신청 후 즉시 취소 시도) → `400 SCHOOLCAMP_009`

### `PATCH /api/v1/school-camps/applications/{id}` (수정)
6. 본인 신청이 아닌 계정으로 수정 시도 → `403 SCHOOLCAMP_007`
7. 존재하지 않는 신청 → `404 SCHOOLCAMP_010`
8. 담당 선생님만 변경(자유 입력), 팀원 그대로 → `200 OK`, `teacherDisplayName`만 바뀌고
   팀원 구성은 그대로 유지됨을 확인
9. 이미 이번 달에 다른 세션에 참여 중인 학생을 팀원으로 추가 시도 → `409 SCHOOLCAMP_003`,
   실패 후 캘린더 재조회로 신청 상태가 실제로 변경 없이 그대로임을 확인
10. 팀원 제거(가입 학생 1명) + 게스트 1명 추가를 한 요청에 함께 수행(코드 리뷰가 지적한
    조합 케이스) → `200 OK`, 제거한 학생은 응답에서 사라지고 새 게스트만 추가됨을 확인
11. 총원 9명(대표 포함, 8명 초과) → `400 SCHOOLCAMP_004`
12. 존재하지 않는 `studentUserId` → `400 SCHOOLCAMP_008`
13. 11~12번 실패 후에도 신청이 10번 상태 그대로 유지됨을 캘린더로 재확인(부분 반영 없음)

### 코드 리뷰 Medium 1번(동시 수정 시 팀원 중복 삽입) 재현 검증
14. DB에 직접 접속해 `school_camp_member`의 `SHOW CREATE TABLE`로
    `uq_member_application_student UNIQUE (application_id, student_user_id)` 제약이 실제
    적용돼 있음을 확인
15. 이미 팀원으로 등록된 학생과 같은 `(application_id, student_user_id)` 조합으로 직접
    `INSERT`를 시도 → `ERROR 1062 Duplicate entry` — DB가 실제로 중복을 거부함을 확인
16. 참고로 게스트(양쪽 다 `student_user_id IS NULL`) 두 행은 같은 신청에 자유롭게
    공존함을 확인(제약이 게스트 중복은 막지 않는다는 설계 의도대로 동작)
    - 코드 레벨(`DataIntegrityViolationException` → `409 SCHOOLCAMP_011` 변환)은 이미
      단위 테스트(`throwsConflictWhenConcurrentInsertViolatesUniqueConstraint`)로
      결정론적으로 검증되어 있어, 이번 QA에서는 DB 제약 자체가 실제로 존재하고 동작하는지만
      재확인했다 — 실 HTTP 요청 두 개를 동시에 경합시키는 방식은 타이밍이 보장되지 않아
      QA 스크립트로는 재현하지 않았다.

## 발견 사항
Critical/High/Medium/Low 모두 없음 — 코드 리뷰(9단계)에서 지적/반영된 사항이 전부 실제
HTTP 요청과 DB 레벨에서도 의도대로 동작함을 재확인했고, QA 단계에서 새로 발견된 문제는
없다.

## 결론
기획서에 정의된 두 엔드포인트(취소, 수정)의 정상/에러 케이스와 코드 리뷰에서 고친 동시
수정 방어(DB 유니크 제약)가 전부 실제 HTTP 요청 및 DB 레벨 검증으로 확인됐다. 이 이슈의
완료 조건(로컬 빌드/테스트 통과)을 충족하며, CI 통과 여부는 PR 생성(16단계) 후 확인한다
— 이 프로젝트의 CI 워크플로우는 `main`/`dev`로의 PR·push에서만 트리거되어 기능 브랜치
단독 push로는 미리 확인할 수 없다(`#67`/`#68` QA와 동일한 제약).
