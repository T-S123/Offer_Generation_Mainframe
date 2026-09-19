ALTER TABLE bureau_work ADD COLUMN response_sequence bigserial;
WITH ordered AS (SELECT id,row_number() OVER (ORDER BY created_at,id) AS ordinal FROM bureau_work)
UPDATE bureau_work w SET response_sequence=o.ordinal FROM ordered o WHERE w.id=o.id;
SELECT setval(pg_get_serial_sequence('bureau_work','response_sequence'),COALESCE(MAX(response_sequence),1),COUNT(*)>0) FROM bureau_work;
ALTER TABLE bureau_work ADD COLUMN response_projected boolean NOT NULL DEFAULT false;
ALTER TABLE bureau_work ADD COLUMN response_retry_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE bureau_work ADD COLUMN response_error varchar(120);
CREATE INDEX bureau_response_pending ON bureau_work(response_retry_at,response_sequence) WHERE result IS NOT NULL AND NOT response_projected;
CREATE INDEX bureau_response_source ON bureau_work(run_id,qualification_id,response_sequence DESC) WHERE result IS NOT NULL;
CREATE TABLE decision_responses (
    id varchar(80) PRIMARY KEY,
    run_id varchar(80) NOT NULL,
    qualification_id varchar(80) NOT NULL,
    customer_id varchar(80) NOT NULL,
    version bigint NOT NULL,
    work_sequence bigint NOT NULL,
    eligible boolean NOT NULL,
    next_check timestamptz NOT NULL,
    payload text NOT NULL,
    UNIQUE(run_id,qualification_id)
);
CREATE INDEX decision_response_due ON decision_responses(next_check,id) WHERE eligible;
CREATE INDEX decision_response_customer ON decision_responses(customer_id,id) WHERE eligible;
CREATE TABLE decision_response_history (
    response_id varchar(80) NOT NULL REFERENCES decision_responses(id),
    version bigint NOT NULL,
    event_id varchar(80) NOT NULL UNIQUE REFERENCES bureau_outbox(id),
    payload text NOT NULL,
    PRIMARY KEY(response_id,version)
);
ALTER TABLE bureau_outbox ADD COLUMN attempts integer NOT NULL DEFAULT 0;
ALTER TABLE bureau_outbox ADD COLUMN last_attempt_at timestamptz;
ALTER TABLE bureau_outbox ADD COLUMN last_error varchar(120);
ALTER TABLE bureau_outbox ADD COLUMN replay_count integer NOT NULL DEFAULT 0;
