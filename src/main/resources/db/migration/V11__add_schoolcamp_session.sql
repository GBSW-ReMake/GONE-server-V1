-- 스쿨캠핑 세션 (#67)
CREATE TABLE school_camp_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    camp_date DATE NOT NULL UNIQUE,
    taken_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
