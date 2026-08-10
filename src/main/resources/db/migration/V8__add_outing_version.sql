-- 외출증(Outing) 낙관적 락 버전 컬럼 추가 (#42 MISSED 스케줄러의 동시 갱신 충돌 감지)
ALTER TABLE outing
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
