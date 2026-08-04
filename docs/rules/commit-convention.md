# 커밋 규칙

- 형식: `<type>(<scope>): <한글 설명>` (scope 생략 가능: `<type>: <한글 설명>`)
- `type`: `feat`, `fix`, `refactor`, `chore`, `test`, `docs`
- `scope`: 변경이 속한 도메인명 (`auth`, `user`, `file`, `gbsw` 등)
- 설명은 한글, 현재형("~구현", "~수정", "~추가")으로 간결하게 작성한다.
- 작은 task 단위로 자주 커밋한다. 한 기능을 한 번에 몰아서 커밋하지 않는다.
  - 예: 엔티티/마이그레이션 → 서비스 로직 → 컨트롤러 → 테스트 순서로 분리
- 브랜치 네이밍은 [branch-workflow.md](./branch-workflow.md) 7단계를 따른다: `feat/#{이슈번호}-{slug}` / `fix/#{이슈번호}-{slug}`

## 예시
- `feat(auth): 로그인 시 JWT Access/Refresh 토큰 발급 구현`
- `feat(auth): Refresh Token Redis 저장 로직 추가`
- `fix: checkstyle 라인 길이 위반 수정`
- `test(auth): 로그인 실패 케이스 테스트코드 추가`
