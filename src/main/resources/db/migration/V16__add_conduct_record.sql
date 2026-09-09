CREATE TABLE conduct_record
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    student_user_id      BIGINT       NOT NULL,
    teacher_user_id      BIGINT       NOT NULL,
    category_id          BIGINT       NOT NULL,
    type                 VARCHAR(20)  NOT NULL,
    points               INT          NOT NULL,
    detail               VARCHAR(500) NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    canceled_at          DATETIME     NULL,
    canceled_by_user_id  BIGINT       NULL,
    cancel_reason        VARCHAR(500) NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_conduct_record_student  FOREIGN KEY (student_user_id)     REFERENCES user (id),
    CONSTRAINT fk_conduct_record_teacher  FOREIGN KEY (teacher_user_id)     REFERENCES user (id),
    CONSTRAINT fk_conduct_record_category FOREIGN KEY (category_id)         REFERENCES conduct_category (id),
    CONSTRAINT fk_conduct_record_canceled_by FOREIGN KEY (canceled_by_user_id) REFERENCES user (id),
    INDEX idx_conduct_record_student_created  (student_user_id, created_at),
    INDEX idx_conduct_record_teacher_created  (teacher_user_id, created_at)
);
