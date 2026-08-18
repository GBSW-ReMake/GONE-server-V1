-- 탈퇴/졸업 사용자 상태 관리 (#35)
ALTER TABLE user
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' AFTER created_at;

-- 지금까지 이 컬럼을 읽는 코드가 없어(전부 NULL) 이름을 바꿔도 기존 데이터에 영향이 없다.
ALTER TABLE user
    RENAME COLUMN deleted_at TO status_changed_at;

INSERT INTO role (code, name) VALUES
    ('GRADUATE', '졸업생');
