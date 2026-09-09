# #98 외출증 오늘 전체 현황 조회 API — 코드 리뷰 결과

관련 기획서: [98-outing-daily-status.md](./98-outing-daily-status.md)
리뷰 대상: `git diff dev...85f160b`(브랜치 `feat/#98-outing-daily-status`, 커밋
`3490bfe`~`85f160b` 5개 — 브랜치 이름으로 재실행하면 이 리뷰 문서 자체가 추가 커밋으로
잡혀 재현되지 않으므로, 리뷰 시점의 커밋 SHA로 고정해 남긴다)

## 리뷰 범위/방법
- 기획서(`98-outing-daily-status.md`)에 기록된 설계 판단(#96 패턴 재사용, 인덱스를
  이번 범위에 포함, 기존 `OutingResponse` 재사용, `startTime`+`id` 정렬, `date` 범위
  제한 없음)을 기준으로 diff 전체(`docs/domain/outing/98-outing-daily-status.md`,
  `outing/controller/OutingController.java`, `outing/repository/OutingRepository.java`,
  `outing/service/OutingService.java`,
  `db/migration/V20260825134549__add_outing_date_status_index.sql`, 테스트 3개 파일)를
  정적으로 읽었다.
- 신규 JPQL `findByOutingDatePage`의 `statusEq`/`wantExpired` 조건을 기존
  `findStudentRequestsPage`/`findTeacherReceivedPage`(#41/#96) 및
  `OutingService.resolveStatusFilterParams`(#96)와 문자열 단위로 대조해 로직이
  동일한지(파라미터 순서, null 처리, `wantExpired` 판정식) 확인했다. 추가로
  `OutingTimeUtils.isPastDeadline`(`outingDate.isBefore(today) ||
  (outingDate.isEqual(today) && !now.isBefore(startTime))`)과 JPQL의
  `o.startTime <= :now` 조건이 논리적으로 동치인지(`!now.isBefore(startTime)` ==
  `now >= startTime` == `startTime <= now`) 확인했다 — 동치.
- `GET /api/v1/outings`(신규)와 `POST /api/v1/outings`(#29, 기존)의 라우팅 충돌 여부를
  `OutingController` 전체 매핑 목록(`@GetMapping`/`@PostMapping`/`@PatchMapping`
  11개)을 나열해 확인했다 — HTTP 메서드가 달라 충돌 없음. `/active`, `/me/requests`,
  `/me/received` 같은 정적 경로와 `/{code}` 사이의 기존 우선순위 규칙도 이번 변경으로
  달라지지 않았다(Spring `AntPathMatcher`가 정적 세그먼트를 경로변수보다 항상 우선).
- 마이그레이션 버전 형식(`V20260825134549`)을 [migration-convention.md](./migration-convention.md)
  기준으로 확인했다 — 해당 파일이 포함된 커밋(`4097861`)의 실제 커밋 시각이
  `2026-08-25T13:47:37+09:00`으로, 파일명 타임스탬프(`13:45:49`)와 약 2분 차이(파일
  작성 후 커밋까지의 정상적인 지연)로 KST 기준이 맞다.
- `OutingResponse`를 채우는 `toResponse(outing, student, teacher, today, now)`가
  `/me/requests`/`/me/received`(#41)가 쓰는 것과 완전히 동일한 private 메서드이고,
  이번 이슈에서 변경되지 않았음을 확인했다(`teacherName` 포함 모든 필드가 기존 경로와
  동일하게 채워짐).
- diff에 포함된 파일 8개 전부가 outing 도메인(및 그 도메인의 신규 마이그레이션)뿐이며,
  기획서 범위를 벗어난 변경은 없었다.
- 관련 과거 PR(#110/`#96`, #47/`#41`)의 리뷰 댓글을 `gh pr view --json reviews,comments`로
  확인했다 — CodeRabbit 자동 리뷰는 마크다운 lint 지적(fenced code block 언어 표기 등)
  뿐이었고, 이번 PR의 코드 변경과 관련된 재발 가능한 지적은 없었다.
- 로컬 빌드/테스트/checkstyle은 재실행하지 않았다(이미 통과 확인됨, 전체 454개 테스트
  통과).

## 확인한 것 (문제로 이어지지 않음)
- `findByOutingDatePage`의 `wantExpired` 판정 JPQL이 `findStudentRequestsPage`/
  `findTeacherReceivedPage`와 학생/선생님 조건, 날짜 범위(`BETWEEN` vs `=`)를 뺀
  나머지 전부(파라미터 순서, `statusEq`/`wantExpired` null 처리, 마감 판정 부등호)가
  글자 그대로 동일하다.
- `resolveStatusFilterParams`(#96에서 도입된 private 헬퍼)를 변경 없이 그대로 호출하고
  있고, `OutingServiceTest.GetDailyOverview`가 PENDING/MISSED/APPROVED 세 경우 모두
  `statusEq`/`wantExpired` 조합을 개별 검증한다.
- `GET /api/v1/outings`와 `POST /api/v1/outings`는 같은 경로·다른 HTTP 메서드라
  Spring 라우팅 충돌이 없고, 기존 11개 매핑 어디와도 경로 우선순위 문제가 없다.
- `(outing_date, status)` 인덱스 마이그레이션(`V20260825134549`)이 컨벤션에 맞는
  타임스탬프 버전을 쓰고, 기존 `idx_outing_student_date`와 이름이 겹치지 않는다.
- `OutingResponse` 재사용 판단이 기획서 근거(#96과 달리 필드를 좁힐 이유가 없음)와
  일치하고, `teacherName` 등 관련 LAZY 연관 필드가 기존 `toResponse` 경로를 그대로 타
  정상적으로 채워진다.
- N+1(student/teacher의 LAZY `gbsw`)은 기획서에 이미 "#109 성능 백로그에 outing 도메인
  전반으로 기록되어 있어 이 이슈에서 별도 처리하지 않는다"고 명시돼 있고, `#96` 코드
  리뷰에서 이미 `gh issue view 109` 등록 여부를 확인한 바 있어 이번에 다시 검증하지
  않았다.
- 테스트 구성(`OutingServiceTest.GetDailyOverview`, `OutingControllerTest.GetDailyOverview`,
  `OutingDailyOverviewAuthorizationTest`)이 기획서 "테스트" 절에 적힌 항목(기본값,
  상태 필터 변환, 빈 결과, 페이지 메타데이터, 파라미터 검증, 인가 4역할)을 빠짐없이
  커버한다.

## 발견 사항

### 1. 🟡 Medium — 기획서에 명시된 완료 조건("Postman 컬렉션 반영")이 diff에 반영되지 않음 (해결됨, 커밋 `db1763a`)

**문제**: `docs/domain/outing/98-outing-daily-status.md:126-130`의 "완료 조건(Definition of
Done)"은 "로컬 빌드/테스트 통과", "CI 통과"와 나란히 "Postman 컬렉션 반영"을 명시한다.
그런데 이번 diff(`git diff dev...feat/#98-outing-daily-status --name-only`)에는
`postman/collections/gone-outing.postman_collection.json`이 포함되어 있지 않다.
실제로 그 파일에서 `daily`/`GET /api/v1/outings`(경로 파라미터 없는 요청) 항목을
검색해도 없고, 파일의 마지막 커밋(`a8db99a`, `2026-08-25 11:49:46`, #96 후속 반영)도
이 브랜치의 첫 커밋(`3490bfe`, `2026-08-25 13:45:16`)보다 이전이다. 지금 PR을 올리면
프론트/QA가 Postman에서 새 엔드포인트를 찾을 수 없다 — `#96`(`96-outing-active-list-code-review.md`
1번 항목)에서 이미 한 번 같은 패턴으로 지적된 사안이 이번 이슈에서도 반복됐다.

**해결 방안**:
1. `postman/collections/gone-outing.postman_collection.json`에 `GET /outings`(전체
   현황) 요청과 에러 케이스(401/403/`date` 형식 오류 400/`status` 형식 오류 400)를
   직접 추가하고 커밋에 포함한다 — `#96`이 실제로 택한 방법(커밋 `a8db99a`)과 동일하게
   기존 항목과 구조를 맞추는 수작업 비용이 들지만, 가장 확실하고 저장소 히스토리에
   남는다.
2. Postman API로 워크스페이스에 이미 반영했다면, 로컬 `.json` 파일을 워크스페이스에서
   다시 pull해 diff에 포함시킨다(보스 메모의 "REST API로 push, git 폴더 동기화는 지양"
   방식과 일관됨) — 비용은 낮지만, 이미 워크스페이스에 반영됐는지 이 코드 리뷰만으로는
   확인할 수 없다(로컬 리포에서 Postman API 키/네트워크 접근 불가).
3. 이번 코드 리뷰 단계에서는 지적만 남기고 다음 QA(10단계)에서 처리한다 — 코드 자체의
   정확성과는 무관한 항목이라 이번 코드 리뷰를 막을 필요는 없지만, 기획서 스스로 정한
   완료 조건이므로 PR을 올리기 전에는 반드시 처리해야 한다.

**반영 결과(15단계, 커밋 `db1763a`)**: 방안 1을 택해 최상위에 `GET /api/v1/outings`
요청, 에러 케이스 4건(401/403/date 400/page 400), `#98 오늘 전체 현황 조회 확인` 검증
폴더(신청→오늘 조회→status 필터 2종→날짜 필터)를 직접 추가했다. newman으로 로컬
실서버 대상 end-to-end 실행해 11개 assertion 전부 통과를 확인한 뒤 Postman 워크스페이스에
API로 push했다.

## 요약
Critical/High 없음. Medium 1건(Postman 컬렉션 미반영 — 해결됨). Low 없음.

## 부록 — QA 중 발견된 별도 Critical 버그(9단계 리뷰 시점엔 발견 못 함)
10단계 실서버 QA에서 `status=MISSED` 필터가 `OutingMissedScheduler`(#42)가 이미 DB에
반영한 행을 놓치는 회귀를 발견했다(#96에서 도입된 공용 패턴의 결함, 이미 머지된
`/me/requests`·`/me/received`에도 영향). 이 리뷰(9단계) 시점에는 정적 코드 대조만으로는
잡히지 않았다 — `resolveStatusFilterParams`/JPQL이 문법적으로는 일관되게 재사용되고
있어서 "재사용이 올바른가"만 확인했지, 재사용된 원본 로직 자체의 결함까지는 잡아내지
못했다. 상세는 [98-outing-daily-status-QA.md](./98-outing-daily-status-QA.md) 참고.
