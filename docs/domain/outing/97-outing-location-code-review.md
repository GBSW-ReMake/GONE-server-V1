# #97 외출증 위치/동선 조회 + 위치 핑 전송 API — 코드 리뷰 결과

리뷰 범위: `git diff 6c012cc..HEAD`(브랜치 `feat/#97-outing-location`, 6c012cc 위 7개 커밋,
`docs(outing)` 기획서 커밋 포함 총 12개 파일 변경). 대조 문서: 승인된 기획서
[`docs/domain/outing/97-outing-location.md`](./97-outing-location.md), 프로젝트 컨벤션
(`docs/rules/code-style.md`, `docs/rules/api-design.md`, `docs/rules/test-convention.md`,
`docs/rules/sentence-refinement.md` 원칙 6), 그리고 같은 파일에서 선행 구현된 #43(출발/도착
보고)의 접근 제어/테스트 패턴. `OutingService.java`/`OutingController.java`/`OutingLocation`
엔티티·리포지토리·DTO/`OutingErrorCode`/Flyway 마이그레이션/`OutingServiceTest`/신규 인가 테스트
2개 파일을 모두 직접 읽고 검토했다.

## 요약
- Critical: 없음
- High: 1건(#1) — 반영 완료
- Medium: 2건(#2, #3) — 반영 완료
- Low: 3건(#4, #5, #6) — 반영 완료

**반영 내역(2026-08-25):** 모든 항목이 기획서에 이미 명시된 요구사항을 그대로 채우거나
(#1, #4, #5), 기존 컨벤션(동률 시 `id` 보조 정렬)과 이미 문서화된 응답 불변식("항상
`recordedAt` 오름차순")을 지키는 방향의 구현 세부사항 수정(#2, #3)이라 별도 설계 변경
승인 없이 즉시 반영했다.
1. IDOR/세부 역할: `OutingLocationOwnershipIntegrationTest` 추가(학생 2명 + 외출증을 실
   DB에 저장하고 `@SpringBootTest`로 비소유 학생 핑 403, 담당 아닌 TEACHER 조회 403,
   DISCIPLINE 조회 성공을 검증).
2. 정렬: `OutingLocationRepository.findByOutingIdOrderByRecordedAtAscIdAsc`로 `id`를
   보조 정렬 키로 추가.
3. 정렬 불변식: `getOutingLocations`가 `path` 조립 직후 `recordedAt` 기준으로 한 번 더
   정렬해 경합 상황에서도 응답 계약을 강제.
4. `OutingServiceTest.RecordLocationPing`에 연속 핑 저장 테스트 추가.
5. `OutingControllerTest`에 `RecordLocationPing`/`GetOutingLocations` `@Nested` 추가.
6. `OutingController` 클래스 Javadoc에 #97 언급 추가.

엔드포인트 계약(경로/DTO/에러 코드/상태 코드), `validateLocationAccess`의 좁은 접근 규칙
(담당 선생님 본인 + `DISCIPLINE`/`ADMIN`만 허용, 일반 `TEACHER` 배제)은 기획서와 정확히
일치하게 구현되어 있었다. 문제는 대부분 **기획서가 명시적으로 요구한 테스트 커버리지 일부가
실제로는 빠졌다**는 점과, 정렬/동시성 관련 두 가지 엣지 케이스에서 나왔다.

---

### 1. 🟠 High — 기획서가 요구한 소유권/IDOR 통합 테스트가 더 약한 테스트로 대체됨

**문제**: 기획서(`docs/domain/outing/97-outing-location.md:205-207`)는 다음을 실 DB 기반
`@SpringBootTest` 통합 테스트로 요구한다.
> `OutingLocationOwnershipIntegrationTest`(신규, 실 DB 기반): 본인 아닌 학생이 핑 전송
> 시도 → 403(기존 `OutingDepartReturnOwnershipIntegrationTest`와 동일한 패턴), 담당
> 아닌 `TEACHER`가 조회 시도 → 403, `DISCIPLINE`은 조회 성공

실제로 이 커밋에 추가된 것은 `OutingLocationOwnershipIntegrationTest`가 아니라 이름과
검증 내용이 다른 두 파일이다.
- `src/test/java/com/remake/gone/outing/controller/OutingLocationPingAuthorizationTest.java`
  (전체 55줄): 인증 없음(401)과 `TEACHER` 역할(403, `@PreAuthorize("hasRole('STUDENT')")`
  검증)만 확인한다. 이는 애노테이션 자체가 이미 보장하는 역할 게이트를 재확인하는 것으로,
  기획서가 원한 "본인 아닌 학생이 핑 전송 시도 → 403"(서비스 계층 `validateOwnership`을
  실 DB로 검증하는 IDOR 케이스)은 어디에도 없다.
- `src/test/java/com/remake/gone/outing/controller/OutingLocationsAuthorizationTest.java`
  (전체 34줄): 인증 없음(401) 단 하나만 확인한다. 기획서가 원한 "담당 아닌 `TEACHER` 조회
  시도 → 403", "`DISCIPLINE`은 조회 성공"은 실 DB/필터 체인을 통과하는 어떤 테스트에도
  없다 — `OutingServiceTest.GetOutingLocations`(`rejectsUnassignedTeacherRole`/
  `allowsDisciplineRole`, `src/test/java/.../service/OutingServiceTest.java:1846,1877`)는
  순수 Mockito 단위 테스트라 `@PreAuthorize("isAuthenticated()")` + 실제 필터 체인 +
  `UserRoleRepository` 조회까지 거치는 end-to-end 경로에서는 이 케이스가 한 번도
  실행되지 않는다.

같은 컨트롤러의 #43(`OutingDepartReturnOwnershipIntegrationTest.java`)이 이미 정확히 이
패턴(학생 2명 + 외출증을 실 DB에 저장한 뒤 소유자 아닌 학생 토큰으로 요청 → 403 확인)을
확립해뒀는데도, #97은 같은 위험(위치 데이터는 개인정보이고 IDOR 방지가 이 이슈의 핵심
보안 요구사항)에 대해 동일한 강도의 검증을 도입하지 않았다. 접근 제어 관련 회귀(예:
`validateOwnership`/`validateLocationAccess` 호출을 실수로 지우거나 순서를 바꾸는 리팩터링)가
실서버 경로에서 전혀 잡히지 않는다.

**해결 방안**:
1. 기획서에 적힌 대로 `OutingLocationOwnershipIntegrationTest`를
   `OutingDepartReturnOwnershipIntegrationTest`를 그대로 본떠 추가한다(학생 2명 + 외출증
   fixture, 비소유 학생 토큰으로 핑 시도 → 403, 담당 아닌 `TEACHER`/`DISCIPLINE` 토큰으로
   조회 시도). 이 프로젝트에 이미 확립된 패턴이라 비용이 낮고, 정확히 요구된 커버리지를
   채운다 — 가장 권장.
2. 최소한 현재의 `OutingLocationPingAuthorizationTest`/`OutingLocationsAuthorizationTest`에
   테스트 메서드를 추가해 같은 파일 안에서 IDOR/역할 분기 케이스를 보강한다 — 새 클래스를
   만들지 않아 변경 범위는 작지만, 기획서가 명시한 파일명·구조와 달라져 다음 리뷰어가
   "왜 이름이 다른가"를 다시 확인해야 하는 비용이 남는다.
3. 이번 PR에서는 보류하고 10단계 QA에서 실서버로 수동 재현해 QA 문서에 남기는 것으로
   대체한다 — 비용은 가장 낮지만, 이후 회귀가 생겨도 자동으로 잡히지 않고 기획서에
   명시된 완료 조건과도 어긋난다.

---

### 2. 🟡 Medium — 위치 핑 정렬에 동률 시 결정론적 보조 정렬 키가 없음

**문제**: `OutingLocationRepository.findByOutingIdOrderByRecordedAtAsc`
(`src/main/java/com/remake/gone/outing/repository/OutingLocationRepository.java:18`)는
`recordedAt` 단일 컬럼으로만 정렬한다. 마이그레이션
(`src/main/resources/db/migration/V20260825160335__add_outing_location.sql:7`)이 정의한
`recorded_at DATETIME`은 초 단위 정밀도라(소수점 자리 미지정), 같은 초 안에 들어온 핑
두 건은 DB가 반환하는 순서를 보장하지 않는다.

기획서(`97-outing-location.md:30-34`)는 "위치 핑 최소 간격 검증을 도입하지 않는다"고
명시적으로 확정했다 — 즉 클라이언트가 짧은 간격(같은 초)으로 연속 핑을 보내는 상황이
정상 케이스로 허용된다. 반면 같은 서비스 파일 안의 다른 목록 조회들
(`OutingService.java:80-93`의 `LIST_QUERY_SORT`/`ACTIVE_LIST_SORT`/`DAILY_OVERVIEW_SORT`
주석)은 정확히 이 이유("동률 시 페이지 경계/순서가 흔들리는 것을 방지")로 `id`를 보조
정렬 키로 이미 추가해뒀다. `getOutingLocations`
(`src/main/java/com/remake/gone/outing/service/OutingService.java:474`)가 조립하는
`path` 배열은 클라이언트가 그대로 이어 폴리곤으로 렌더링하는 값이라(기획서 12-15줄),
같은 초에 들어온 두 핑의 순서가 실제 이동 방향과 반대로 뒤집혀 그려질 수 있다.

**해결 방안**:
1. `findByOutingIdOrderByRecordedAtAsc`를
   `findByOutingIdOrderByRecordedAtAscIdAsc`로 바꿔 `id`를 보조 정렬 키로 추가한다 —
   이 서비스에 이미 확립된 컨벤션과 일치하고, 쿼리 메서드 이름만 바꾸는 최소 변경이다.
   다만 `id`(AUTO_INCREMENT 삽입 순서)가 항상 `recordedAt`과 같은 방향으로 증가한다는
   전제가 필요한데, 서버가 수신 시각을 그대로 `recordedAt`으로 저장하므로(요청 처리
   순서 = 삽입 순서) 실질적으로 이 전제는 성립한다.
2. 그대로 둔다 — 기획서가 이미 "최소 간격 검증 없음"을 YAGNI로 확정했고, 같은 초 안에
   여러 핑이 들어오는 경우는 드물며, 순서가 한두 프레임 뒤바뀌어도 폴리곤 전체 모양에는
   큰 영향이 없다는 점을 근거로 삼는다 — 비용은 0이지만, 이미 이 파일 안에서 세 번이나
   같은 이유로 도입한 보호 장치를 이 조회에만 빠뜨린 채 남겨두는 것이라 다음 리뷰어가
   "빠뜨린 건가?"로 다시 의심하게 된다(api-design.md "마스터 기획서 재검토" 절이 지적하는
   바로 그 위험).

---

### 3. 🟡 Medium — 핑 전송과 도착 보고의 동시 요청 시 동선 정렬 불변식이 깨질 수 있음

**문제**: `recordLocationPing`(`OutingService.java:436-452`)은 상태 검증(`status ==
DEPARTED`)과 `OutingLocation` 저장을 하나의 트랜잭션 안에서 낙관적 락 충돌 처리 없이
수행한다. `returnOuting`(`OutingService.java:404-424`)은 별도 트랜잭션에서
`saveOrRejectAsAlreadyProcessed`(버전 충돌 시 409로 변환, `OutingService.java:509-515`)로
보호된다. 학생이 도착 처리를 하는 순간과 거의 동시에(예: 클라이언트가 마지막 위치 핑을
자동 전송하는 타이밍과 "도착" 버튼 탭이 겹치는 경우) 핑 요청의 트랜잭션이 아직 커밋되지
않은 `status = DEPARTED`를 읽고 통과하면, `recordedAt`이 실제 `returnedAt`보다 늦은
`OutingLocation` 행이 저장될 수 있다.

`getOutingLocations`(`OutingService.java:463-483`)는 `outingLocationRepository`가 반환한
핑 목록 뒤에 무조건 도착 좌표를 마지막으로 붙인다(477-480줄) — 실제 타임스탬프 순서를
다시 확인하지 않는다. `OutingLocationsResponse`의 Javadoc
(`src/main/java/com/remake/gone/outing/dto/OutingLocationsResponse.java:11-12`)이 명시한
"전부 `recordedAt` 오름차순" 불변식이 이 경합에서는 깨지고, 클라이언트가 그 배열을
그대로 이어 그리는 폴리곤의 마지막 구간이 시간상 거꾸로 그려질 수 있다.

**해결 방안**:
1. `getOutingLocations` 조립 단계에서 `path`를 `recordedAt` 기준으로 한 번 더
   정렬(`List.sort` 또는 스트림 `sorted`)해 응답 직전에 불변식을 강제한다 — 원인(경합)을
   막지는 못하지만 응답 계약은 항상 지켜지고, 구현 비용이 가장 낮다. 다만 "왜 이미 시간순으로
   조립한 리스트를 다시 정렬하나"가 코드만 봐서는 안 드러나므로, 이 경합 상황을 설명하는
   주석이 필요하다.
2. `recordLocationPing`에서도 `depart`/`return`처럼 낙관적 락을 활용해, 저장 직전에 최신
   `outing.getStatus()`를 재확인(`saveAndFlush` + 버전 충돌 처리 또는 재조회)하도록 강화한다
   — 근본 원인(경합)을 막지만, 매 핑마다 트랜잭션 비용이 늘고 기획서가 "핑은 무거운 처리가
   필요 없다"고 명시한 설계 의도와 다소 배치된다.
3. 그대로 둔다 — 발생 확률이 매우 낮고(정확히 도착 처리와 같은 초에 핑이 도착해야 함),
   영향도 "지도에서 마지막 구간이 잠깐 어색하게 보임" 수준이라 기능적 손해가 크지
   않다는 판단. 다만 이 경우 DTO Javadoc의 "전부 오름차순" 문구를 "일반적으로 오름차순"
   등으로 완화해 실제 보장 범위를 정확히 남겨야 한다.

---

### 4. 🟢 Low — 기획서가 명시한 "연속 핑 모두 저장" 테스트 케이스 누락

**문제**: 기획서(`97-outing-location.md:193-194`)는
`OutingServiceTest.RecordLocationPing`에 다음 케이스를 요구한다.
> 최소 간격 검증 없이 연속 핑이 전부 저장되는지 확인(짧은 간격 두 번 호출 → 두 건 모두
> 저장)

실제 `RecordLocationPing`(`src/test/java/com/remake/gone/outing/service/OutingServiceTest.java:1719-1798`)에는
`rejectsWhenOutingNotFound`/`rejectsWhenNotOwner`/`rejectsWhenNotDeparted`/
`savesPingEvenOutsideSchoolRadius` 4개 테스트만 있고, 짧은 간격으로 두 번 연속 호출해도
둘 다 저장되는지(= 최소 간격 검증이 실제로 없는지) 확인하는 테스트가 없다. 지금은
`recordLocationPing`에 그런 검증 로직이 아예 없어 통과하겠지만, 이 테스트가 없으면 나중에
누군가 "속도 제한이 필요하다"고 판단해 검증을 추가해도(기획서가 이미 예상한 미래
변경) 이 결정을 뒤집는 회귀를 잡을 안전망이 없다.

**해결 방안**:
1. `RecordLocationPing`에 `savesBothPingsWithoutMinimumIntervalCheck` 같은 이름으로
   테스트를 추가한다 — `recordLocationPing`을 같은 `now`(또는 1초 차이)로 두 번 호출하고
   `outingLocationRepository.save`가 2번 호출됐는지(`verify(..., times(2))`) 확인한다.
   기획서에 이미 적힌 케이스라 비용이 낮고 바로 채울 수 있다.
2. 생략한다 — 지금 구현에 최소 간격 검증 로직 자체가 없어 이 테스트가 항상 통과할
   수밖에 없는 "동어반복" 테스트에 가깝다고 보고, 실제로 검증 로직이 추가되는 시점에
   테스트도 같이 추가하는 편이 낫다는 판단. 다만 기획서에 이미 명시된 항목을 구현
   시점에 스스로 빼는 것이므로, 이 판단을 PR 설명이나 QA 문서에 남겨야 한다.

---

### 5. 🟢 Low — 기획서가 명시한 `OutingControllerTest` 케이스(좌표 400/파라미터 전달) 누락

**문제**: 기획서(`97-outing-location.md:203-204`)는 다음을 요구한다.
> `OutingControllerTest.RecordLocationPing`/`GetOutingLocations`(신규 `@Nested`): 요청
> 검증(좌표 범위 밖 400), principal·파라미터 전달 확인

`git diff 6c012cc..HEAD --stat` 기준으로 `OutingControllerTest.java`는 이번 diff에서 전혀
수정되지 않았다. 같은 파일 안에 이미 `DepartOuting`/`ReturnOuting` `@Nested` 클래스가
`returns400WhenLatitudeOutOfRange`/`returns400WhenLongitudeOutOfRange`/principal 전달
테스트를 갖고 있는데(`OutingControllerTest.java:221-300` 부근), 같은 `OutingLocationRequest`
DTO를 재사용하는 `/locations` POST 엔드포인트에는 대응하는 테스트가 없다. `OutingLocationRequest`
자체의 Bean Validation은 형제 엔드포인트 테스트로 이미 어느 정도 커버되어 위험도는 낮지만,
`recordLocationPing` 메서드 시그니처의 `@Valid` 애노테이션이 빠지거나 새 라우팅이
깨지는 회귀는 이 엔드포인트 전용 테스트가 없으면 컨트롤러 레이어에서 잡히지 않는다.

**해결 방안**:
1. `DepartOuting`/`ReturnOuting` `@Nested` 클래스를 그대로 본떠 `RecordLocationPing`
   `@Nested` 클래스를 추가한다(principal/code/좌표 전달 확인 1개 + 좌표 범위 400 2개
   정도) — 기존 패턴을 복사하는 수준이라 비용이 낮다. `GetOutingLocations`는 요청 바디가
   없어 principal 전달 확인 정도만 추가하면 된다.
2. 생략한다 — `OutingLocationRequest`의 Bean Validation 자체는 이미 다른 엔드포인트
   테스트로 검증됐고, `OutingServiceTest`가 서비스 로직을 충분히 덮으므로 컨트롤러
   레이어 중복 테스트는 우선순위가 낮다고 판단한다. 다만 이 경우 "왜 이 엔드포인트만
   컨트롤러 테스트가 없나"를 판단한 근거를 남겨야, 항목 1과 마찬가지로 다음 리뷰어가
   빠뜨린 것으로 오해하지 않는다.

---

### 6. 🟢 Low — 컨트롤러 클래스 Javadoc이 #97 엔드포인트를 언급하지 않음

**문제**: `OutingController` 클래스 Javadoc
(`src/main/java/com/remake/gone/outing/controller/OutingController.java:35-40`)은
"#29에서 신청, #30에서 승인, #31에서 거절, #41에서 조회(본인 신청/배정/단건 상세), #43에서
출발/도착 보고 엔드포인트를 구현했다"까지만 나열하고, 이번 #97이 추가한
`recordLocationPing`/`getOutingLocations`는 언급되지 않는다. 이 컨트롤러를 건드린 이전
이슈들은 전부 이 목록에 자기 자신을 추가해왔는데(#96/#98은 개별 메서드 Javadoc에는 있지만
클래스 Javadoc에는 원래도 없었던 것으로 보인다 — 다만 #43까지는 일관되게 나열됨), #97만
빠지면 다음에 이 클래스를 훑어보는 사람이 "이 컨트롤러가 어떤 이슈들로 만들어졌는지"를
클래스 Javadoc만으로 파악할 때 #97을 놓친다.

**해결 방안**:
1. 클래스 Javadoc 문장 끝에 "#97에서 위치 핑 전송/동선 조회"를 추가한다 — 한 줄 수정으로
   끝나는 가장 저비용 수정.
2. 생략한다 — 이미 #96/#98처럼 최근 이슈들은 클래스 Javadoc 나열에서 빠지기 시작한
   전례가 있어(개별 메서드 Javadoc이 `(#97)`로 출처를 남기고 있으므로 정보 손실은 아님),
   클래스 Javadoc의 나열식 이력 관리를 더 이상 유지하지 않기로 암묵적으로 전환된
   것으로 볼 수도 있다는 판단. 다만 이 경우 "왜 #96부터 나열을 멈췄는지"가 코드
   어디에도 명시되어 있지 않아, 다음 사람이 다시 같은 질문을 하게 된다.

---

## Critical 없음
원격 코드 실행, 인증 우회(토큰 검증 자체 무력화), 데이터 파괴/유실, SQL 인젝션 등 즉시
서비스에 치명적인 문제는 발견되지 않았다. `validateLocationAccess`/`validateOwnership`
자체의 로직은 정확하며(단위 테스트 기준 통과), 위 1번 항목은 "그 로직이 실제 서버 경로에서
검증되지 않는다"는 테스트 커버리지 문제이지 로직 결함 자체는 아니라 Critical이 아닌 High로
분류했다.

## 확인한 항목 중 문제 없었던 것
- 엔드포인트 경로/DTO 재사용/에러 코드(`OUTING_016` 상태 코드·메시지)/응답 필드명(`code`
  사용, `outingId` 미노출)은 기획서와 정확히 일치.
- `validateLocationAccess`(`OutingService.java:485-494`)가 `getOutingDetail`의
  `validateDetailAccess`(`OutingService.java:855-868`)보다 의도적으로 좁게(일반 `TEACHER`
  배제) 구현되어 있음을 코드로 직접 확인 — 기획서 요구사항과 정확히 일치.
- `getOutingLocations`의 쿼리는 `findByCode` 1회 + `findByOutingIdOrderByRecordedAtAsc`
  1회로 고정 쿼리 수이며, `OutingLocation.outing`은 지연 로딩인데 응답 조립 시 접근하지
  않아 N+1 없음.
- 페이지네이션 미도입은 기획서에 근거와 함께 명시되어 있고(시계열 데이터량이 작고
  `outing_id`로 항상 좁혀짐), api-design.md 5번 원칙("이번 범위에는 넣지 않는다"는
  판단을 남기는 것)을 충족.
- 테스트 구조(`@Nested` + 한글 `@DisplayName` + `BDDMockito given/willReturn` +
  AssertJ)는 `docs/rules/test-convention.md`와 일치.
