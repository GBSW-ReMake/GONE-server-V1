# #81 스쿨캠핑 신청/수정 시 월 중복 참여자 특정해서 응답하기 — 기획서

관련 이슈: [#81 스쿨캠핑 신청/수정 시 월 중복 참여자 특정해서 응답하기](https://github.com/GBSW-ReMake/GONE-server-V1/issues/81)
마스터 기획서: 별도 절 없음 — 마스터 기획서(`1_schoolcamp-domain.md`)의 신청(2번)/수정
엔드포인트가 이미 확정한 "이번 달 참여 1회 제한" 정책 자체는 바뀌지 않는다. 이 이슈는
그 제한에 걸렸을 때의 **에러 응답 데이터**만 보강한다.
선행 이슈: [#68](./68-schoolcamp-application.md)(신청, 완료·머지됨),
[#70](./70-schoolcamp-cancel-update.md)(수정, 완료·머지됨) — 이 이슈가 손보는
`validateNoDuplicateThisMonth`가 이 두 이슈에서 이미 만들어져 있다.

## 개요/목적
새 엔드포인트를 추가하지 않는다. 기존 `POST /api/v1/school-camps/{sessionId}/applications`
(#68)와 `PATCH /api/v1/school-camps/applications/{id}`(#70)가 이번 달 중복 참여로
`409 SCHOOLCAMP_003`을 반환할 때, **어떤 학생 때문에 막혔는지**를 응답의 `data`에 실어
보낸다. 차단 자체(요청 전체 거부)는 이미 정확히 동작하고 있어 바꾸지 않는다 — 그 결정을
사용자가 읽고 바로 알 수 있게 만드는 것이 이 이슈의 전부다.

**계약 변경 성격**: 기존에는 `data: null`이었던 자리가 `data: {...}`로 채워진다.
[api-design.md](../../rules/api-design.md) 원칙 6("필드를 추가하는 확장은 항상
안전하다")에 해당하는 안전한 확장이다 — 기존 클라이언트가 `data`를 무시하고 있었다면
영향이 없고, `success`/`code`/`message`/HTTP 상태 코드는 전혀 바뀌지 않는다.

## 엔드포인트
엔드포인트 자체(경로, 메서드, 요청 스키마, 성공 응답)는 변경하지 않는다. 아래는 두
엔드포인트가 공통으로 던지는 `409 SCHOOLCAMP_003` 에러 응답의 변경 전/후만 다룬다.

**변경 전**
```json
{
  "success": false,
  "data": null,
  "message": "이번 달에 이미 참여한 사용자가 포함되어 있습니다.",
  "code": "SCHOOLCAMP_003"
}
```

**변경 후**
```json
{
  "success": false,
  "data": {
    "conflictingMembers": [
      { "studentUserId": 55, "studentRealName": "이영희", "studentGrade": 3, "studentClassNo": 2 }
    ]
  },
  "message": "이번 달에 이미 참여한 사용자가 포함되어 있습니다.",
  "code": "SCHOOLCAMP_003"
}
```
`conflictingMembers`는 이번 요청의 후보(신청은 대표+팀원, 수정은 새로 추가되는 팀원만)
중 실제로 이번 달에 이미 참여 중이었던 사람만 담는다 — 후보 전체가 아니라 걸린 사람만.
2명 이상이 동시에 걸리면 배열에 전부 담는다(한 명만 담고 나머지는 재신청 때 또 걸리게
하지 않는다).

## 구현 로직
`SchoolCampService.validateNoDuplicateThisMonth`(현재 `Set<Long> candidateIds,
LocalDate campDate`를 받아 `memberRepository.findParticipatedStudentIdsInMonth`로 걸린
학생 ID 목록(`participated`)을 조회한 뒤 **그 목록을 버리고** 존재 여부만 확인해 예외를
던진다)를 아래처럼 고친다.

1. 시그니처에 `Map<Long, User> usersById` 파라미터를 추가한다(신규) —
   `findParticipatedStudentIdsInMonth`가 돌려주는 ID들의 이름/학년/반을 조회하려고
   또 DB를 왕복하지 않고, **호출부가 이미 들고 있는 `User` 객체를 재사용**한다.
2. `participated`(걸린 학생 ID 목록)가 비어있지 않으면, 각 ID를 `usersById`에서 찾아
   `SchoolCampConflictingMemberResponse`로 변환한 뒤 `CustomException(ErrorCode, data)`
   생성자(이 프로젝트에 이미 있는 패턴 — `PhoneAuthService.sendVerificationCode`/
   `verifyCode`가 남은 쿨다운 초/실패 횟수를 같은 방식으로 응답에 실어 보낸다)로 던진다.
3. 두 호출부를 각각 고친다:
   - `completeApplication`(#68, 신규 신청): 현재 `candidateIds`를
     `studentsById.keySet() + applicantUserId`로 만드는데, `usersById`도 같은 모양으로
     만든다 — `studentsById`(이미 있음)에 대표 신청자 `applicant`(이미 조회돼 있는 변수)
     하나만 추가하면 된다.
   - `updateApplication`(#70, 수정): `candidateIds`가 `diff.addedStudentIds()`(새로
     추가되는 팀원만)이고, `studentsById`가 이미 그 상위집합을 커버하므로 **그대로
     `studentsById`를 넘기면 된다**(추가 조회 없음).

## 데이터 모델 변경
신규 엔티티/마이그레이션 없음.

### 신규 DTO
```java
public record SchoolCampParticipationConflictResponse(
    List<SchoolCampConflictingMemberResponse> conflictingMembers
) {}

public record SchoolCampConflictingMemberResponse(
    Long studentUserId,
    String studentRealName,
    Integer studentGrade,
    Integer studentClassNo
) {}
```
필드 구성은 기존 `SchoolCampMemberResponse`(팀원 응답)와 통일한다 — `guestName`/
`isApplicant`는 이 응답에 의미가 없어 뺀다("기타" 자유 입력 팀원은 계정이 없어 애초에
이번 달 중복 체크 대상이 아니고, 대표 여부는 이 에러 상황에서 프론트가 굳이 구분할 필요가
없다). 바로 `List<SchoolCampConflictingMemberResponse>`를 `data`에 넣지 않고
`SchoolCampParticipationConflictResponse`로 한 겹 감싸는 이유는 원칙 6 때문이다 — 배열을
바로 최상위 `data`로 노출하면 나중에 형제 필드(예: "이번 달 참여 가능 인원 안내" 같은
부가 정보)를 추가하고 싶을 때 깨지는 변경이 되지만, 객체로 감싸두면 필드 추가로 안전하게
확장할 수 있다.

### `SchoolCampErrorCode.ALREADY_PARTICIPATED_THIS_MONTH` Javadoc 갱신
코드/메시지(`SCHOOLCAMP_003`, "이번 달에 이미 참여한 사용자가 포함되어 있습니다.")는
그대로 두고, 이제 `data`에 `SchoolCampParticipationConflictResponse`가 실려 온다는 점만
Javadoc에 추가한다. 새 에러 코드는 만들지 않는다(원인은 그대로 "이번 달 중복 참여"이고,
그 부가 정보만 풍부해지는 것이라 코드를 분리할 이유가 없다 — 원칙 4의 "원인이 다르면
코드를 분리한다"에 해당하지 않는다).

## 영향 받는 기존 코드
- 신규: `SchoolCampParticipationConflictResponse`/`SchoolCampConflictingMemberResponse`
  (DTO)
- 수정: `SchoolCampService`(`validateNoDuplicateThisMonth` 시그니처에 `Map<Long, User>`
  추가 + 예외에 `data` 실어 던지기, `completeApplication`/`updateApplication` 두 호출부
  수정), `SchoolCampErrorCode`(`ALREADY_PARTICIPATED_THIS_MONTH` Javadoc만 갱신, 코드/
  메시지 불변)
- 신규 마이그레이션 없음, 신규 에러 코드 없음, 컨트롤러/엔드포인트 시그니처 변경 없음

## 리스크 및 고려사항
- **API 설계 6원칙 체크**: 이 이슈는 신규 엔드포인트가 아니라 기존 에러 응답의 `data`
  필드 보강이라 1~5번 원칙은 직접 해당하지 않는다. 6번(하위 호환성)만 핵심 — 위 "계약
  변경 성격" 절 참고, 필드 추가만 있고 제거/의미 변경은 없다.
- **추가 DB 쿼리 없음**: `usersById`를 호출부가 이미 조회해둔 `Map<Long, User>`(`#68`의
  `findExistingStudents` 결과 + 대표 신청자)에서 그대로 재사용하므로, 이번 이슈로 새로운
  쿼리가 늘지 않는다(성능 영향 없음).
- **"기타"(자유 입력) 팀원은 애초에 이 응답에 나타날 수 없음**(의도된 동작) —
  `candidateIds`/`usersById` 자체가 가입된 학생만 대상으로 구성되므로, 자유 입력 팀원이
  월중복으로 걸리는 경우 자체가 없다(그런 사람은 계정이 없어 "이번 달 참여" 개념이
  성립하지 않음).
- **Notion API 명세서**: `#68`(`SCHOOLCAMP_003` in Notion, POST 신청)/`#70`
  (`SCHOOLCAMP_005`, PATCH 수정) 두 페이지의 "예외/오류 처리"/필요하면 "Response" 절에
  이번 변경(`409` 응답의 `data` 채워짐)을 반영해야 한다(17단계, 머지 후).

## 테스트
- `SchoolCampService.applyToCamp`(#68 회귀 + 신규 검증):
  - 대표 신청자 본인이 이번 달 중복이면 `data.conflictingMembers`에 대표 신청자 정보가
    담기는지(`studentUserId`/`studentRealName`/`studentGrade`/`studentClassNo` 정확히)
  - 팀원 1명이 이번 달 중복이면 `data.conflictingMembers`에 그 팀원만 담기는지(대표 신청자
    본인은 포함 안 됨)
  - **대표 신청자 + 팀원 여러 명이 동시에 걸리면 전부 담기는지**(이번 이슈 핵심 케이스)
- `SchoolCampService.updateApplication`(#70 회귀 + 신규 검증):
  - 새로 추가하는 팀원이 이번 달 중복이면 `data.conflictingMembers`에 그 팀원 정보가
    담기는지
  - 기존에 이미 있던(유지되는) 팀원은 재검사 대상이 아니므로 걸릴 수 없음(기존 로직
    회귀 확인용, 새 테스트 아님)
- 기존 `throwsWhenAlreadyParticipatedThisMonth`류 테스트(`ApplyToCamp`/
  `UpdateApplication` 양쪽)는 `ErrorCode`만 검증하던 것에 `getData()` 검증을 추가하는
  형태로 확장한다(테스트 신규 추가가 아니라 기존 테스트 보강).
