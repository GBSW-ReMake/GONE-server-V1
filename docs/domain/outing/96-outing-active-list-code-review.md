# #96 외출증 실시간 목록 조회 API — 코드 리뷰 결과

관련 기획서: [96-outing-active-list.md](./96-outing-active-list.md)
리뷰 대상: `git diff dev...feat/#96-outing-active-list` (커밋 `a15640f`~`3122129`, 7개)

## 리뷰 범위/방법
- 기획서(`96-outing-active-list.md`)에 기록된 설계 판단(DB 레벨 페이지네이션 전환,
  `departedAt`+`id` 정렬, 단건 상세조회 인가 확장, N+1/인덱스 이슈 보류)을 기준으로 삼아
  diff 전체(`docs/domain/outing/96-outing-active-list.md`,
  `common/response/PageResponse.java`, `outing/controller/OutingController.java`,
  `outing/dto/OutingActiveResponse.java`, `outing/repository/OutingRepository.java`,
  `outing/service/OutingService.java`, 관련 테스트 3개 파일)를 정적으로 읽었다.
- 특히 신규 JPQL `findStudentRequestsPage`/`findTeacherReceivedPage`의 `statusEq`/
  `wantExpired` 조건을, 제거된 in-memory 로직(`resolveEffectiveStatus` +
  `OutingTimeUtils.isPastDeadline`)과 케이스별(필터 없음/APPROVED류 직접 매치/PENDING/
  MISSED)로 1:1 대조해 동등성을 확인했다 — 동일하다고 판단했다(아래 "확인한 것" 참고).
- `validateDetailAccess`의 `TEACHER` 전체 허용 확장은 기획서 "리스크 및 고려사항" 절의
  근거(/active가 이미 전체 TEACHER에게 같은 데이터를 노출)와 일치하는지 확인했다.
- N+1/인덱스 부재는 기획서에 이미 기록된 대로 `gh issue view 109`로 실제 이슈 등록 여부와
  항목 일치 여부를 확인했다(등록 확인, 항목 일치).
- 로컬 빌드/테스트/checkstyle은 재실행하지 않았다(이미 통과 확인됨, outing 패키지 148개
  테스트 통과).

## 확인한 것 (문제로 이어지지 않음)
- `findStudentRequestsPage`/`findTeacherReceivedPage`의 `statusEq`/`wantExpired` 분기가
  기존 `toFilteredResponses`+`resolveEffectiveStatus` 로직과 모든 `OutingQueryStatus` 값에
  대해 동일한 결과를 낸다(PENDING → `statusEq=PENDING, wantExpired=false`, MISSED →
  `statusEq=PENDING, wantExpired=true`, 그 외 → `statusEq=값, wantExpired=null`, 필터 없음 →
  둘 다 `null`). `OutingServiceTest`도 이 다섯 케이스를 개별 검증하고 있다.
- `PreAuthorize("hasAnyRole('DISCIPLINE', 'TEACHER', 'ADMIN')")`가 기획서 문구와 정확히
  일치하고, `OutingActiveListAuthorizationTest`가 4개 역할(무인증/STUDENT/DISCIPLINE·
  TEACHER·ADMIN)을 실제 필터 체인으로 검증한다.
- `OutingActiveResponse` 필드 순서/타입이 기획서 응답 예시(JSON)와 정확히 일치한다.
- `PageResponse.of(List, page, size)`(기존)와 `of(Page<T>)`(신규) 둘 다 실제로 쓰이고 있다
  (`SchoolCampService`는 여전히 전자, `OutingService` 3개 메서드는 모두 후자) — 기획서의
  "기존 팩토리는 그대로 둔다"는 판단이 실제로 지켜졌다.
- 제거된 `toFilteredResponses`가 다른 곳에서 참조되지 않는다(죽은 코드 없음).
- `findByStatus(OutingStatus)`(#42 스케줄러용, List 반환)와 신규
  `findByStatus(OutingStatus, Pageable)`(Page 반환)이 공존해도 Spring Data 파생 쿼리
  규칙상 문제없다(트레일링 `Pageable`은 쿼리 프로퍼티에 포함되지 않음).
- N+1 쿼리·`status` 컬럼 인덱스 부재는 `#109`(`외출 도메인 성능 최적화 백로그`, OPEN)에
  실제로 등록되어 있고, 이슈 본문의 두 항목이 기획서에 적힌 내용과 일치한다.
- diff에 포함된 파일 9개 전부가 outing 도메인 또는 그 도메인이 의존하는 공용
  `PageResponse`뿐이며(기획서에 "이번 이슈 범위를 넘는 리팩토링"으로 명시적으로 승인된
  범위), 기획서 범위를 벗어난 변경은 없었다.

## 발견 사항

### 1. 🟡 Medium — 기획서에 명시된 완료 조건("Postman 컬렉션 반영")이 diff에 반영되지 않음

**문제**: `docs/domain/outing/96-outing-active-list.md:206-209`의 "완료 조건(Definition of
Done)"은 "로컬 빌드/테스트 통과", "CI 통과"와 나란히 "Postman 컬렉션 반영"을 명시한다.
그런데 이번 diff(`git diff dev...feat/#96-outing-active-list --name-only`)에는
`postman/collections/gone-outing.postman_collection.json`이 포함되어 있지 않고, 실제로 그
파일을 열어봐도 `active`를 포함한 요청 항목이 없다(`grep -i active
postman/collections/gone-outing.postman_collection.json` 결과 없음). 그 파일의 마지막 커밋
시각(`2026-08-24 14:59`)도 이 브랜치의 첫 커밋(`a15640f`, `2026-08-25 09:27`)보다 이전이라,
Postman 워크스페이스에 API 호출로 직접 반영했다 하더라도 로컬 컬렉션 파일과는 최소한 싱크가
안 맞는 상태다. 지금 PR을 올리면 프론트/QA가 Postman에서 `GET /active`를 찾을 수 없다.

**해결 방안**:
1. `postman/collections/gone-outing.postman_collection.json`에 `GET /active` 요청을 직접
   추가하고 커밋에 포함한다 — 가장 확실하고 저장소 히스토리에 남지만, 기존 다른 요청
   항목과 필드 구조(쿼리 파라미터 프리셋, 테스트 스크립트 등)를 맞춰야 하는 수작업 비용이
   든다.
2. Postman API로 워크스페이스에 이미 반영했다면, 로컬 `.json` 파일을 워크스페이스에서
   다시 pull해 diff에 포함시킨다(보스 메모에 있는 "REST API로 push, git 폴더 동기화는
   지양" 방식과 일관됨) — 비용은 낮지만, 이미 워크스페이스에 반영됐는지부터 확인이
   필요하고 이 코드 리뷰만으로는 확인할 수 없다(로컬 리포에서 Postman API 키/네트워크
   접근 불가).
3. 완료 조건 자체를 "PR 머지 전까지"로 미루고 이번 코드 리뷰 단계에서는 지적만 남긴 채
   다음 QA(10단계) 단계에서 처리한다 — 코드 자체의 정확성과는 무관한 항목이라 이번
   코드 리뷰를 막을 필요는 없지만, PR을 올리기 전에 반드시 처리해야 한다(기획서 스스로
   정한 완료 조건이므로).

## 요약
Critical/High 없음. Medium 1건(Postman 컬렉션 미반영), Low 없음.
