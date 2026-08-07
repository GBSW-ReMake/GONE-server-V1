# #31 외출증 거절 API — 코드 리뷰 & QA 결과

관련 기획서: [31-outing-reject.md](./31-outing-reject.md)

## 1. 코드 리뷰 (9단계, 독립 에이전트)

컨텍스트 격리를 위해 구현 대화 기록 없이 별도 에이전트를 띄워, `merge-base`(177f60d) ~
`feat/#31-outing-reject` 전체 diff와 기획서만 전달해 `code-review` 스킬로 리뷰했다.

**결과: 발견된 문제 없음 (Critical/High/Medium/Low 전부 0건)**

확인한 항목:
- 기획서 계약 준수: 엔드포인트 경로/메서드, 권한(`TEACHER`), 요청/응답 스키마, 에러코드
  (`OUTING_004`/`005`/`006`, Bean Validation 400) 전부 기획서와 일치. 스코프 벗어난 변경 없음.
- `#30`(승인)의 `approveOuting`과 구조적으로 일관됨: 조회→소유권 확인→상태 확인→변경 순서와
  에러코드 사용 패턴이 동일.
- `OutingResponse`에 추가된 `rejectedReason` 필드가 기존 호출부(신청/승인 응답 생성 지점)에
  누락 없이 반영됨.
- 테스트 커버리지: 성공/404/403/409(서비스), Bean Validation 400 2케이스(컨트롤러),
  401/403(`@EnableMethodSecurity` 실동작, `OutingRejectAuthorizationTest`) — #30과 동일한
  형태로 갖춰짐.
- 기획서에 이미 명시된 동시성 트레이드오프(락 미적용)는 재검토 결과 새로운 이슈가 아니라
  문서화된 대로의 의도된 결정으로 확인.

## 2. QA/QC (10단계)

### 자동 검증
- `./gradlew build` (test + checkstyleMain 포함): **통과**

### 수동 검증 (실서버, `localhost:9091`, dev 프로필)
`user1`(STUDENT)/`teacher1`(TEACHER) 계정으로 실제 로그인 → 외출증 신청 → 거절까지 end-to-end로
확인했다.

| # | 케이스 | 기대 | 결과 |
|---|---|---|---|
| 1 | 정상 거절 (PENDING → REJECTED, `rejectedReason` 저장) | 200 | ✅ |
| 2 | 이미 처리된 code 재거절 | 409 `OUTING_005` | ✅ |
| 3 | 존재하지 않는 code | 404 `OUTING_006` | ✅ |
| 4 | Authorization 헤더 없음 | 401 | ✅ |
| 5 | 학생 토큰(TEACHER 아님)으로 시도 | 403 | ✅ |
| 6 | `rejectedReason` 빈 값 | 400 | ✅ |

**Medium — 환경 제약으로 직접 검증 못 한 항목**: "본인이 지정된 선생님이 아님 →
`OUTING_004`" 케이스는 두 번째 teacher 테스트 계정이 로컬 DB에 없어 실서버로 직접 재현하지
못했다. 다만 `OutingServiceTest.RejectOuting`의 403 케이스와 `OutingRejectAuthorizationTest`가
이 경로를 자동화 테스트로 커버하고 있고 `./gradlew build`에서 통과했으므로, 자동화 테스트
결과로 대체 확인했다.

## 3. 기타 발견 사항

**Low — 테스트 방법 관련 (서버 버그 아님)**: 이 저장소의 Windows/Git Bash 환경에서 한글이
포함된 JSON 바디를 `curl -d '...'`로 직접 전달하면 인코딩이 깨져 `COMMON_007`(500) 오류가
발생한다. UTF-8로 저장한 파일을 `--data-binary @file`로 보내면 정상 동작한다. 서버/구현
문제가 아니라 로컬 테스트 방법의 함정이라 별도 조치 불필요.

## 결론

Critical/High 없음. Medium 1건은 테스트 계정 제약으로 인한 자동화 테스트 대체 확인이며 추가
조치가 필요한 결함은 아니다. PR 진행 가능.
