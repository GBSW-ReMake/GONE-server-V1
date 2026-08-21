-- 스쿨캠핑 "자리나면 알림받기" 대기 등록 (#83)
CREATE TABLE school_camp_waitlist (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_user_id BIGINT NOT NULL,
    month DATE NOT NULL,
    registered_at DATETIME NOT NULL,
    cancelled_at DATETIME NULL,

    FOREIGN KEY (student_user_id) REFERENCES user(id),
    CONSTRAINT uq_waitlist_student_month UNIQUE (student_user_id, month),
    KEY idx_waitlist_month_cancelled (month, cancelled_at)
);
