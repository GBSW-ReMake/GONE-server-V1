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

## 참고: #81 범위 밖에서 발견한 무관한 회귀
같은 컬렉션 전체 실행 중 `#69 스쿨캠핑 참여 내역 확인` 폴더의 "15~17번"(담당 선생님
지정 신청 → 참여 내역/상세 조회 시 `myRole`이 `TEACHER`로 나와야 하는 시나리오)이 실패했다
(신청 자체가 400으로 실패, 이후 조회에서 `myRole`이 `TEACHER`가 아닌 `MEMBER`로 나옴).
이 플로우는 이미 `dev`에 머지된 `#69`(`07fb4ed`) 코드 경로이고, 이번 `#81` 브랜치의 변경과
무관하다 — `validateNoDuplicateThisMonth`/`SchoolCampParticipationConflictResponse`와
관련 없는 별개 기능이다. `progress-tracking.md`의 "머지 후 발견된 회귀는 새 이슈로 분리"
규칙에 따라, 이 브랜치에서 고치지 않고 별도로 보고한다 (백로그 이슈 생성 여부는 보스 확인
후 결정).

아울러 같은 실행에서 `#67` 폴더의 날짜 재등록 요청과 상단 예시 "스쿨캠핑 신청 수정"
요청도 실패했는데, 이는 컬렉션이 하드코딩된 고정 날짜(`campDate1` 등)를 사용해 이전 실행의
잔존 데이터와 충돌한 것으로, 코드 버그가 아니라 로컬 DB에 QA 컬렉션을 반복 실행한
부작용이다(멱등성 없는 테스트 데이터 재사용). 별도 조치가 필요하지 않다.
