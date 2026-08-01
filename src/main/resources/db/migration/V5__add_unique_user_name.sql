-- 별명(name) 중복 불가 정책 적용
ALTER TABLE user
    ADD CONSTRAINT uq_user_name UNIQUE (name);
