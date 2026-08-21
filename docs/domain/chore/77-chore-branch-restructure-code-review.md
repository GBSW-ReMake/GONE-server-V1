# #77 브랜치 2단계 구조 재편 — 코드 리뷰 결과

리뷰 대상: `git diff dev...chore/#77-branch-restructure`
(`.github/workflows/ci.yml`, `docs/rules/branch-workflow.md`, `docs/rules/pr-template.md`,
`docs/rules/progress-tracking.md`, 신규 `docs/domain/chore/77-chore-branch-restructure.md`)

리뷰어는 구현 과정에 관여하지 않은 격리된 세션에서, 기획서만 참고해 진행했다
([code-review-isolation.md](../../rules/code-review-isolation.md) 준수).

## 발견 사항

### 1. 🟢 Low — `build-and-test` job의 jar 업로드 스텝 주석이 여전히 "dev EC2 배포"로 남아 있음

**문제**: `.github/workflows/ci.yml:179`의 주석은 이번 PR로 수정되지 않은 채 다음과 같이
남아 있다.

```
# dev EC2 배포(#51)를 위해, 이미 테스트까지 통과한 jar를 이후 job에서 재사용한다.
```

같은 파일의 `deploy-staging` job 헤더 주석(`ci.yml:350`)은 이번 PR에서 정확히
"staging EC2(구 dev EC2)"로 갱신됐는데, `build-and-test` job 쪽의 이 주석만 갱신 대상에서
빠졌다. 기능상 영향은 없지만(순수 주석), 같은 파일 안에서 "dev EC2"와 "staging EC2(구 dev
EC2)"라는 서로 다른 표현이 공존해 향후 합류자가 실제 배포 대상이 무엇인지 헷갈릴 수 있다.
기획서의 "리스크 및 고려사항"에서도 "신규 합류자가 헷갈릴 수 있다는 점을 인지하고 문서에
이유를 남긴다"고 명시한 만큼, 이런 잔존 표현은 정리하는 편이 그 원칙에 부합한다.

**해결 방안**:
1. 주석을 `# staging EC2(구 dev EC2) 배포(#51, #77)를 위해, ...`처럼 `deploy-staging` job
   헤더 주석과 동일한 표현으로 맞춘다 — 가장 확실하고 비용이 거의 없다. 단점은 없음(순수
   문서성 변경).
2. 지금 당장은 그대로 두고, 다음에 이 job을 건드릴 때(예: 다른 이슈로 jar 업로드 로직을
   수정할 때) 같이 정리한다 — 이번 PR 범위를 CI/CD 워크플로우 표(`if`, `environment`,
   job id, Discord 알림)로 좁게 유지할 수 있다는 장점이 있지만, 기획서가 이미 "dev/staging
   지칭 혼동 방지"를 문서 갱신의 명시적 목표로 두고 있어 이번에 같이 처리하지 않으면 그
   원칙과 살짝 어긋난다.

## 확인했지만 문제 없었던 항목 (Critical/High/Medium 없음)

- **CI 워크플로우가 기획서 표와 일치하는지**: `on.pull_request/push.branches`에
  `staging` 추가, `build-and-push-image`의 `if` ref를 `refs/heads/staging`으로 변경,
  job id `deploy-dev` → `deploy-staging`, `environment: dev` → `environment: STAGING`,
  Discord 알림 필드(아바타/브랜치/소요 시간/실패 단계) 추가까지 기획서 표
  ("CI/CD 워크플로우 변경" 절)와 한 줄씩 대조했고 모두 일치한다.
- **`deploy-dev`/`refs/heads/dev`/`environment: dev` 잔존 여부**: `grep -n "deploy-dev"
  .github/workflows/ci.yml` 결과 0건. `needs:` 그래프(`checkstyle` → `build-and-test` →
  `build-and-push-image` → `deploy-staging`)에도 옛 job id를 참조하는 곳이 없다. 위 1번
  항목(잔존 "dev EC2" 문구)을 제외하면 job 정의/조건문 자체에는 잔존 참조가 없다.
- **YAML 자체의 정확성**: `needs`/`if`/`concurrency` 그룹이 각 job에서 논리적으로
  일관된다. `CURRENT_STAGE`는 `GITHUB_ENV`에 기록되는데, GitHub Actions에서
  `$GITHUB_ENV`에 쓴 값은 해당 job의 이후 스텝 전체에서 보이므로("배포 단계 기록" 스텝 →
  "헬스체크" 스텝 → "Discord 알림 (실패)" 스텝) 의도대로 동작한다. "알림용 컨텍스트 수집"
  스텝이 `if: always()`로 돼 있어 실패 시에도 `SHORT_SHA`/`COMMIT_MSG`/`ELAPSED`가 채워짐을
  확인했다(`ci.yml:471-481`). 실패 알림의 `${CURRENT_STAGE:-알 수 없음}` fallback도
  체크아웃 자체가 실패하는 극단적 케이스까지 커버한다.
- **시크릿/인프라 값 유출 여부**: 새 Discord embed 필드(`author.icon_url`은 공개
  GitHub 아바타 URL, `🌿 브랜치`는 하드코딩된 문자열 `"staging"`, `⚠️ 실패 단계`는
  "파일 전송(scp)" 등 고정 문자열)에 `EC2_HOST`/`EC2_USER` 등 시크릿이나 인프라 값이
  전혀 들어가지 않는다. `.env` 생성 스텝도 기존과 동일하게 SSH 세션 내부에서만 값을
  다뤄 로그에 남지 않는다.
- **`dev`/`staging`/`DEV`/`STAGING` 표기 일관성**: git 브랜치명은 전부 소문자
  (`dev`/`staging`), GitHub Environment명은 전부 대문자(`STAGING`)로 일관되게
  쓰였다. `docs/rules/*.md` 세 파일 모두 이 관례를 어기지 않는다.
- **문서 갱신 대상 커버리지**: `branch-workflow.md`의 "🚫 dev 직접 푸시 금지" 절 이유가
  "히스토리/리뷰 규율 유지"로 바뀌었고, "🚫 staging 직접 푸시 금지" 절이 신설됐으며,
  `progress-tracking.md`에 승격 PR 예외가 명시되고, `pr-template.md`의 "closes #N
  자동 종료 안 됨" 설명도 새 구조(`dev → staging` 승격 PR/향후 `main` PR 모두 자동 종료
  트리거 아님)에 맞게 갱신됐다 — 기획서 "문서 갱신 대상" 절의 네 항목 모두 확인했다.
- **저장소 실제 상태와의 정합성**: `git branch -a`로 `origin/dev`, `origin/staging`이
  모두 존재하고 같은 커밋을 가리키는 것을 확인해, 기획서 "구현 순서" 1~3번(Environment
  생성, rename, 새 dev 분기)이 이 브랜치 작업 이전에 이미 선행된 상태임을 확인했다.

## 반영 시점

코드 리뷰 직후(9단계) 작성. QA(10단계) 시작 전 이 문서가 먼저 존재해야 한다는
[code-review-template.md](../../rules/code-review-template.md) 규칙을 따랐다.
