CREATE TABLE IF NOT EXISTS bureau_mapping (
 customer_id varchar(80) PRIMARY KEY, subject_id varchar(80) NOT NULL UNIQUE);
CREATE TABLE IF NOT EXISTS bureau_profiles (
 subject_id varchar(80) REFERENCES bureau_mapping(subject_id), version int NOT NULL,
 payload text NOT NULL, PRIMARY KEY(subject_id,version));
CREATE TABLE IF NOT EXISTS credit_assessments (
 request_id varchar(80) PRIMARY KEY, fingerprint varchar(64) NOT NULL, payload text NOT NULL);
CREATE TABLE IF NOT EXISTS bureau_batches (
 id varchar(80) PRIMARY KEY, fingerprint varchar(64) NOT NULL, payload text NOT NULL,
 run_index int NOT NULL DEFAULT 0, cursor_ordinal int NOT NULL DEFAULT -1,
 expanded boolean NOT NULL DEFAULT false, error text, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS bureau_work (
 id varchar(80) PRIMARY KEY, fingerprint varchar(64) NOT NULL,
 run_id varchar(80) NOT NULL, risk_id varchar(80) NOT NULL, qualification_id varchar(80) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PENDING', attempts int NOT NULL DEFAULT 0,
 lease_token varchar(80), lease_until timestamptz, available_at timestamptz NOT NULL DEFAULT now(),
 result text, created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz);
CREATE INDEX IF NOT EXISTS bureau_queue_ready ON bureau_work(available_at,id) WHERE status IN ('PENDING','RETRY','PROCESSING');
CREATE INDEX IF NOT EXISTS bureau_work_risk ON bureau_work(risk_id,id);
CREATE TABLE IF NOT EXISTS bureau_batch_items (
 batch_id varchar(80) REFERENCES bureau_batches(id), request_id varchar(80) REFERENCES bureau_work(id),
 PRIMARY KEY(batch_id,request_id));
CREATE TABLE IF NOT EXISTS bureau_outbox (
 id varchar(80) PRIMARY KEY, topic varchar(120) NOT NULL, event_key varchar(80) NOT NULL, payload text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), sent_at timestamptz);
CREATE INDEX IF NOT EXISTS bureau_outbox_pending ON bureau_outbox(created_at,id) WHERE sent_at IS NULL;
CREATE TABLE IF NOT EXISTS bureau_inbox (
 event_id varchar(180) PRIMARY KEY, fingerprint varchar(64) NOT NULL, received_at timestamptz NOT NULL DEFAULT now());
