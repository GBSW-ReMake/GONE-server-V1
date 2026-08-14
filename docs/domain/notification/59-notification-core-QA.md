# #59 알림 도메인 — Notification 엔티티 + 공통 발송 모듈 — QA 결과

관련 기획서: [59-notification-core.md](./59-notification-core.md)
관련 코드 리뷰: [59-notification-core-code-review.md](./59-notification-core-code-review.md)
(9단계 코드 리뷰 지적 사항 Medium 2건은 QA 이전에 반영 완료 — 이 문서는 그 이후 로컬
검증 결과만 다룬다)

## 검증 방법/범위
- 이 이슈는 컨트롤러/REST API가 없다(기획서 "엔드포인트: 없음") — 실제 서버를 띄운
  Postman/curl 검증 대상 자체가 없다. 대신 기획서의 "테스트 방법" 절이 명시한 대로
  단위 테스트 + 로컬 빌드/체크스타일 통과 여부로 검증한다.
- `./gradlew checkstyleMain checkstyleTest`: 통과.
- `./gradlew test --tests "com.remake.gone.notification.*"`: 통과 (`savesNotificationWithGivenValues`,
  `allowsNullType` 2건 모두 성공, 코드 리뷰 반영으로 `UserRepository` 목(mock)
  스텁이 추가된 이후 버전으로 재검증).
- `./gradlew build`(전체): 통과.
- GitHub Actions CI: `.github/workflows/ci.yml`은 `main`/`dev`로의 push 또는 그
  브랜치를 대상으로 한 pull_request에서만 트리거된다. 이번 이슈는 아직 PR을 만들지
  않아 `feat/#59-notification-core`로의 push만으로는 CI가 실행되지 않았다 — 16단계
  PR 생성 시 자동으로 실행되는 CI 결과를 그때 다시 확인한다.

## 발견 사항

### Critical / High
없음.

### Medium
없음(9단계 코드 리뷰에서 나온 Medium 2건은 그 단계에서 이미 반영 완료 — 반영 내용은
[코드 리뷰 문서의 "반영 결과"](./59-notification-core-code-review.md#반영-결과) 참고).

### Low
1. **CI 통과 여부는 이 QA 시점에는 확인 불가(환경 제약)** — 위 "검증 방법/범위" 참고.
   PR 생성 후 CI 결과를 다시 확인해야 10단계의 "CI 통과 확인" 조건이 완전히 충족된다.

## 결론
Critical/High/Medium 없음. 로컬에서 검증 가능한 범위(빌드/테스트/체크스타일)는 전부
통과했다. 컨트롤러가 없는 이슈 특성상 실서버 기반 QA는 해당 사항이 없고, CI 통과
확인만 PR 생성 이후로 남아 있다(Low 1).
