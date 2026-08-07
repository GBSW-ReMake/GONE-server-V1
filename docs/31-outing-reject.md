# #31 외출증 거절 API — 기획서

관련 이슈: [#31 외출증 거절 API 구현](https://github.com/GBSW-ReMake/GONE-server-V1/issues/31)
선행 이슈: #30(승인) — 소유권 확인 패턴, `OUTING_004`~`006` 재사용
마스터 기획서: [outing-domain.md](./outing-domain.md)의 "3. `PATCH /api/v1/outings/{code}/reject`"

## 개요/목적
담당 선생님이 학생의 외출증 신청을 거절하는 API. 승인(#30)의 반대 케이스로, 상태 전이만
`APPROVED`가 아니라 `REJECTED`로 바뀌고 거절 사유(`rejectedReason`)를 같이 저장한다는 점만
다르다. 이 이슈가 끝나면 신청(#29)→승인/거절(#30/#31) 흐름이 완성된다.

## 엔드포인트

### `PATCH /api/v1/outings/{code}/reject` — 선생님 거절
**권한**: `TEACHER` + 본인이 그 외출증의 `teacherUserId`와 일치(승인과 동일한 소유권 확인
패턴, `OutingService.approveOuting`의 체크 로직 재사용)

**요청**
```json
{ "rejectedReason": "지금은 상담 시간이라 곤란해요" }
```
- `rejectedReason`: 필수, not blank, 최대 200자(`Outing.rejectedReason` 컬럼 `length = 200`과
  일치)

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": {
    "code": "8A1zx9202",
    "studentNickname": "길동이",
    "studentProfileImageUrl": "https://.../profile/1/abc.jpg?X-Amz-...",
    "studentRealName": "홍길동",
    "studentGrade": 3,
    "studentClassNo": 4,
    "teacherName": "김선생",
    "reason": "치과 진료",
    "outingDate": "20260814",
    "timeSlot": "LUNCH",
    "startTime": "12:30",
    "endTime": "13:40",
    "status": "REJECTED",
    "rejectedReason": "지금은 상담 시간이라 곤란해요"
  },
  "message": "외출증을 거절했습니다.",
  "code": null
}
```

**구현 로직** (`OutingService.approveOuting`과 거의 동일한 뼈대)
1. `code`로 `Outing` 조회(`findByCode`), 없으면 `404` `OUTING_006`
2. `principal.userId() == outing.getTeacher().getId()` 확인, 아니면 `403` `OUTING_004`
3. `outing.getStatus() == PENDING` 확인, 아니면 `409` `OUTING_005`
4. `status = REJECTED`, `rejected_reason = request.rejectedReason()` 저장

**에러**
- 본인이 지정된 선생님이 아님 → `403` `OUTING_004`
- 이미 승인/거절 처리된 건(`PENDING`이 아님) → `409` `OUTING_005`
- 존재하지 않는 `code` → `404` `OUTING_006`
- `rejectedReason`이 비어있음/200자 초과 → `400` (Bean Validation, `MethodArgumentNotValidException`
  → 기존 `GlobalExceptionHandler` 공통 처리, 신규 `ErrorCode` 불필요)

## 데이터 모델 변경
없음 — `Outing.rejectedReason` 컬럼은 `V7__add_outing.sql`에 이미 존재(#29 시점에 전체 흐름을
고려해 미리 만들어둠). 마이그레이션 불필요.

## 영향 받는 기존 코드
- `OutingResponse`에 `rejectedReason` 필드 추가(기존 필드는 그대로 유지 — 순수 확장, 하위 호환
  깨지지 않음). 신청/승인 응답에서는 항상 `null`.
- `OutingController`: `PATCH /{code}/reject` 핸들러 추가, `@PreAuthorize("hasRole('TEACHER')")`
- `OutingService`: `rejectOuting(...)` 메서드 추가. 소유권/상태 확인 로직은
  `approveOuting`과 동일한 형태라 별도 `private` 헬퍼로 뽑을지, 각자 인라인 유지할지는 구현
  시 판단(현재 `approveOuting`도 인라인이라 컨벤션 일관성상 인라인 유지 예정).
- 신규 DTO: `OutingRejectRequest(String rejectedReason)`, `@NotBlank @Size(max = 200)`

## API 설계 6원칙 체크
1. **한 가지를 잘하기**: 엔드포인트 1개, 상태 전이 1건만 책임 — 승인과 대칭이라 원칙에 부합.
2. **빠른 시작**: 요청/응답 예시 위 명시.
3. **일관성**: 경로/권한/에러코드 모두 승인(#30)과 동일 패턴 재사용.
4. **의미 있는 오류**: 기존 `OUTING_004`~`006` 그대로 재사용(원인이 승인과 동일하므로 코드
   분리 불필요 — 원칙 4번의 "원인이 다르면 분리"에 해당 안 됨, 승인/거절 모두 "PENDING이
   아닌 건 처리 불가"라는 같은 원인).
5. **확장성/성능**: 단건 처리라 페이지네이션 등 해당 없음.
6. **하위 호환성**: `OutingResponse`에 필드 추가만 함 — 기존 신청/승인 응답 구조에 영향 없음.

## 테스트 방법
로컬 서버(`baseUrl=http://localhost:9091`) 기동 후 아래 두 가지 방법으로 검증한다.

### 1. Postman 컬렉션 (권장)
워크스페이스에 동기화된 `GONE - Outing API` 컬렉션 + `GONE - Local Dev` 환경을 사용한다(환경
선택기에서 `GONE - Local Dev`를 먼저 선택). 사전 조건: DB에 테스트 계정
`user1`(STUDENT)/`teacher1`(TEACHER), 비밀번호 둘 다 `1234`가 있어야 한다(없으면 아래 SQL로
생성).

실행 순서(컬렉션에 번호로 정리돼 있음):
1. 학생 로그인 → `studentAccessToken` 자동 저장
2. 선생님 로그인 → `teacherAccessToken` + JWT에서 디코드한 `teacherUserId` 자동 저장
3. 외출증 신청(승인용) → `outingCode` 자동 저장
4. 외출증 승인 → `status: APPROVED` 확인
5. 외출증 신청(거절용, 3번과 다른 시간대) → `outingCodeForReject` 자동 저장
6. **외출증 거절** → `status: REJECTED`, 응답에 `rejectedReason`이 요청한 값 그대로 들어있는지
   확인(이 기획서의 핵심 검증 포인트)
7. "에러 케이스" 폴더(4번 실행 후 아무 때나 실행 가능):
   - 학생 토큰으로 승인 시도 → `403`
   - 이미 처리된 code 재승인 시도 → `409` `OUTING_005`
   - 존재하지 않는 code로 거절 시도 → `404` `OUTING_006`
   - `rejectedReason` 빈 값 → `400`
   - Authorization 헤더 없이 승인 시도 → `401`

각 요청에 Postman test 스크립트로 상태코드/응답 필드 assertion이 붙어있어, Run 시 자동으로
초록/빨강 표시된다.

### 2. 테스트 계정 생성 (DB에 없을 경우)
`mysql` CLI로 아래 SQL 실행(비밀번호 `1234`의 BCrypt 해시는 이 프로젝트가 쓰는
`BCryptPasswordEncoder`로 직접 생성/검증됨):
```sql
INSERT INTO gbsw (type, name, phone_number, grade, class_no, number)
VALUES ('STUDENT', '테스트학생', '01011119999', 3, 1, 1);
INSERT INTO user (gbsw_id, login_id, password_hash, name, phone_number)
VALUES ((SELECT id FROM gbsw WHERE phone_number = '01011119999'), 'user1',
  '$2a$10$8wkrdTE7xQLxEHtvJcxUFe/PmW1gIxzC1AvvSFuu8FG7eU99uwp7G', '테스트학생닉네임', '01011119999');
INSERT INTO user_role (user_id, role_id)
VALUES ((SELECT id FROM user WHERE login_id = 'user1'), (SELECT id FROM role WHERE code = 'STUDENT'));

INSERT INTO gbsw (type, name, phone_number, grade, class_no, number)
VALUES ('TEACHER', '테스트선생님', '01022229999', NULL, NULL, NULL);
INSERT INTO user (gbsw_id, login_id, password_hash, name, phone_number)
VALUES ((SELECT id FROM gbsw WHERE phone_number = '01022229999'), 'teacher1',
  '$2a$10$8wkrdTE7xQLxEHtvJcxUFe/PmW1gIxzC1AvvSFuu8FG7eU99uwp7G', '테스트선생님닉네임', '01022229999');
INSERT INTO user_role (user_id, role_id)
VALUES ((SELECT id FROM user WHERE login_id = 'teacher1'), (SELECT id FROM role WHERE code = 'TEACHER'));
```

### 3. 자동화 테스트 (참고용, 이미 통과 확인됨)
`./gradlew build` — `OutingServiceTest.RejectOuting`(성공/404/403/409 4케이스),
`OutingControllerTest.RejectOuting`(위임 확인 + Bean Validation 400 2케이스),
`OutingRejectAuthorizationTest`(401/403, `@EnableMethodSecurity` 실제 동작 확인) 전부 포함.

## 리스크 및 고려사항
- **동시성 (마스터 기획서 "공통 구현 고려사항"에서 재판단 요구한 부분)**: 승인과 마찬가지로
  `status == PENDING` 체크 후 저장까지 락이 없어 이론상 같은 선생님의 이중 클릭이 둘 다 체크를
  통과하는 레이스가 있다. 다만 거절은 승인과 달리 매 요청마다 `rejectedReason` 값이 다를 수
  있어 "마지막에 쓴 요청의 사유가 남는" 정도의 영향이 있다. 그래도 ①이미 소유권 확인이 있어
  공격 벡터가 아니고 ②최종 상태는 항상 일관되게 `REJECTED`이며 ③사유 텍스트 하나가 어느 쪽
  이중 클릭 값으로 남는지 정도의 낮은 영향이라, **#30과 동일하게 이번에도 락을 추가하지
  않기로 제안**한다. 리뷰 시 동의 여부 확인 필요.
- 승인/거절 모두 `PENDING`에서만 가능하므로 한 외출증에 대해 승인 후 거절(또는 그 반대)
  시도는 이미 `OUTING_005`로 막힌다 — 별도 처리 불필요.
