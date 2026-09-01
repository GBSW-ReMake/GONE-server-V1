CREATE TABLE conduct_request
(
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  requester_user_id BIGINT       NOT NULL,
  student_user_id   BIGINT       NOT NULL,
  assignee_user_id  BIGINT       NOT NULL,
  category_id       BIGINT       NOT NULL,
  detail            VARCHAR(500) NULL,
  status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
  conduct_record_id BIGINT       NULL,
  version           BIGINT       NOT NULL DEFAULT 0,
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  canceled_at       DATETIME     NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_conduct_request_requester FOREIGN KEY (requester_user_id) REFERENCES user (id),
  CONSTRAINT fk_conduct_request_student FOREIGN KEY (student_user_id) REFERENCES user (id),
  CONSTRAINT fk_conduct_request_assignee FOREIGN KEY (assignee_user_id) REFERENCES user (id),
  CONSTRAINT fk_conduct_request_category FOREIGN KEY (category_id) REFERENCES conduct_category (id),
  CONSTRAINT fk_conduct_request_record FOREIGN KEY (conduct_record_id) REFERENCES conduct_record (id),
  INDEX idx_conduct_request_requester_created (requester_user_id, created_at),
  INDEX idx_conduct_request_assignee_status (assignee_user_id, status)
);
