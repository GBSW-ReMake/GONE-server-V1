# #97 외출증 위치/동선 조회 + 위치 핑 전송 API — QA 결과

관련 기획서: [97-outing-location.md](./97-outing-location.md)
코드 리뷰 결과: [97-outing-location-code-review.md](./97-outing-location-code-review.md)

## 검증 환경
- 로컬 MySQL(`gone` 스키마) + 로컬 Redis
- `CONDUCT_DEMERIT_THRESHOLD=10 ./gradlew bootRun --args='--spring.profiles.active=dev'`로
  실서버 기동(포트 9090). 이 환경변수가 로컬에 없어 컨텍스트 로딩이 실패하는 문제는 #98
  QA에서 이미 발견해 팀에 공유한 기존 이슈로, #97 범위 밖이라 이번에도 동일하게 임시로
  넣어서 우회했다.
- 인증은 `JwtProvider.createAccessToken`을 임시 테스트로 직접 호출해 역할별 토큰 발급,
  외출증도 같은 임시 테스트로 `DEPARTED`/`PENDING` 픽스처를 실 DB에 저장
- 검증에 사용한 임시 픽스처(학생 2명, 선생님 4명, 외출증 2건, 위치 핑 2건)는 검증 직후
  같은 방식으로 실 DB에서 삭제 완료, 임시 테스트 파일도 커밋하지 않고 삭제함

## 로컬/CI 결과
- `./gradlew build`(compileJava/Test, test, checkstyleMain/Test 포함) 통과
- 전체 테스트 480개 통과
- CI는 PR 생성 후 확인 예정(이 저장소 CI는 `pull_request`/`push` 대상이 `main`/`dev`/
  `staging`뿐이라 feature 브랜치 push 시점에는 실행되지 않음)

## 엔드포인트 실동작 검증

### `POST /api/v1/outings/{code}/locations` — 위치 핑 전송
| 케이스 | 요청 | 기대 | 실제 |
|---|---|---|---|
| 인증 없음(GET으로 대표 확인) | 토큰 없이 요청 | 401 `COMMON_002` | ✅ |
| 정상(본인, DEPARTED) | STUDENT 본인 토큰 | 200, `data: null` | ✅ |
| 본인 아닌 학생(IDOR) | 다른 STUDENT 토큰 | 403 `OUTING_007` | ✅ |
| DEPARTED 아닌 상태(PENDING) | 본인 토큰, PENDING 외출증 | 409 `OUTING_016` | ✅ |
| 존재하지 않는 code | 본인 토큰, 임의 code | 404 `OUTING_006` | ✅ |
| 좌표 범위 밖 | `latitude: 999.0` | 400 `COMMON_001` | ✅ |
| 연속 핑 저장(최소 간격 미검증) | 같은 외출증에 핑 2회 연속 전송 | 둘 다 200, 둘 다 저장 | ✅ |

### `GET /api/v1/outings/{code}/locations` — 위치/동선 조회
| 케이스 | 요청 | 기대 | 실제 |
|---|---|---|---|
| 인증 없음 | 토큰 없이 요청 | 401 `COMMON_002` | ✅ |
| 담당 선생님 본인 | 담당 TEACHER 토큰 | 200, `path` 3개(출발좌표+핑2개) | ✅ |
| 담당 아닌 TEACHER | 배정 안 된 TEACHER 토큰 | 403 `OUTING_007` | ✅ |
| DISCIPLINE(담당 아니어도) | DISCIPLINE 토큰 | 200, 담당 선생님과 동일 응답 | ✅ |
| ADMIN(담당 아니어도) | ADMIN 토큰 | 200, 담당 선생님과 동일 응답 | ✅ |
| 존재하지 않는 code | 담당 토큰, 임의 code | 404 `OUTING_006` | ✅ |
| `path` 정렬/조립 | 출발좌표(17:14:25) → 핑1(17:45:32) → 핑2(17:45:58) | `recordedAt` 오름차순, 출발좌표가 항상 첫 점 | ✅ |

## 발견된 문제
없음. 코드 리뷰(9단계)에서 지적된 High 1건/Medium 2건/Low 3건은 모두 코드 리뷰 단계에서
이미 반영 완료된 상태로 QA에 들어왔고, 이번 실서버 검증에서 별도로 발견된 문제는 없다.

## 남은 절차
- 15단계: Postman 컬렉션에 `POST/GET /api/v1/outings/{code}/locations` 반영
- 16단계: 보스 최종 확인 후 PR 생성
