# #51 dev 서버(EC2) 배포 자동화(CI/CD) 구축 — 기획서

관련 이슈: [#51 dev 서버(EC2) 배포 자동화(CI/CD) 구축](https://github.com/GBSW-ReMake/GONE-server-V1/issues/51)

## 🔒 시크릿 취급 원칙 (최우선, 이 문서 전체에 적용)
- 이 문서 어디에도 실제 EC2 IP/호스트명, SSH 키, DB 비밀번호, JWT 시크릿 등 실제 값을
  적지 않는다. 아래 모든 설정값은 이름(placeholder)만 다루고, 실제 값은 GitHub 저장소
  **Settings → Secrets and variables → Actions**에 보스가 직접 등록한다.
- **애플리케이션 시크릿(DB 비밀번호, JWT 시크릿, R2/NEIS 키)도 배포 시크릿(SSH 키)과
  함께 GitHub Secrets에 등록한다.** `deploy-dev` job이 매 배포마다 그 값들로 EC2의
  `.env` 파일을 새로 생성한다 — 수동으로 EC2에 미리 넣어두지 않는다(보스 결정, 아래
  "GitHub Actions 워크플로우" 참고). GitHub Secrets는 워크플로우 실행 중에만 러너의
  환경변수로 잠깐 존재하고, 로그에 그 값이 그대로 찍히면 GitHub이 자동으로 마스킹한다.
  이 문서의 스크립트는 그 마스킹에만 기대지 않고 `set -x`(셸 디버그 출력) 등 시크릿을
  의도치 않게 표준출력에 남길 수 있는 옵션을 쓰지 않는다.
- SSH 키는 배포 전용으로 새로 생성한다. EC2에는 sudo 권한이 없는 전용 `deploy` 계정을
  만들고, 그 계정에만 이 배포 키를 등록한다.
- `deploy-dev` job은 `dev` 브랜치로의 `push` 이벤트에서만 실행된다(PR 이벤트에서는
  절대 실행되지 않음 — 아래 "GitHub Actions 워크플로우" 조건 참고). 시크릿에 접근하는
  잡은 신뢰된 트리거로만 제한한다.

## 개요/목적
`dev` 브랜치에 push되고 기존 CI(체크스타일/빌드/테스트)를 통과하면, EC2 위에서 Docker
Compose로 띄운 애플리케이션 컨테이너를 자동으로 최신 버전으로 갱신한다. dev EC2 한 대에
`app`(Spring Boot 컨테이너), `mysql`, `redis` 세 컨테이너를 함께 띄운다 — RDS를 쓰지 않고
DB/Redis도 컨테이너로 격리해 운영 인프라와 완전히 분리한다.

운영(prod) EC2/RDS/S3 배포 구조는 이번 범위 밖이다(이슈 #51 본문에 후속 이슈로 명시).

## 아키텍처
```
[개발자] --push(dev)--> [GitHub Actions]
                              |
                    1) checkstyle (기존)
                              |
                    2) build-and-test (기존, mysql/redis 서비스 컨테이너로 테스트)
                              |
              (push && ref=dev 일 때만) 3) build-and-push-image
                    - ./gradlew build로 나온 jar를 그대로 Docker 이미지에 담아 빌드
                    - ghcr.io/gbsw-remake/gone-server-v1:dev 로 푸시
                              |
              4) deploy-dev
                    - deploy/docker-compose.dev.yml을 EC2의
                      /opt/gone/dev/docker-compose.dev.yml로 scp(파일명 유지)
                    - ssh 세션 안에서 GitHub Secrets 값으로 /opt/gone/dev/.env를
                      새로 생성(매 배포마다 덮어씀)
                    - docker compose -f docker-compose.dev.yml pull app && up -d app
                    - 헬스체크(포트 응답 재시도)
                    - Discord 알림(dev 전용 웹훅)
```
`mysql`/`redis` 컨테이너는 매 배포마다 재시작되지 않는다 — `docker compose up -d app`은
`app` 서비스만 갱신한다(볼륨에 데이터가 남아있는 `mysql`/`redis`를 매번 건드릴 이유가
없다).

## Docker 이미지
### `Dockerfile` (저장소 루트, 신규)
```dockerfile
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 9091
ENTRYPOINT ["java", "-jar", "app.jar"]
```
CI가 이미 `./gradlew build`로 테스트까지 통과한 jar를 만들어두므로, 이미지 안에서 다시
컴파일하지 않는다 — `docker build`는 그 jar를 그대로 패키징만 한다(같은 걸 두 번 빌드하지
않는다). 빌드 컨텍스트는 `./gradlew build` 실행 직후, 같은 job 안에서 `build/libs/`가
존재하는 상태로 실행한다.

### 이미지 태그
`ghcr.io/gbsw-remake/gone-server-v1:dev` 하나만 쓴다(고정 태그, 매 배포마다 덮어씀).
GitHub Container Registry(GHCR)를 쓰는 이유: 이 저장소와 같은 GitHub 조직 소속이라 별도
계정 생성 없이 `GITHUB_TOKEN`(워크플로우에 자동 주입, 만료도 자동 관리됨)으로 푸시
권한을 받는다 — Docker Hub 등 외부 레지스트리 계정/토큰을 새로 관리할 필요가 없다.

## `docker compose` 구성 (`deploy/docker-compose.dev.yml`, 저장소에 버전 관리)
```yaml
services:
  mysql:
    image: mysql:8.0
    restart: always
    env_file: .env
    environment:
      MYSQL_DATABASE: gone
    volumes:
      - mysql-data:/var/lib/mysql
    ports:
      - "3306:3306"

  redis:
    image: redis:7
    restart: always
    volumes:
      - redis-data:/data
    ports:
      - "6379:6379"

  app:
    image: ghcr.io/gbsw-remake/gone-server-v1:dev
    restart: always
    depends_on:
      - mysql
      - redis
    env_file: .env
    ports:
      - "9091:9091"

volumes:
  mysql-data:
  redis-data:
```
이 파일 자체는 시크릿 값을 담지 않는다(`env_file: .env`로 값만 별도 파일에서 읽어온다).
저장소에 커밋되고, 배포할 때마다 최신 버전을 EC2로 scp해서 덮어쓴다 — 서비스 정의(포트,
볼륨, 환경변수 이름 등)가 바뀌면 다음 배포에 자동 반영된다.

`.env`는 저장소에 없고 `deploy-dev` job이 매 배포마다 GitHub Secrets 값으로 새로 생성한다
(아래 "GitHub Actions 워크플로우" 참고). 생성되는 내용:
```
MYSQL_ROOT_PASSWORD=<GitHub Secret: MYSQL_ROOT_PASSWORD>
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/gone?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=<GitHub Secret: MYSQL_ROOT_PASSWORD>
SPRING_DATA_REDIS_HOST=redis
SPRING_DATA_REDIS_PORT=6379
JWT_SECRET=<GitHub Secret: JWT_SECRET>
JWT_ACCESS_TOKEN_EXPIRATION=1800000
JWT_REFRESH_TOKEN_EXPIRATION=1209600000
R2_ACCOUNT_ID=<GitHub Secret: R2_ACCOUNT_ID>
R2_ACCESS_KEY=<GitHub Secret: R2_ACCESS_KEY>
R2_SECRET_KEY=<GitHub Secret: R2_SECRET_KEY>
R2_BUCKET=<GitHub Secret: R2_BUCKET>
R2_ENDPOINT=<GitHub Secret: R2_ENDPOINT>
NEIS_API_KEY=<GitHub Secret: NEIS_API_KEY>
NEIS_ATPT_OFCDC_SC_CODE=<GitHub Secret: NEIS_ATPT_OFCDC_SC_CODE>
NEIS_SD_SCHUL_CODE=<GitHub Secret: NEIS_SD_SCHUL_CODE>
```
- `SPRING_DATASOURCE_PASSWORD`는 별도 시크릿이 아니라 `MYSQL_ROOT_PASSWORD`와 같은 값을
  그대로 재사용한다(`app`이 `root` 계정으로 접속하므로 같은 비밀번호여야 한다) — 시크릿을
  중복 등록하지 않는다.
- `JWT_ACCESS_TOKEN_EXPIRATION`/`JWT_REFRESH_TOKEN_EXPIRATION`/`SPRING_DATA_REDIS_PORT`는
  민감 정보가 아니라 워크플로우 스크립트에 고정값으로 직접 써서(`application.yml`의
  기존 값과 동일), GitHub Secrets 개수를 늘리지 않는다.
- `SPRING_DATASOURCE_URL`/`SPRING_DATA_REDIS_HOST`가 `mysql`/`redis`(컨테이너 이름)를
  가리키는 이유: 같은 `docker compose` 네트워크 안에서는 서비스 이름이 곧 DNS 호스트명이라
  `localhost`가 아니라 이 값을 써야 한다. `spring.profiles.active=dev`는 그대로 유지 —
  이 값들은 `application-dev.yml`의 같은 프로퍼티를 환경변수로 덮어쓸 뿐이라(Spring Boot
  relaxed binding, CI의 R2/NEIS 더미 값 주입과 동일한 방식), 프로필을 새로 만들 필요가
  없다.

> ⚠️ **`MYSQL_ROOT_PASSWORD`를 바꿔도 이미 초기화된 DB의 실제 비밀번호는 바뀌지 않는다.**
> 이 환경변수는 MySQL 컨테이너가 **최초 기동**할 때만(빈 볼륨일 때) 적용된다. GitHub
> Secrets에서 이 값을 바꾸고 재배포해도, `mysql` 컨테이너는 매 배포마다 재시작되지 않으므로
> (위 "아키텍처" 참고) 실제로는 아무 효과가 없고 오히려 `app`이 새 값으로 접속을 시도하다
> 인증 실패를 일으킨다. 비밀번호를 실제로 바꾸려면 `mysql` 컨테이너 안에서 `ALTER USER`로
> 직접 변경하거나 볼륨을 초기화해야 한다 — 이 절차는 이번 범위에서 다루지 않고, 필요 시
> 별도로 진행한다.

## GitHub Actions 워크플로우 (`ci.yml` 수정)
- `build-and-test` job: 기존 그대로. jar 산출물을 다음 job이 쓸 수 있게
  `actions/upload-artifact`로 `build/libs/*.jar` 업로드 스텝만 추가.
- `build-and-push-image` job(신규): `needs: build-and-test`,
  `if: github.event_name == 'push' && github.ref == 'refs/heads/dev'`. jar 아티팩트
  다운로드 → `docker/login-action@v3`(`registry: ghcr.io`, `username:
  ${{ github.actor }}`, `password: ${{ secrets.GITHUB_TOKEN }}`) → `docker/build-push-
  action@v6`으로 빌드/푸시. job에 `permissions: packages: write` 추가(GHCR 푸시에 필요).
- `deploy-dev` job(신규): `needs: build-and-push-image`,
  `concurrency: { group: deploy-dev, cancel-in-progress: false }`(연속 push가 배포를
  겹쳐 실행하지 않고 순서대로 처리).
  1. `deploy/docker-compose.dev.yml`을 `appleboy/scp-action@v0.1.7`로 EC2의
     `/opt/gone/dev/docker-compose.dev.yml`에 전송(파일명을 바꾸지 않는다 — 이후 명령은
     `docker compose -f docker-compose.dev.yml`로 이 파일을 명시적으로 지정한다).
  2. `appleboy/ssh-action@v1.0.3`로 EC2에 접속해 아래 스크립트 실행. 이 액션의 `envs:`
     옵션으로 job의 `env:` 블록에 매핑해둔 시크릿들을 원격 셸의 환경변수로 전달한다(값이
     GitHub Actions 콘솔 로그에는 등장하지 않고, SSH 세션 안에서만 존재):
     ```yaml
     env:
       MYSQL_ROOT_PASSWORD: ${{ secrets.MYSQL_ROOT_PASSWORD }}
       JWT_SECRET: ${{ secrets.JWT_SECRET }}
       R2_ACCOUNT_ID: ${{ secrets.R2_ACCOUNT_ID }}
       R2_ACCESS_KEY: ${{ secrets.R2_ACCESS_KEY }}
       R2_SECRET_KEY: ${{ secrets.R2_SECRET_KEY }}
       R2_BUCKET: ${{ secrets.R2_BUCKET }}
       R2_ENDPOINT: ${{ secrets.R2_ENDPOINT }}
       NEIS_API_KEY: ${{ secrets.NEIS_API_KEY }}
       NEIS_ATPT_OFCDC_SC_CODE: ${{ secrets.NEIS_ATPT_OFCDC_SC_CODE }}
       NEIS_SD_SCHUL_CODE: ${{ secrets.NEIS_SD_SCHUL_CODE }}
     steps:
       - uses: appleboy/ssh-action@v1.0.3
         with:
           host: ${{ secrets.DEV_EC2_HOST }}
           username: ${{ secrets.DEV_EC2_USER }}
           key: ${{ secrets.DEV_EC2_SSH_KEY }}
           envs: MYSQL_ROOT_PASSWORD,JWT_SECRET,R2_ACCOUNT_ID,R2_ACCESS_KEY,R2_SECRET_KEY,R2_BUCKET,R2_ENDPOINT,NEIS_API_KEY,NEIS_ATPT_OFCDC_SC_CODE,NEIS_SD_SCHUL_CODE
           script: |
             set -e
             cd /opt/gone/dev
             cat > .env <<ENVEOF
             MYSQL_ROOT_PASSWORD=$MYSQL_ROOT_PASSWORD
             SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/gone?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
             SPRING_DATASOURCE_USERNAME=root
             SPRING_DATASOURCE_PASSWORD=$MYSQL_ROOT_PASSWORD
             SPRING_DATA_REDIS_HOST=redis
             SPRING_DATA_REDIS_PORT=6379
             JWT_SECRET=$JWT_SECRET
             JWT_ACCESS_TOKEN_EXPIRATION=1800000
             JWT_REFRESH_TOKEN_EXPIRATION=1209600000
             R2_ACCOUNT_ID=$R2_ACCOUNT_ID
             R2_ACCESS_KEY=$R2_ACCESS_KEY
             R2_SECRET_KEY=$R2_SECRET_KEY
             R2_BUCKET=$R2_BUCKET
             R2_ENDPOINT=$R2_ENDPOINT
             NEIS_API_KEY=$NEIS_API_KEY
             NEIS_ATPT_OFCDC_SC_CODE=$NEIS_ATPT_OFCDC_SC_CODE
             NEIS_SD_SCHUL_CODE=$NEIS_SD_SCHUL_CODE
             ENVEOF
             chmod 600 .env
             docker compose -f docker-compose.dev.yml pull app
             docker compose -f docker-compose.dev.yml up -d app
     ```
  3. `curl` 재시도로 헬스체크(최대 60초, `45-chore-newman-e2e.md`에서 이미 제안한
     "TCP/포트 응답 확인" 방식과 동일 근거 — 이 프로젝트에 actuator가 없어서다).
  4. 성공/실패 여부와 무관하게 Discord 알림.

## Discord 알림
**dev 배포 전용 웹훅을 새로 만든다.** 기존 `DISCORD_CI_WEBHOOK`(체크스타일/빌드 실패 알림)
과는 별개 채널/웹훅으로, 배포 성공/실패만 알린다 — 시크릿 이름 `DISCORD_DEV_WEBHOOK`.
알림 포맷(제목/커밋/소요시간, 성공 초록/실패 빨강 embed)은 기존 `ci.yml` 알림 스텝과
동일한 구조를 그대로 재사용한다.

## 필요한 GitHub Secrets
| 이름 | 용도 |
|---|---|
| `DEV_EC2_HOST` | dev EC2 접속 주소 |
| `DEV_EC2_SSH_KEY` | 배포 전용 SSH 개인키(PEM) |
| `DEV_EC2_USER` | 배포 전용 계정명(`deploy`) |
| `DISCORD_DEV_WEBHOOK` | dev 배포 전용 Discord 웹훅(신규 생성) |
| `MYSQL_ROOT_PASSWORD` | MySQL root 비밀번호(app 접속 비밀번호와 동일하게 재사용) |
| `JWT_SECRET` | JWT 서명 시크릿 |
| `R2_ACCOUNT_ID` / `R2_ACCESS_KEY` / `R2_SECRET_KEY` / `R2_BUCKET` / `R2_ENDPOINT` | R2 연동 정보 |
| `NEIS_API_KEY` / `NEIS_ATPT_OFCDC_SC_CODE` / `NEIS_SD_SCHUL_CODE` | NEIS 연동 정보 |

`GITHUB_TOKEN`(GHCR 푸시용)은 워크플로우 실행 시 자동 주입되므로 별도로 등록하지 않는다.

## EC2 사전 준비 (최초 1회, 보스가 직접 수행 — Claude는 SSH 자격증명을 다루지 않는다)
1. Docker Engine + Docker Compose plugin 설치.
2. `deploy` 시스템 계정 생성, `docker` 그룹에 추가(그룹 멤버는 `sudo` 없이 `docker`
   명령을 실행할 수 있다 — 이 EC2가 dev 전용이라 감수 가능한 트레이드오프로 채택).
3. GitHub에서 `read:packages` 권한을 가진 Personal Access Token(classic)을 발급하고,
   EC2에서 `deploy` 계정으로 `docker login ghcr.io`를 1회 실행해 자격증명을 저장한다
   (`~/.docker/config.json`에 저장되고, 이후 재로그인 불필요 — GitHub Secrets로 관리하지
   않는 이유: 이 값은 EC2 로컬 상태일 뿐 GitHub Actions 워크플로우가 참조하지 않는다).
4. `deploy` 계정의 `~/.ssh/authorized_keys`에 배포 전용 SSH 공개키 등록(개인키는
   `DEV_EC2_SSH_KEY`로 GitHub Secrets에 등록).
5. `/opt/gone/dev/` 디렉토리 생성(소유자 `deploy`). `.env`는 미리 만들어두지 않는다 —
   위 GitHub Secrets를 전부 등록한 뒤 `dev` 브랜치에 첫 push를 하면 `deploy-dev` job이
   최초의 `.env`를 만들고 `docker compose -f docker-compose.dev.yml up -d app`까지
   실행한다(단, `mysql`/`redis`는 `app`이 아니므로 이 job이 자동으로 띄워주지 않는다 —
   아래 6번 참고).
6. `mysql`/`redis`는 `deploy-dev` job이 건드리지 않으므로(위 "아키텍처" 참고), 5번 이후
   `docker compose -f docker-compose.dev.yml up -d mysql redis`를 1회 수동 실행해
   띄워둔다(이때 `.env`가 이미 있어야 하므로, 5번의 첫 자동 배포가 끝난 뒤 실행한다).

## 영향 받는 기존 코드/설정
- `Dockerfile`(신규, 저장소 루트)
- `deploy/docker-compose.dev.yml`(신규)
- `.github/workflows/ci.yml`: `build-and-test`에 아티팩트 업로드 스텝 추가,
  `build-and-push-image`/`deploy-dev` job 신규 추가. 기존 `checkstyle`/`build-and-test`
  동작 자체는 바뀌지 않는다.
- 애플리케이션 코드 변경 없음(신규 엔드포인트 없음 — `api-design.md` 6원칙 검토는 해당
  없음).

## 리스크 및 고려사항
- **애플리케이션 시크릿이 GitHub Actions 러너를 경유한다.** 배포 SSH 키만 노출되던
  이전 설계보다 노출 표면이 넓어진다 — `dev` 브랜치에 `push` 권한이 있거나 워크플로우
  파일을 수정할 수 있는 사람은 이 시크릿들에 접근 가능한 워크플로우를 만들 수 있다.
  아래로 완화한다: (1) `deploy-dev`는 `dev` push에서만 실행(PR에서 실행 안 됨), (2)
  스크립트에 `set -x` 등 디버그 출력 금지, (3) third-party 액션은 메이저 버전 태그로
  고정해 예상 밖 동작을 막는다. 완전히 없앨 수는 없는 리스크이며, 이 트레이드오프(회전
  편의성 vs 노출 표면)는 보스가 이미 확인하고 이 방식을 선택했다.
- **자동 롤백 없음**: 헬스체크 실패 시 Discord로만 알리고, 이전 이미지로 되돌리는 동작은
  하지 않는다. dev 환경은 실패해도 서비스 영향이 없어 이번 범위에서는 넣지 않는다.
- **`deploy` 계정의 `docker` 그룹 권한은 사실상 루트와 동급이다**(Docker 소켓 접근 권한의
  잘 알려진 특성). dev 전용 EC2이고 배포 키가 이 계정에만 한정돼 있어 감수하기로 했다 —
  운영 환경에서는 이 트레이드오프를 그대로 가져가지 않는다(운영 이슈에서 별도로 다룸).
- **GHCR 이미지가 public이면 코드가 노출된다** — 저장소가 private이므로 GHCR 패키지도
  기본적으로 private으로 생성된다(GitHub 기본 동작). 배포 전 실제로 private 상태인지
  1회 확인한다.
- **third-party GitHub Action(`docker/login-action`, `docker/build-push-action`,
  `appleboy/ssh-action`, `appleboy/scp-action`)은 메이저 버전 태그로 고정**한다 —
  `@master`처럼 움직이는 참조는 쓰지 않는다.
- **`MYSQL_ROOT_PASSWORD` 회전 시 주의사항** — 위 "docker compose 구성" 절의 경고 참고.

## 완료 조건 (Definition of Done)
- `dev` 브랜치에 push하면 GHCR에 새 이미지가 푸시되고, EC2의 `app` 컨테이너가 자동으로
  그 이미지와 최신 `.env`로 갱신된다.
- 배포 성공/실패 모두 `DISCORD_DEV_WEBHOOK`으로 알림이 온다.
- `mysql`/`redis` 컨테이너는 배포 중 재시작되지 않고 데이터가 유지된다.
- 어떤 시크릿 값도 저장소/문서/커밋/GitHub Actions 로그에 평문으로 남지 않는다(값
  자체는 GitHub Secrets → 워크플로우 환경변수 → SSH 세션 안에서만 존재).
