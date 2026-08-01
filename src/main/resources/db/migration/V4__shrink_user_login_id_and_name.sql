-- login_id, name 정책상 최대 20자로 제한
ALTER TABLE user
    MODIFY COLUMN login_id VARCHAR(20) NOT NULL,
    MODIFY COLUMN name VARCHAR(20) NOT NULL;
