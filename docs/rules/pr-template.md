# PR 템플릿 사용 규칙

- `.github/PULL_REQUEST_TEMPLATE.md`를 사용하고, 항목을 임의로 삭제하지 않는다.
- `관련 이슈`: `closes #{이슈번호}` 형식으로 명시한다.
  - ⚠️ 이 저장소의 default 브랜치는 `main`이고 feature PR은 `dev`로 머지되므로, GitHub의
    "closes #N" 자동 종료는 **이 시점에 발동하지 않는다** (자동 종료는 default 브랜치로
    머지될 때만 동작). `dev → main` PR에서 다시 자동 종료되길 기대하지 말고, feature PR을
    `dev`에 머지한 직후 관련 이슈를 `gh issue close {번호}`로 직접 닫는다.
- `작업 내용` / `변경 사항 상세`: 기획서(`docs/domain/{도메인명}/{이슈번호}-{도메인명}-{간략한 제목}.md`)와 실제 구현이
  다른 부분이 있다면 반드시 명시한다.
- `확인 사항` 체크리스트는 실제로 로컬에서 실행/확인한 항목만 체크한다.
  - `./gradlew build`
  - `./gradlew checkstyleMain`
  - CI(checkstyle, build-and-test) 통과
  - (해당 시) Notion 기능정의서/기획 문서 반영
  - (해당 시) 관련 도메인 라벨 지정
- PR은 [branch-workflow.md](./branch-workflow.md) 14~16단계(작업 완료 보고 → Postman 컬렉션
  정리 → 보스의 확인)를 거친 뒤에만 생성한다.
