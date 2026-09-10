# #160 dev → staging 프로모션 지연 경고 자동화 — QA 결과

관련 이슈: [#160](https://github.com/GBSW-ReMake/GONE-server-V1/issues/160)
관련 기획서: [160-chore-dev-staging-promotion-warning.md](./160-chore-dev-staging-promotion-warning.md)
관련 코드 리뷰: [160-chore-dev-staging-promotion-warning-code-review.md](./160-chore-dev-staging-promotion-warning-code-review.md)

## 검증 방법
애플리케이션 코드 변경이 없어(신규 엔드포인트 없음) `./gradlew build`/`test`/
`checkstyleMain`은 영향을 받지 않는다. 이 변경은 GitHub Actions 워크플로우라 로컬에서
직접 실행할 수 없고(`act` 등 로컬 러너 미설치), 트리거 자체가 "PR이 실제로 머지될 때"라
CI 파이프라인 통과만으로는 동작을 검증할 수 없다. 대신 워크플로우 안 github-script
로직을 그대로 옮긴 Node 스크립트로 **실제 저장소의 라이브 데이터**를 대상으로 카운트
로직만 별도로 재현해 검증했다(부작용 없이 GitHub API 조회만 수행).

## 검증 결과

### 정상 동작 확인 (실제 데이터로 재현)
- 마지막 프로모션 PR 탐색: `#154`(2026-09-09T08:02:48Z, base=staging, head=dev) 정확히
  탐지됨.
- 그 이후 `dev`에 머지된 PR 집계: `#156` 1건만 집계됨 — `git log origin/staging..origin/dev`로
  직접 확인한 결과(커밋 3개, PR 1개분)와 일치.
- 조기 종료 페이지네이션 로직(코드 리뷰 1번 반영분)이 정상적으로 페이지를 순회하고
  멈춤을 확인.
- 현재 집계값(1건)이 임계치(5건) 미만이라 "아무 것도 하지 않고 종료"하는 경로도 실제
  데이터로 확인됨(위 집계 자체가 이 경로를 통과했다는 뜻).

### 검증하지 못한 항목

**Medium** — 임계치 도달 시 이슈 생성/Discord 알림 경로, 그리고 프로모션 PR 머지 시
경고 이슈를 닫는 `close-promotion-warning` job을 실제로 트리거해서 검증하지 못했다.
검증하려면 (a) 실제로 `dev`에 PR을 5개 이상 머지하거나, (b) 임계치를 낮춰 실제 이슈
생성과 실제 Discord 알림(운영 채널의 `DISCORD_CI_WEBHOOK`)을 발생시켜야 하는데, 둘 다
운영 저장소/운영 Discord 채널에 실제 부작용(가짜 이슈, 팀 채널에 테스트 알림)을 남긴다.
정적 검토(코드 리뷰)로 로직은 확인했지만, "실제로 GitHub이 이 워크플로우를 그 조건에서
정말 트리거하는지", "Discord embed가 실제로 원하는 모양으로 렌더링되는지"는 실제
이벤트가 발생하기 전까지는 확인할 수 없다.

## 결정
**1번 방식으로 확정** — 지금은 정적 검토 + 라이브 카운트 검증까지만으로 머지하고, 실제
`dev`에 5번째 PR이 쌓이는 시점(자연 발생)에 처음으로 이슈 생성/Discord 알림 경로가 실제로
동작하는지 확인한다. 팀 채널에 테스트성 알림을 미리 내보내지 않는 쪽을 선택했다(보스 결정,
2026-09-10). 첫 실동작에서 문제가 발견되면 그때 후속 이슈로 대응한다.
