# #77 브랜치 2단계 구조 재편(dev → staging + 신규 dev) — 기획서

관련 이슈: [#77 브랜치 2단계 구조 재편(dev → staging + 신규 dev)](https://github.com/GBSW-ReMake/GONE-server-V1/issues/77)
선행 이슈: [#51](./51-chore-dev-cicd.md)(dev EC2 CI/CD 구축, 완료·머지됨) — 이 이슈가
건드리는 `deploy-dev` job/GitHub Environment가 #51에서 만들어졌다.
흡수하는 이슈: **#64**(dev 배포 Discord 알림 세부화) — 원래는 별도 이슈로 분리했으나
(보스 확인, 2026-08-21) 이번 브랜치 재편과 함께 staging 배포 알림도 다시 설계하기로
범위를 넓혔다. #64가 다루려던 내용(실행자 아바타, 소요 시간, 실패 단계 구분)을 아래
"Discord 배포 알림 개선" 절에 포함해 이번 이슈에서 함께 구현하고, 머지 후 #64는
중복으로 닫는다.

## 🔒 시크릿 취급 원칙 (최우선, #51과 동일하게 적용)
- 이 문서 어디에도 실제 EC2 IP/호스트명, SSH 키, DB 비밀번호, JWT 시크릿 등 실제 값을
  적지 않는다. 이름(placeholder)만 다룬다 — 실제 값은 옵시디언 `[[GitHub Secrets
  (Dev)]]` 노트에 이미 기록되어 있어(현재 GitHub Environment `DEV`에 15개 전부 등록된
  값과 동일) 새로 발급할 필요가 없다. 새 `STAGING` 환경에 그 값을 그대로 복사해
  등록한다.
- `DISCORD_WEBHOOK`도 이 15개 시크릿 중 하나다 — staging용으로 새 웹훅을 만들지 않고
  기존 값을 그대로 재사용한다(보스 확인, 2026-08-21).

## 개요/목적
현재 `dev` 브랜치가 "로컬 feature 브랜치들이 모이는 지점"과 "실제 EC2로 자동 배포되는
지점" 역할을 동시에 하고 있다. 이 둘을 분리한다.

- **기존 `dev`(실제 배포되는 지점) → `staging`으로 이름만 바꾼다.** 그 브랜치가 가리키는
  커밋, 배포 파이프라인의 동작 방식, EC2 서버, 시크릿 값은 전부 그대로다 — **git
  브랜치명(`dev`→`staging`)과 GitHub Environment 이름(`DEV`→`STAGING`, 기존 `DEV`
  표기 관례를 그대로 따름)만** 바뀐다.
- **새 `dev` 브랜치를 신설**해 로컬 feature 브랜치들의 병합 대상(통합 지점)으로 쓴다.
  이 브랜치에는 배포 파이프라인을 붙이지 않는다(빌드/테스트만 돈다).
- **`main`/prod는 이번 범위에서 완전히 제외한다**(보스 확인, 2026-08-21) — GitHub 기본
  브랜치도 `main` 그대로 유지하고 손대지 않는다. 운영 EC2는 아직 개방 전이라, 이 부분은
  별도 이슈로 나중에 다룬다(1_schoolcamp-domain.md 같은 마스터 문서 없이, 필요해지면
  새 이슈로 시작).

## 브랜치 구조 변화
```
[변경 전]                                [변경 후]
feat/#N-... ──PR──▶ dev(=배포 대상)      feat/#N-... ──PR──▶ dev(신규, 로컬 통합, 배포 없음)
                                                                    │
                                                              승격 PR
                                                                    ▼
                                                          staging(=배포 대상, 구 dev)

main(기본 브랜치, 배포 미연결) ── 변경 없음, 이번 범위 밖 ──▶ main(그대로)
```

## 구현 순서 (반드시 이 순서대로 — 순서를 바꾸면 배포가 일시적으로 끊길 수 있음)
1. **새 GitHub Environment `STAGING` 생성 + 시크릿 15개 등록**(옵시디언 `[[GitHub
   Secrets (Dev)]]` 값을 그대로 복사). 브랜치 rename보다 먼저 해야, rename 직후 바로
   이어지는 배포 검증에서 곧바로 쓸 수 있다.
2. **GitHub에서 `dev` → `staging` 브랜치 rename**(웹 UI `Settings → Branches` 또는
   `gh api repos/{owner}/{repo}/branches/dev/rename -f new_name=staging`). 이 시점에
   `refs/heads/dev`는 더 이상 존재하지 않는다.
   > ⚠️ **이 시점부터 아래 3~4번이 끝나기 전까지는 `staging`에 push해도 자동 배포가
   > 트리거되지 않는다.** 기존 `ci.yml`의 `deploy-dev` job 조건이 여전히
   > `github.ref == 'refs/heads/dev'`인 채로 `staging` 브랜치에 실려있기 때문 — ref가
   > 더 이상 일치하지 않아 조건이 항상 거짓이 된다. 배포가 "실패"하는 게 아니라
   > "아예 안 도는" 상태이니 당황할 필요는 없지만, 이 공백을 최소화하려면 3~4번을
   > 최대한 빨리 이어서 진행한다.
3. **새 `dev` 브랜치를 `staging`의 현재 커밋에서 분기**해 push한다(`git branch dev
   staging && git push origin dev`, 또는 GitHub 웹 UI로 분기).
4. `chore/#77-branch-restructure` 같은 이름의 새 브랜치를 `dev`에서 분기해 아래 "CI/CD
   워크플로우 변경"/"문서 갱신" 내용을 구현하고, **`dev`로 PR을 올려 머지**한다(신규
   `dev`가 이제 로컬 통합 지점이므로 이 이슈 자체도 그 규칙을 따른다).
5. `dev` → `staging` **승격 PR**을 올려 머지한다 — 이 머지로 `staging`의 `ci.yml`이
   비로소 새 `environment: STAGING`/`refs/heads/staging` 조건을 갖게 되고, 이 push
   자체가 곧 새 배포 파이프라인의 첫 실행이 된다.
6. `staging` 배포가 새 환경/시크릿으로 정상 동작하는지 헬스체크로 확인한다(위 "완료
   조건" 참고).
7. 검증 끝나면 기존 `DEV`(대문자) GitHub Environment를 삭제해 정리한다.

## CI/CD 워크플로우 변경 (`.github/workflows/ci.yml`)
| 위치 | 변경 전 | 변경 후 |
|---|---|---|
| `on.pull_request.branches` / `on.push.branches` | `[main, dev]` | `[main, dev, staging]`(신규 `dev`와 `staging` 둘 다 checkstyle/build-and-test 대상) |
| `build-and-push-image`의 `if` | `github.ref == 'refs/heads/dev'` | `github.ref == 'refs/heads/staging'` |
| `deploy-dev` job의 `environment:` | `dev` | `STAGING`(기존 `DEV` 표기 관례를 그대로 따름) |
| `deploy-dev` job의 `needs`/`if` | 위와 동일한 ref 조건 | `refs/heads/staging`으로 갱신 |
| job id `deploy-dev` | — | `deploy-staging`으로 이름 변경(아래 "결정 사항" 참고) |
| `deploy-dev`/`deploy-staging`의 Discord 알림 스텝 | 제목 + 커밋 한 줄뿐인 payload | 실행자 아바타/브랜치/소요 시간/실패 단계 포함(아래 "Discord 배포 알림 개선" 절) |

**Docker 이미지 태그(`ghcr.io/gbsw-remake/gone-server-v1:dev`), `deploy/docker-
compose.dev.yml` 파일명, EC2의 `/opt/gone/dev/` 디렉토리 경로는 이번 이슈에서 바꾸지
않는다(결정 사항, 아래 "리스크" 절 참고).**

## GitHub Environment 변경
- 신규: `STAGING`(시크릿 15개, 기존 `DEV`와 동일한 값 — 대문자 표기는 `DEV` 관례를 그대로 따름)
- 정리(삭제 대상, 검증 완료 후): 기존 `DEV`
- 변경 없음: `copilot`, `production`(아직 생성하지 않음 — 이번 범위 밖)

## Discord 배포 알림 개선 (#64 흡수)
`deploy-dev`(→`deploy-staging`) job의 성공/실패 알림이 지금은 제목 + 커밋 한 줄뿐이라,
같은 워크플로우의 `checkstyle`/`build-and-test` 알림(#22에서 개선된 실행자 아바타·소요
시간·PR/브랜치 컨텍스트 포맷)에 비해 정보가 부실하다. `build-and-test`가 이미 쓰는
패턴을 재사용해 아래처럼 맞춘다.

**공통 필드(성공/실패 모두)**
- 제목: `🚀 staging 배포 성공` / `🔥 staging 배포 실패` (기존 `✅`/`❌`보다 눈에 띄는
  이모지로 상태 구분)
- `author`: 실행자 GitHub 아바타(`icon_url`) — `build-and-test`와 동일한 방식
- 필드: `🌿 브랜치`(`staging` 고정), `📦 커밋`(SHA+메시지), `⏱️ 소요 시간`(job 시작
  시각 기록 → 알림 시점 차이, `build-and-test`와 동일한 계산 방식), 실행 로그 링크는
  기존처럼 `embeds[0].url`로 유지
- **EC2 호스트/IP 등 인프라 값은 여전히 알림 payload에 절대 포함하지 않는다**(위 시크릿
  원칙과 동일 — "staging 서버"처럼 이름으로만 지칭)

**실패 시 추가 필드**
- `⚠️ 실패 단계`: 파일 전송(scp) / `.env` 생성·컨테이너 갱신(ssh) / 헬스체크 중 어디서
  멈췄는지 표시. 각 배포 스텝 시작 시 `GITHUB_ENV`에 `CURRENT_STAGE`를 기록해두고,
  실패 알림 스텝에서 마지막으로 기록된 값을 읽어 채운다(#64 이슈 본문의 "검토" 항목을
  이번에 확정).

**색상**: 기존과 동일하게 성공 `65280`(녹색)/실패 `16711680`(빨강) 유지.

## 문서 갱신 대상
- **`docs/rules/branch-workflow.md`**: 17단계 전체에서 "dev" 언급이 배포 대상인지
  로컬 통합 지점인지 구분해서 수정. 특히:
  - "🚫 dev 직접 푸시 금지" 절 — 지금은 "dev에 CI/CD가 붙어있어 직접 push가 곧 배포"라는
    이유였는데, 이제 그 이유는 `staging`에 해당한다. `dev`(신규)도 "브랜치 분기 → PR로
    머지"라는 저장소 전체 컨벤션은 동일하게 지키되, 직접 push 금지의 **이유**를
    "배포 트리거"에서 "히스토리/리뷰 규율 유지"로 바꿔 적는다. `staging` 직접 push
    금지 절을 새로 추가한다(진짜 배포 트리거는 이제 `staging`이므로).
  - 7단계(브랜치 생성) — "`dev`에서 분기" 그대로 유지(신규 `dev`가 그 역할을 이어받음).
  - "한 번에 하나의 기능만" 절(progress-tracking.md) — 승격 PR(`dev`→`staging`)도 이
    규칙 대상인지 명시(승격 PR은 이미 검증된 커밋을 옮기는 것뿐이라 별도 이슈/기획서
    없이 진행 가능하다는 점을 명확히 한다).
- **`docs/rules/pr-template.md`**: "이 저장소의 default 브랜치는 `main`이고 feature
  PR은 `dev`로 머지되므로 closes #N이 자동 종료되지 않는다"는 설명은 구조가 바뀌어도
  **여전히 유효**하다(신규 `dev`도 default 브랜치가 아니므로) — 문구만 "기존 dev"
  같은 오해 소지 없이 다시 확인.
- **`docs/rules/progress-tracking.md`**: 위 "한 번에 하나의 기능만" 관련 갱신 외
  구조적 변경 없음.

## 영향 받는 기존 코드/문서
- 수정: `.github/workflows/ci.yml`, `docs/rules/branch-workflow.md`,
  `docs/rules/pr-template.md`, `docs/rules/progress-tracking.md`
- 변경 없음: `Dockerfile`, `deploy/docker-compose.dev.yml`, `deploy/nginx.conf`,
  애플리케이션 코드 전체
- GitHub 저장소 설정: 브랜치 1개 rename(`dev`→`staging`) + 브랜치 1개 신설(`dev`),
  Environment 1개 신설(`STAGING`) + 추후 1개 삭제(`DEV`)
- 기본 브랜치(`main`) 변경 없음

## 리스크 및 고려사항
- **API 설계 6원칙**: 이 이슈는 신규/변경 엔드포인트가 없어 해당 없음.
- **결정 사항 — 내부 라벨(이미지 태그/디렉토리/파일명)은 이번에 안 바꾼다**: git 브랜치명·
  GitHub Environment명과 달리, Docker 이미지 태그(`:dev`)나 EC2 경로(`/opt/gone/dev/`)는
  외부에 노출되지 않는 내부 구현 디테일이라 브랜치 전략과 반드시 같은 이름일 필요가
  없다고 판단했다. 전부 맞춰 바꾸려면 EC2에 새 디렉토리를 만들고 기존 볼륨(`mysql-
  data`/`redis-data`)을 옮기는 작업까지 딸려와 위험도가 커지는데, 얻는 이득은 "이름이
  좀 더 일관되어 보인다" 정도뿐이다. 다만 향후 신규 합류자가 "왜 staging 배포인데
  디렉토리는 dev냐"고 헷갈릴 수 있다는 점은 인지하고, `Infra 인수인계.md`류 문서에 이
  이유를 남겨 혼란을 방지한다.
- **배포 공백 윈도우(인지, 최소화)**: 위 "구현 순서" 2번의 경고 박스 참고 — rename
  직후부터 `dev`→`staging` 승격 PR이 머지되기 전까지는 `staging`에 push해도 배포가
  트리거되지 않는다. 이 구간에는 원칙적으로 아무도 `staging`에 직접 push하지 않을
  것이므로(승격 PR을 통해서만 반영) 실질적 영향은 없지만, 순서를 반드시 지켜야 한다.
- **단일 개발자 저장소라 rename 충돌 위험 낮음**: 현재 열려있는 PR이 0개임을 확인
  완료(`gh pr list --state open`) — rename으로 인한 PR base 자동 전환 문제가 없다.
- **기존 `DEV` 환경을 바로 삭제하지 않고 검증 후 삭제**: `staging` 배포가 실제로
  정상 동작하는 것을 헬스체크로 확인하기 전까지는 롤백 여지를 남겨둔다.
- **Discord 웹훅 알림 내용도 이번 이슈 범위에 포함한다(#64 흡수, 보스 확인
  2026-08-21)** — 위 "Discord 배포 알림 개선" 절 참고. 웹훅 URL 자체는 새로 만들지
  않고 기존 값을 그대로 재사용한다.

## 테스트/검증
- 로컬: `./gradlew build`, `./gradlew checkstyleMain checkstyleTest` 통과
- CI: 신규 `dev` 브랜치에 PR/push 시 checkstyle+build-and-test만 돌고
  `build-and-push-image`/`deploy-staging`은 스킵되는지 확인(Actions 탭에서 job
  스킵 여부 확인)
- 배포: `staging` 승격 PR 머지 후 Actions에서 `deploy-staging`이 성공하는지,
  `curl http://<EC2_HOST>:9090`(또는 nginx 80)이 500 미만을 반환하는지 확인
- Discord 알림: 새 포맷(실행자 아바타, 브랜치/커밋/소요 시간 필드, 이모지 제목)이
  실제로 오는지 확인. 배포 스텝 하나를 의도적으로 실패시켜(예: 헬스체크 타임아웃)
  실패 알림의 `⚠️ 실패 단계` 필드도 한 번 확인
- 문서: 갱신된 `branch-workflow.md`를 처음부터 끝까지 다시 읽어 "dev"/"staging"
  지칭이 헷갈리는 문장이 남아있지 않은지 확인
