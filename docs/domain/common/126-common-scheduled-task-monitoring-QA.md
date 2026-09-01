# #126 scheduled_task 모니터링/재시도 API — QA 결과

관련 기획서: [126-common-scheduled-task-monitoring.md](./126-common-scheduled-task-monitoring.md)
코드 리뷰 결과: [126-common-scheduled-task-monitoring-code-review.md](./126-common-scheduled-task-monitoring-code-review.md)

## 검증 환경
- 로컬 MySQL(Windows `MySQL80` 서비스, `gone` 스키마) + WSL Ubuntu `redis-server`
- `./gradlew bootRun`으로 실서버 기동(포트 9090)
- 인증은 임시 테스트(`ScratchTokenPrinterTest`, 커밋하지 않고 검증 직후 삭제)로
  `JwtProvider.createAccessToken`을 직접 호출해 ADMIN/STUDENT 역할별 토큰 발급
- 검증에 사용한 임시 픽스처(`scheduled_task` 행, `task_type` 접두사 `QA_`)는 검증 직후
  같은 방식으로 실 DB에서 삭제 완료

## 로컬 빌드/테스트 결과
- `./gradlew build` 통과, 전체 테스트 통과(#126이 추가한 43개 포함)
- CI는 PR 생성 후 확인 예정

## 엔드포인트 실동작 검증

### `GET /api/v1/scheduled-tasks`
| 케이스 | 요청 | 기대 | 실제 |
|---|---|---|---|
| ADMIN, 필터 없음 | ADMIN 토큰 | 200 | ✅ |
| `status`/`taskType` 필터 | `status=PENDING&taskType=QA_PENDING_TEST` | 200, 해당 행만 | ✅ |
| 인증 없음 | 토큰 없음 | 401 `COMMON_002` | ✅ |
| ADMIN 아님 | STUDENT 토큰 | 403 `COMMON_003` | ✅ |
| `page` 음수 | `page=-1` | 400 `SCHEDULE_001` | ✅ |

### `GET /api/v1/scheduled-tasks/stats`
| 케이스 | 요청 | 기대 | 실제 |
|---|---|---|---|
| ADMIN | ADMIN 토큰 | 200, `{pending,done,failed,total}` | ✅ |

### `POST /api/v1/scheduled-tasks/{id}/retry`
| 케이스 | 요청 | 기대 | 실제 |
|---|---|---|---|
| FAILED 작업 재시도 | FAILED 픽스처 | 200, `status: PENDING`, `failureCount: 0` | ✅ |
| 존재하지 않는 id | 임의 id | 404 `SCHEDULE_002` | ✅ |
| FAILED 아닌 작업 재시도 | PENDING 픽스처 | 409 `SCHEDULE_003` | ✅ |

### `DELETE /api/v1/scheduled-tasks/{id}`
| 케이스 | 요청 | 기대 | 실제 |
|---|---|---|---|
| 존재하는 작업 삭제 | 픽스처 id | 200, DB에서 실제로 사라짐 | ✅ |
| 존재하지 않는 id | 임의 id | 404 `SCHEDULE_002` | ⚠️ → ✅(아래 "발견된 문제" 참고) |

## 발견된 문제

- **High**: `DELETE /api/v1/scheduled-tasks/{id}`에 존재하지 않는 id를 넣었더니 404가
  아니라 **200(성공)**이 반환됐다. 원인: 코드 리뷰(9단계) High #1에서 반영한
  "`deleteById()` 호출 후 `EmptyResultDataAccessException`을 잡아 404로 변환" 방식이, 이
  프로젝트가 쓰는 Spring Data JPA 버전에서는 애초에 성립하지 않았다 — `deleteById()`가
  대상이 없어도 예외를 던지지 않고 조용히 성공(no-op)한다. 즉 코드 리뷰가 근거로 삼은
  "Spring Data 표준 동작"이 실제 이 버전에는 해당하지 않았고, 그 결과 코드 리뷰에서 고쳤다고
  판단한 catch 블록이 한 번도 발동하지 않는 죽은 코드가 되어 원래 있던 버그(404가 안 나옴)가
  그대로 남아 있었다. **실서버 curl 검증으로 실제 200 응답을 직접 확인**했다.
  **반영 완료**: `findById`로 먼저 존재를 확인한 뒤 엔티티 기준으로 삭제하는 방식으로
  수정(`retry()`가 이미 쓰는 findById-then-mutate 패턴과 일관됨). 수정 후 같은 curl
  재현으로 404 반환을 재확인했다. 회귀 방지로 컨트롤러 테스트에 케이스를 추가했다
  (`returns404ForDeleteMissingTask`).
  - 이 사례는 코드 리뷰(정적 분석/코드 읽기 기반)가 프레임워크 버전별 실제 런타임 동작까지는
    검증하지 못한다는 걸 보여준다 — 이런 종류의 결함은 QA(실제 요청으로 검증)에서만 잡힌다.

## 남은 절차
- 이 이슈는 관리자 전용 API라 Postman(15단계) 반영 대상은 유지하되, Notion(17단계)
  반영은 프론트 소비자가 아직 없어 보스 확인 필요(아래 참고).
- 16단계: 보스 최종 확인 후 PR 생성
