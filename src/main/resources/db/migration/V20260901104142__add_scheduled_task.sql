-- 범용 이벤트 스케줄 테이블 (#120). 특정 도메인 전용이 아니라 여러 도메인이 공유하는
-- 공용 인프라 테이블 — task_type 컬럼으로 도메인을 구분해 같은 폴링 루프를 공유한다.
CREATE TABLE scheduled_task (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_type         VARCHAR(50) NOT NULL,
    reference_id      BIGINT NOT NULL,
    scheduled_at      DATETIME NOT NULL,
    interval_seconds  INT NULL,
    end_at            DATETIME NULL,
    next_attempt_at   DATETIME NOT NULL,
    last_executed_at  DATETIME NULL,
    last_attempted_at DATETIME NULL,
    failure_count     INT NOT NULL DEFAULT 0,
    last_error        VARCHAR(500) NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uq_scheduled_task_type_ref (task_type, reference_id),
    KEY idx_scheduled_task_due (status, next_attempt_at)
);
