# #51 dev 서버(EC2) 배포 자동화(CI/CD) 구축 — QA 결과

관련 기획서: [51-chore-dev-cicd.md](./51-chore-dev-cicd.md)
관련 코드 리뷰: [51-chore-dev-cicd-code-review.md](./51-chore-dev-cicd-code-review.md)
(9단계 코드 리뷰 지적 사항은 QA 이전에 전부 반영 완료 — 이 문서는 QA에서 새로 발견된
것만 다룬다)

## 검증 방법/범위
- `./gradlew checkstyleMain checkstyleTest`: 통과(Java 코드 변경 없음, 기존 상태 유지 확인).
- `./gradlew build`(clean 후 재실행): 통과.
- `.github/workflows/ci.yml`, `deploy/docker-compose.dev.yml`: Python `yaml` 모듈로
  파싱 검증(구문 오류 없음, job/service 키 구성 확인).
- 이 로컬 환경에는 실행 중인 Docker 데몬(Docker Desktop)이 없어 `docker build`/
  `docker compose` 실제 실행은 하지 못했다(아래 "Medium" 항목 참고).
- 실제 EC2/GitHub Secrets가 아직 준비되지 않아(기획서의 "EC2 사전 준비"는 보스가 직접
  수행하는 항목) `deploy-dev` job의 실제 배포 동작(scp/ssh/헬스체크/Discord 알림)은
  GitHub Actions 상에서 실행해보지 못했다.

## 발견 사항

### High
없음.

### Medium
1. **Docker 빌드 자체를 로컬에서 실행하지 못함(환경 제약)** — 이 세션의 로컬 환경에
   Docker 데몬이 떠 있지 않아(`docker version` 시 데몬 연결 실패) `docker build -f
   Dockerfile .`을 실제로 실행해보지 못했다. 대신 `./gradlew build`로 `build/libs/`
   산출물이 Dockerfile이 기대하는 형태(jar 정확히 1개)인지를 파일 시스템 레벨에서
   확인했고, 그 과정에서 실제 버그(아래 "발견 후 수정" 참고)를 하나 찾아 고쳤다. 하지만
   `docker build`가 실제로 성공하는지, 이미지가 실행됐을 때 `ENTRYPOINT`가 정상
   동작하는지는 여전히 실제로 실행해보지 않은 상태다.
2. **전체 배포 파이프라인(GHCR 푸시 → EC2 배포 → 헬스체크 → Discord 알림)을 실제로
   실행해보지 못함(환경 제약)** — `deploy-dev`/`build-and-push-image` job은 실제
   GitHub Actions 러너 + 실제 EC2 + 등록된 GitHub Secrets가 있어야 실행 가능하다.
   이 중 EC2 사전 준비와 Secrets 등록은 기획서에 이미 "보스가 직접 수행"으로 명시된
   항목이라 이번 QA에서 제가 대신 진행할 수 없다. YAML 문법과 각 스텝의 로직(주석/
   변수 참조 일관성)은 코드 리뷰 + 이번 QA에서 정적으로 검토했지만, **실제 SSH 접속,
   `.env` 생성, `docker compose up`, 헬스체크 판정, Discord 알림까지 실제로 성공하는지는
   보스가 EC2를 준비하고 GitHub Secrets를 등록한 뒤 `dev`에 첫 push를 해봐야 확인된다.**

### Low
없음(코드 리뷰 9단계에서 나온 Low 3건은 모두 그 단계에서 반영 완료).

## 발견 후 즉시 수정한 문제 (QA 중 발견, 별도 이슈 분리 없이 같은 브랜치에서 수정)
- **`build/libs/`에 jar가 2개 생성돼 `Dockerfile`의 `COPY build/libs/*.jar app.jar`가
  실패할 뻔함**: Spring Boot Gradle 플러그인은 기본적으로 실행 가능한 `bootJar`
  (`GONE-server-V1-0.0.1-SNAPSHOT.jar`, 85MB) 외에 의존성이 빠진 "plain" jar
  (`GONE-server-V1-0.0.1-SNAPSHOT-plain.jar`, 163KB)도 함께 만든다. 실제로
  `./gradlew build` 후 `build/libs/`를 확인해서 발견했다(코드 리뷰 에이전트는 이
  경로를 실제로 실행해보지 않고 문서상으로만 검토해 놓쳤다). Docker COPY는 와일드카드가
  파일 2개 이상을 매칭하면 목적지가 디렉터리가 아닌 한 빌드 자체가 실패한다 — `docker
  build`를 실제로 돌려봤다면(위 Medium 1번 참고) 여기서 막혔을 것이다. `build.gradle`에
  `tasks.named('jar') { enabled = false }`를 추가해 plain jar 생성을 꺼서, `build/libs/`에
  bootJar 하나만 남도록 고쳤다(별도 커밋 `fix(build): ...`). 수정 후 clean 빌드로
  `build/libs/`에 파일이 정확히 1개인 것을 재확인했다.

## 결론
Critical/High 없음. 코드/설정 정적 검토와 로컬에서 실행 가능한 범위(빌드, YAML 파싱)는
전부 통과했고, 그 과정에서 실제 버그 1건(jar 충돌)을 찾아 수정했다. **다만 이 이슈의
핵심 가치(실제로 dev EC2에 자동 배포되는지)는 EC2/Secrets가 준비된 뒤에야 검증
가능하다** — 이 부분은 로컬/CI로 대신할 방법이 없어 보스의 환경 준비 후 실제 첫 배포
결과로 최종 확인이 필요하다.
