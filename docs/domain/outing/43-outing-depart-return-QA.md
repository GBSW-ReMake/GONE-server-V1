# #43 외출증 출발/도착 보고 API — QA 결과

관련 기획서: [43-outing-depart-return.md](./43-outing-depart-return.md)
코드 리뷰 결과(9단계): [43-outing-depart-return-code-review.md](./43-outing-depart-return-code-review.md)

## 1. QA/QC (10단계)

### 자동 검증
- `./gradlew checkstyleMain checkstyleTest`: **통과** (경고 없음)
- `./gradlew test`: **통과** (전체 105개 테스트 클래스, 실패/에러 0건 — 코드 리뷰 반영분
  포함 재실행)
- GitHub Actions CI: 이 저장소의 CI는 `pull_request`/`push`가 `main`/`dev`/`staging`
  대상일 때만 트리거되고 기능 브랜치 push로는 돌지 않는다(`.github/workflows/ci.yml`).
  이번 브랜치는 아직 PR 이전(로컬 전용)이라 CI 실행 이력이 없다 — **16단계(PR 생성) 직후
  실제 CI 결과를 다시 확인한다.**

### 수동 검증 (실서버, `localhost:9090`, dev 프로필, 로컬 MySQL/Redis 기동)
`user1`(STUDENT, id=12)/`teacher1`(TEACHER, id=13)/`testuser01`(STUDENT 역할 보유, 관계없는
사용자 대역, id=1) 계정으로 실제 요청을 보내 확인했다. `outing.school-latitude`/
`school-longitude`가 로컬 `application-dev.yml`에 더미값 `0.0`/`0.0`(반경 200m)으로 설정돼
있어, 좌표 `(0.0, 0.0)`을 "학교 반경 안"으로 활용했다.

외출증 4건(A~D)을 `user1`으로 신청 후 `teacher1`이 A/B/C만 승인(D는 의도적으로 `PENDING`
유지)했다.

| # | 케이스 | 기대 | 결과 |
|---|---|---|---|
| 1 | `POST /{code}/depart` 정상 출발(A, 반경 안 `0,0`) | 200, `status: DEPARTED` | ✅ (부가로 `offSchedule: true` 확인 — 자기 시간대 15:00 이전에 출발했으므로) |
| 2 | 존재하지 않는 `code`로 출발 | 404 `OUTING_006` | ✅ |
| 3 | 본인 소유가 아닌 외출증에 출발 시도(B, `testuser01`로) | 403 `OUTING_007` | ✅ |
| 4 | 학교 반경 밖에서 출발 시도(C) | 400 `OUTING_009` | ✅ |
| 5 | 아직 `PENDING`(승인 전)인 D에 출발 시도 | 409 `OUTING_005` | ✅ |
| 6 | 이미 `DEPARTED`인 A에 재출발 시도 | 409 `OUTING_005` | ✅ |
| 7 | 좌표 범위 밖(`latitude: 999.0`)으로 출발 시도(코드 리뷰 Low 3번 대응 확인) | 400 `COMMON_001` | ✅ |
| 8 | 본인 소유가 아닌 외출증에 도착 시도(A, `testuser01`로) | 403 `OUTING_007` | ✅ |
| 9 | 정상 도착(A) | 200, `status: RETURNED` | ✅ (`offSchedule: true` — 자기 시간대 15:00~16:00 밖인 13시경에 도착) |
| 10 | 이미 `RETURNED`인 A에 재도착 시도 | 409 `OUTING_005` | ✅ |
| 11 | 존재하지 않는 `code`로 도착 | 404 `OUTING_006` | ✅ |
| 12 | 아직 `DEPARTED` 아님(B, `APPROVED` 상태)에 도착 시도 | 409 `OUTING_005` | ✅ |
| 13 | 학교 반경 밖에서 도착 시도(C, 출발은 반경 안에서 정상 처리 후) | 400 `OUTING_009` | ✅ |
| 14 | 좌표 범위 밖(`longitude: 200.0`)으로 도착 시도 | 400 `COMMON_001` | ✅ |
| 15 | 인증 없이 출발 시도 | 401 `COMMON_002` | ✅ |
| 16 | `TEACHER` 역할로 출발 시도(`@PreAuthorize` 실동작 확인) | 403 `COMMON_003` | ✅ |
| 17 | 선생님이 `GET /me/received?status=DEPARTED`로 진행 중인 외출 조회(이슈 본문 요구사항) | C만 반환 | ✅ |
| 18 | 선생님이 `GET /me/received?status=RETURNED`로 완료된 외출 조회(이슈 본문 요구사항) | A만 반환, `departedAt`/`returnedAt` 모두 채워짐 | ✅ |

**코드 리뷰 반영 3건 모두 실서버로 재확인됨**:
- Medium 1번(IDOR): 3번/8번 케이스로 실서버 재현 완료(자동화된
  `OutingDepartReturnOwnershipIntegrationTest`와 별개로, 실제 로그인 세션으로도 직접 확인).
- Medium 2번(낙관적 락 충돌 → 409 변환): 별도 동시 요청 재현은 아래 "환경 제약" 항목 참고
  (단위 테스트로 대체).
- Low 3번(좌표 범위 검증): 7번/14번 케이스로 실서버 재현 완료.

**Medium — 환경 제약으로 직접 재현 못 한 항목**:
1. **학교 운영시간(08:40~20:30) 게이트(`OUTING_010`)**: QA 수행 시각이 13:05~13:51(KST)로
   운영시간 안이라, 실제 시각을 운영시간 밖으로 만들어 재현할 방법이 없었다(서버가
   `LocalDateTime.now(KST)`를 직접 써서 시각을 주입할 수 없음 — 기획서/서비스 설계상 의도된
   것). `OutingServiceTest.DepartOuting`/`ReturnOuting`의 경계값 테스트(08:39/08:40/20:30/
   20:31)로 대체 확인했다(#41/#42와 동일한 판단).
2. **낙관적 락 충돌 → 409 변환**(코드 리뷰 Medium 2번): 더블 탭/재시도 경합은 밀리초 단위로
   두 요청을 정확히 겹치게 만들어야 재현되는데, curl 기반 수동 QA로는 그 타이밍을 통제할 수
   없었다. `OutingServiceTest`의
   `convertsOptimisticLockFailureToAlreadyProcessed`(출발/도착 각각)로 대체 확인했다
   (`OutingRepository.save`가 `ObjectOptimisticLockingFailureException`을 던지도록 목킹해
   409 `ALREADY_PROCESSED` 변환을 검증) — #42 QA의 동일 판단(환경 제약 시 단위 테스트로
   대체)을 따른다.

## 2. 결론

Critical/High 없음. 계획된 18개 케이스(정상 출발/도착, 4xx/409 에러 전 종류, 권한/역할
검증, 이슈 본문의 진행중·완료 조회 요구사항) 모두 실서버에서 기대대로 동작함을 확인했다.
코드 리뷰에서 반영한 3건(IDOR, 낙관적 락 충돌, 좌표 범위 검증) 중 IDOR과 좌표 범위 검증은
실서버로 직접 재현했고, 낙관적 락 충돌과 운영시간 게이트는 실시간 재현이 불가능한 환경
제약으로 단위 테스트로 대체 확인했다(기존 #41/#42 선례와 동일한 판단). CI는 이 브랜치가
PR 이전 단계라 아직 실행 이력이 없어, PR 생성 직후 별도로 확인이 필요하다.

추가 조치 없이 다음 단계(문제사항 보고)로 진행 가능하다고 판단한다.
