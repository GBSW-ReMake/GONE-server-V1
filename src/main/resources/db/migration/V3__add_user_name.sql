-- 서비스 내에서 사용하는 사용자 별명 (gbsw.name = 실명과는 별개)
ALTER TABLE user
    ADD COLUMN name VARCHAR(50) NOT NULL AFTER password_hash;
