-- 출발/도착 보고 시점의 좌표를 증거로 남긴다 (#43)
ALTER TABLE outing
    ADD COLUMN departed_latitude DOUBLE NULL,
    ADD COLUMN departed_longitude DOUBLE NULL,
    ADD COLUMN returned_latitude DOUBLE NULL,
    ADD COLUMN returned_longitude DOUBLE NULL;
