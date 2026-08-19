-- 스쿨캠핑 신청/팀원 (#68)
CREATE TABLE school_camp_application (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    applicant_user_id BIGINT NOT NULL,
    teacher_user_id BIGINT NULL,
    teacher_name VARCHAR(50) NULL,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at DATETIME NULL,

    FOREIGN KEY (session_id) REFERENCES school_camp_session(id),
    FOREIGN KEY (applicant_user_id) REFERENCES user(id),
    FOREIGN KEY (teacher_user_id) REFERENCES user(id),
    KEY idx_application_session_applicant (session_id, applicant_user_id),
    KEY idx_application_applicant_applied (applicant_user_id, applied_at)
);

CREATE TABLE school_camp_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    application_id BIGINT NOT NULL,
    student_user_id BIGINT NULL,
    guest_name VARCHAR(50) NULL,
    is_applicant BOOLEAN NOT NULL,

    FOREIGN KEY (application_id) REFERENCES school_camp_application(id),
    FOREIGN KEY (student_user_id) REFERENCES user(id),
    KEY idx_member_application (application_id),
    KEY idx_member_student (student_user_id)
);
