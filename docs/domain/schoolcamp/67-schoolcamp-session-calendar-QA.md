# #67 스쿨캠핑 세션 등록 + 캘린더 조회 API — QA 결과

관련 기획서: [67-schoolcamp-session-calendar.md](./67-schoolcamp-session-calendar.md)
관련 코드 리뷰: [67-schoolcamp-session-calendar-code-review.md](./67-schoolcamp-session-calendar-code-review.md)
(9단계 코드 리뷰 지적 사항은 QA 이전에 전부 반영 완료 — 이 문서는 QA에서 새로 발견된
것만 다룬다)

## 검증 방법/범위
- `./gradlew build`(전체: checkstyle + 모든 테스트, 249개) 통과 확인.
- 로컬 서버 실제 기동(`./gradlew bootRun`, `dev` 프로필) — `V11__add_schoolcamp_session.sql`이
  실제 dev DB에 자동 적용됨을 확인(`Current version of schema gone: 11`).
- 기존 dev DB의 실 계정(`teacher1`/`1234`)에 QA 목적으로 임시로 `ADMIN` 역할을 DB에서 직접
  부여해 사용했다(역할 부여 관리자 화면/API가 아직 없음, `#15` 진행 중) — 검증 후 원래
  역할(`TEACHER`만)로 복원했다. 등록한 세션 데이터도 QA 종료 후 전부 삭제해 dev DB를 QA
  이전 상태로 되돌렸다.

## 실제 HTTP E2E 검증

### `POST /api/v1/school-camps` (관리자 날짜 일괄 등록)
1. 평일 2건(`20260504`, `20260505`) 등록 → **`201 Created`**, `sessionId`/`campDate` 정상
   반환(코드 리뷰에서 잡은 "명시된 201 대신 200 반환" 버그가 실제로 고쳐졌음을 확인)
2. 금요일이 포함된 날짜(`20260508`, `20260501`) → `400` `SCHOOLCAMP_005`
3. 이미 등록된 날짜(`20260504`) 재등록 → `409` `SCHOOLCAMP_006`
4. 요청 안에서 같은 날짜가 두 번(`20260511`, `20260511`) → `409` `SCHOOLCAMP_006`(코드
   리뷰에서 잡은 자기중복 케이스가 도메인 에러 코드로 정상 응답됨을 확인)
5. 형식은 맞지만 실존하지 않는 날짜(`20261332`, 13월) → `400` `SCHOOLCAMP_005`(코드 리뷰에서
   잡은 `DateTimeParseException` → 500 누수 버그가 고쳐졌음을 확인)
6. `STUDENT` 역할 계정으로 호출 → `403`
7. 인증 없이 호출 → **`401`**(코드 리뷰 중 발견한 `SecurityConfig` 인증 요구 누락이 실제로
   고쳐져 더 이상 `403`이 아님을 확인)

### `GET /api/v1/school-camps?month=` (캘린더 조회)
8. 위에서 등록한 `2026년 5월` 조회 → 두 세션 모두 `status: "OPEN"`,
   `teacherDisplayName`/`applicantDisplayName` 둘 다 `null`(기획서대로 — `#68` 이전이라
   신청 데이터 자체가 없음)
8-b. `SchoolCampApplication`이 아직 없어 API만으로는 `CLOSED`를 재현할 수 없어(기획서
   "리스크" 절에서 이미 예견한 제약), DB에서 세션 하나의 `taken_at`을 직접 채운 뒤 재조회 →
   해당 세션만 `status: "CLOSED"`로 정확히 반영되고 두 이름 필드는 여전히 `null`(코드가
   `taken_at` 유무만으로 상태를 정확히 계산함을 확인)
9. `month` 파라미터 없이 호출 → `400`
10. `month` 형식이 `yyyyMM`이 아님(`"2026"`) → `400` `COMMON_001`(신규 에러 코드를 만들지
    않기로 한 설계 결정이 실제로 의도대로 동작함을 확인)
11. 인증 없이 호출 → `401`

## 발견 사항
Critical/High/Medium/Low 모두 없음 — 코드 리뷰에서 지적/발견된 항목들이 전부 실제 HTTP
요청 레벨에서도 의도대로 고쳐졌음을 재확인했을 뿐, QA 단계에서 새로 발견된 문제는 없다.

## 결론
기획서에 정의된 두 엔드포인트의 정상/에러 케이스가 실제 HTTP 요청으로 전부 검증됐다.
이 이슈의 완료 조건(로컬 빌드/테스트 통과)을 충족하며, CI 통과 여부는 PR 생성(16단계) 후
확인한다 — 이 프로젝트의 CI 워크플로우는 `main`/`dev`로의 PR·push에서만 트리거되어 기능
브랜치 단독 push로는 미리 확인할 수 없다.
