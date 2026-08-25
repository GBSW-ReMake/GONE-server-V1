-- 외출증 위치 핑 시계열 테이블 (#97)
CREATE TABLE outing_location (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    outing_id BIGINT NOT NULL,
    latitude DOUBLE NOT NULL,
    longitude DOUBLE NOT NULL,
    recorded_at DATETIME NOT NULL,
    FOREIGN KEY (outing_id) REFERENCES outing(id),
    KEY idx_outing_location_outing_recorded (outing_id, recorded_at)
);
