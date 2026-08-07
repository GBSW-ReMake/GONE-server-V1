# 코드 스타일 규칙 (Google Java Style)

- `build.gradle`의 checkstyle 플러그인이 공식 `google_checks.xml`을 그대로 사용하며 `maxWarnings = 0`이다.
  즉 경고가 하나라도 있으면 빌드가 실패한다.
- 커밋 전 반드시 `./gradlew checkstyleMain` (테스트 코드 변경 시 `./gradlew checkstyleTest`)으로 통과를 확인한다.
- 주요 규칙:
  - 들여쓰기 2칸, 탭 사용 금지
  - 한 줄 100자 제한
  - import는 static import와 일반 import를 각각 알파벳 순으로 정렬 (와일드카드 import 금지)
  - 중괄호는 K&R 스타일 (Egyptian brackets), 항상 사용 (한 줄짜리 if라도 생략 금지)
- 주석/Javadoc:
  - Javadoc은 공개 API(서비스/컨트롤러의 public 메서드, 클래스)에만 작성
  - 코드만 읽어도 알 수 있는 내용은 주석으로 남기지 않는다 (WHY만, WHAT은 지양)
- 기존 도메인 패키지 구조(`controller / dto / service / exception / entity / repository` /
  이미 쓰이고 있는 `enums` / `utils` 등)를 기본으로 따른다.
  - **단, 컨벤션이 유지보수성보다 우선하지 않는다.** `type`처럼 이 목록에 없는 폴더가
    정말 필요하면(기존 레이어 중 어디에도 자연스럽게 안 맞는 경우) 임의로 아무 데나
    넣기보다, 기획서/PR에서 "왜 새 폴더가 필요한지"를 명시해 검토받고 추가한다 — 목록에
    없다고 억지로 기존 폴더에 욱여넣지 않는다.
