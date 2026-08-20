# #81 월 중복 참여자 응답 QA 결과

관련 기획서: [81-schoolcamp-monthly-conflict-response.md](./81-schoolcamp-monthly-conflict-response.md)
관련 코드 리뷰: [81-schoolcamp-monthly-conflict-response-code-review.md](./81-schoolcamp-monthly-conflict-response-code-review.md)
(코드 리뷰 결과: Critical/High/Medium/Low 없음)

## 검증 방법
- `./gradlew build`, `./gradlew test`, `./gradlew checkstyleMain` 로컬 통과 확인
  (`build`가 `test`/`checkstyleMain`을 포함해 실행됨, `BUILD SUCCESSFUL`).
- 로컬 dev 프로필로 실제 서버 기동(`./gradlew bootRun`, MySQL 로컬 인스턴스 + Redis 사용)
  후 `postman/collections/gone-schoolcamp.postman_collection.json` 전체를 newman으로 실행해
  실제 HTTP 응답 바디를 확인. 기존 컬렉션의 "18. 신청 - 이미 이번 달 참여한 팀원 포함 →
  409 SCHOOLCAMP_003"(`#68` 시나리오, `applyToCamp`/`completeApplication` 경로)과 "19. 수정 -
  이미 이번 달 참여한 팀원2 추가 시도 → 409 SCHOOLCAMP_003"(`#70` 시나리오,
  `updateApplication` 경로)가 이번 이슈가 손댄 두 호출부를 각각 정확히 커버한다.
- 기존 컬렉션의 test 스크립트는 `code` 필드만 검증하므로, newman JSON 리포터로 두 요청의
  raw response body를 직접 추출해 `data.conflictingMembers`의 실제 내용까지 확인했다
  (아래 "실제 응답" 참고).

## 실제 응답

**#68 시나리오 18번 (신청, `applyToCamp`)** — 대표 신청자(user1)와 팀원(testuser01) 둘 다
이번 달 이미 참여 상태였던 케이스:
```json
{
  "success": false,
  "data": {
    "conflictingMembers": [
      { "studentUserId": 1, "studentRealName": "정문경", "studentGrade": 3, "studentClassNo": 2 },
      { "studentUserId": 12, "studentRealName": "테스트학생", "studentGrade": 3, "studentClassNo": 1 }
    ]
  },
  "message": "이번 달에 이미 참여한 사용자가 포함되어 있습니다.",
  "code": "SCHOOLCAMP_003"
}
```
대표 신청자 본인과 팀원이 모두 `conflictingMembers`에 실명/학년/반과 함께 담김 —
기획서 및 신규 단위 테스트(`includesAllConflictingMembersWhenMultipleParticipatedThisMonth`)와
일치.

**#70 시나리오 19번 (수정, `updateApplication`)** — 팀원 추가 시 그 팀원(testuser02)이
이번 달 이미 참여 상태였던 케이스:
```json
{
  "success": false,
  "data": {
    "conflictingMembers": [
      { "studentUserId": 2, "studentRealName": "김은찬", "studentGrade": 3, "studentClassNo": 2 }
    ]
  },
  "message": "이번 달에 이미 참여한 사용자가 포함되어 있습니다.",
  "code": "SCHOOLCAMP_003"
}
```

## 결과
**Critical/High/Medium/Low 없음** — 두 호출부 모두 기획서대로 `data`에 중복 참여자의
실명/학년/반이 정확히 채워져 응답됨을 실서버로 확인했다.

## 참고: 컬렉션 전체 실행 중 관찰된 그 외 실패 (모두 코드 문제 아님)
같은 컬렉션 전체 실행 중 다음 두 종류의 실패가 있었으나, 둘 다 실제 코드 결함이 아니라
QA 실행 방식에서 비롯된 것으로 확인했다.

- **`#69 스쿨캠핑 참여 내역 확인` 폴더 15~17번** (담당 선생님 지정 신청 →
  `myRole=TEACHER` 확인): 처음엔 `#69` 코드의 회귀로 의심했으나, JWT를 디코딩해 재확인한
  결과 오진이었다. 이 시나리오는 컬렉션 설명에 "실행 전 DB에서 testuser01 계정에 TEACHER
  역할을 임시로 부여해야 한다"고 명시돼 있는데, 이번 실행에서 그 수동 준비를 생략하고
  컬렉션 전체를 한 번에 돌렸다. 실제로 testuser01(id=1)은 이 시점에 `STUDENT` 역할만
  가지고 있었고, 그 상태로 `teacherUserId=1`을 지정해 신청했으니 `SCHOOLCAMP_004`(담당
  선생님이 TEACHER 역할 아님)가 발생한 것은 의도된 정상 동작이다. 이후 16/17번의
  `myRole=MEMBER`도 15번 신청 실패로 인한 정상적인 연쇄 결과다. `#69` 코드에는 문제가
  없으므로 백로그 이슈를 만들지 않는다.
- `#67` 폴더의 날짜 재등록 요청과 상단 예시 "스쿨캠핑 신청 수정" 요청 실패는, 컬렉션이
  하드코딩된 고정 날짜(`campDate1` 등)를 사용해 이전 실행의 잔존 데이터와 충돌한 것으로,
  로컬 DB에 QA 컬렉션을 반복 실행한 부작용이다(멱등성 없는 테스트 데이터 재사용). 별도
  조치가 필요하지 않다.
