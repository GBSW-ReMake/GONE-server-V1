-- 외출증(Outing) 테이블 생성 (#29 외출증 신청 API)
CREATE TABLE outing (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(10) NOT NULL,
    student_user_id BIGINT NOT NULL,
    teacher_user_id BIGINT NOT NULL,
    reason VARCHAR(200) NOT NULL,
    outing_date DATE NOT NULL,
    time_slot VARCHAR(20) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    status VARCHAR(20) NOT NULL,
    approved_at DATETIME NULL,
    rejected_reason VARCHAR(200) NULL,
    departed_at DATETIME NULL,
    returned_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (student_user_id) REFERENCES user(id),
    FOREIGN KEY (teacher_user_id) REFERENCES user(id),
    UNIQUE KEY uq_outing_code (code),
    KEY idx_outing_student_date (student_user_id, outing_date)
);
