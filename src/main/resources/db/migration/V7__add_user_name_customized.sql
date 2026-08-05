-- 가입 시 자동 생성된 기본 닉네임을 사용자가 직접 바꿨는지 여부.
-- 클라이언트가 "아직 기본값이면 설정을 유도"할지 판단하는 데만 쓰인다(접근 제어 아님).
ALTER TABLE user ADD COLUMN name_customized BOOLEAN NOT NULL DEFAULT FALSE;
