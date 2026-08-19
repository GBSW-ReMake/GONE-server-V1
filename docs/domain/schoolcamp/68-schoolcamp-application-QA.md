# #68 스쿨캠핑 신청 API — QA 결과

관련 기획서: [68-schoolcamp-application.md](./68-schoolcamp-application.md)
관련 코드 리뷰: [68-schoolcamp-application-code-review.md](./68-schoolcamp-application-code-review.md)
(9단계 코드 리뷰 지적 사항(High 1·Medium 5·Low 3)은 QA 이전에 전부 반영 완료 — 이 문서는
QA에서 새로 발견된 것만 다룬다)

## 검증 방법/범위
- `./gradlew build`(checkstyle + 전체 테스트, 동시성 통합 테스트 포함) 통과 확인.
- 로컬 서버 실제 기동(`./gradlew bootRun`, `dev` 프로필) — `Current version of schema gone: 12`로
  `V12__add_schoolcamp_application.sql`이 이미 적용된 로컬 dev DB에서 검증했다.
- 기존 dev DB의 실 계정(`user1`/`teacher1`)을 사용했고, 팀원 시나리오 검증을 위해 두 번째
  학생 계정이 필요해 `testuser01`에 QA 목적으로 임시로 비밀번호(`user1`과 동일 해시)와
  `STUDENT` 역할을 DB에서 직접 부여했다. 관리자 등록 엔드포인트 검증을 위해 `teacher1`에도
  임시로 `ADMIN` 역할을 부여했다(#67 QA와 동일한 방식) — **검증 후 모두 원래 상태로 복원**
  (비밀번호 해시 원복, 부여한 역할 삭제, 로그인 재시도로 원복 확인 완료). 등록한 세션
  7건/신청 2건/알림 1건도 QA 종료 후 전부 삭제해 dev DB를 QA 이전 상태로 되돌렸다.
- **QA 진행 중 방법론 이슈 1건**: Windows Git Bash에서 `curl -d`에 한글을 직접 넣으면 터미널
  코드페이지 때문에 UTF-8이 아닌 바이트로 전송돼 `SCHOOLCAMP_004`(형식 오류) 검증 2건이
  일시적으로 `500`(Jackson `StreamReadException: Invalid UTF-8 start byte`)으로 보였다 — 이건
  이 이슈의 코드 결함이 아니라 QA 스크립트 자체의 인코딩 문제였다. Write 도구로 UTF-8 파일을
  먼저 만들고 `curl --data-binary @file`로 보내는 방식으로 바꾸자 즉시 재현되지 않았고, 이후
  모든 한글 페이로드는 이 방식으로 전송해 재확인했다.

## 실제 HTTP E2E 검증

### `POST /api/v1/school-camps/{sessionId}/applications` (신청)
1. 정상 신청(가입 선생님 + 가입된 학생 팀원 1명) → **`201 Created`**, `teacherDisplayName`이
   선생님 실명(`Gbsw.name`)으로, 팀원 응답이 실명/학년/반으로 정확히 채워짐
2. 자유 입력 선생님 + "기타"(자유 입력) 팀원 1명, 다른 달(10월)에 신청 → `201 Created`,
   `teacherDisplayName`이 입력한 문자열 그대로, 팀원의 `guestName`만 채워지고
   `studentRealName`/`grade`/`classNo`는 `null`
3. 이미 다른 신청이 그 날짜를 선점한 상태에서 재신청 → `409 SCHOOLCAMP_002`
4. 존재하지 않는 세션(`sessionId=999999`) → `404 SCHOOLCAMP_001`
5. `teacherUserId`/`teacherName` 둘 다 있음 → `400 SCHOOLCAMP_004`
6. `teacherUserId`/`teacherName` 둘 다 없음 → `400 SCHOOLCAMP_004`
7. 총원(대표 포함) 9명(8명 초과) → `400 SCHOOLCAMP_004`
8. 존재하지 않는 `studentUserId` 포함 → `400 SCHOOLCAMP_008`
9. `additionalMembers`에 같은 `studentUserId` 중복 → `400 SCHOOLCAMP_008`
10. 대표 신청자 본인이 `additionalMembers`에 포함 → `400 SCHOOLCAMP_008`
11. 5~10번 모두, 실패 후 캘린더를 재조회해 해당 세션이 다시 `OPEN`으로 돌아옴을 확인
    (claim 이후 명시적 `release`가 실제로 동작함을 실 DB로 재확인)
12. 이미 이번 달에 팀원으로 참여한 학생이 다른 세션에 대표로 재신청 → `409 SCHOOLCAMP_003`
13. 이미 이번 달에 대표로 참여한 학생이 다른 세션에 대표로 재신청 → `409 SCHOOLCAMP_003`
14. `STUDENT`가 아닌 계정(TEACHER+ADMIN) 호출 → `403 COMMON_003`
15. 인증 없이 호출 → `401 COMMON_002`
16. 가입된 학생을 팀원으로 추가하면 그 학생에게 초대 알림이 실제로 저장됨(제목/본문/타입
    확인, 대표 신청자 본인에게는 알림이 가지 않음) — `NotificationType.SCHOOLCAMP`,
    본문에 신청자 닉네임 + "M월 D일" 형식이 정확히 반영됨

### `GET /api/v1/school-camps?month=` (캘린더 조회, #68에서 이름 채우기 추가)
17. 신청 전: 등록한 세션 전부 `status: "OPEN"`, 이름 필드 둘 다 `null`
18. 위 1번 신청 후 재조회: 해당 세션만 `status: "CLOSED"`로 바뀌고
    `teacherDisplayName`/`applicantDisplayName`(학번+실명, 예: `"3101테스트학생"`)이 정확히
    채워짐 — N+1 제거를 위해 바꾼 배치 조회(`findBySessionIdInAndCancelledAtIsNull`)가
    실제 데이터로도 올바른 결과를 반환함을 확인
19. **코드 리뷰 High 발견 사항 재현 검증**: DB에서 세션 하나의 `taken_at`만 직접 채우고
    대응하는 신청 데이터는 만들지 않아 "유령 점유"를 실제로 재현한 뒤 캘린더를 재조회 →
    **`500`이 아니라 `200`**을 반환했고, 그 세션만 이름 없는 `CLOSED`로 방어적으로 표시되며
    나머지 세션은 영향 없이 정상 표시됨(전체 6개 세션 정상 반환 확인). 서버 로그에서 경고
    로그(`SchoolCampService`, "유령 점유 의심: sessionId=...")가 실제로 찍히는 것도 확인—
    코드 리뷰에서 지적된 문제가 실제로 고쳐졌음을 실 DB로 재확인했다.

## 발견 사항
Critical/High/Medium/Low 모두 없음 — 코드 리뷰(9단계)에서 지적/발견된 항목이 전부 실제
HTTP 요청 레벨에서도 의도대로 고쳐졌음을 재확인했고(특히 High 1건은 유령 점유를 실제로
재현해 캘린더가 더 이상 무너지지 않음을 확인), QA 단계에서 새로 발견된 코드 결함은 없다.
위 "검증 방법/범위"에 적은 한글 인코딩 이슈는 QA 스크립트(Windows Git Bash + curl) 자체의
문제였고 애플리케이션 결함이 아니다.

## 결론
기획서에 정의된 엔드포인트(신청, 캘린더 이름 채우기)의 정상/에러 케이스가 전부 실제 HTTP
요청으로 검증됐고, 코드 리뷰에서 고친 사항도 실 DB 기준으로 재확인됐다. 이 이슈의 완료
조건(로컬 빌드/테스트 통과)을 충족하며, CI 통과 여부는 PR 생성(16단계) 후 확인한다 — 이
프로젝트의 CI 워크플로우는 `main`/`dev`로의 PR·push에서만 트리거되어 기능 브랜치 단독
push로는 미리 확인할 수 없다(#67 QA와 동일한 제약).
