CREATE TABLE IF NOT EXISTS marketing_revisions (
    kind VARCHAR(20) NOT NULL, id VARCHAR(60) NOT NULL, version INT NOT NULL,
    payload CLOB NOT NULL, PRIMARY KEY(kind,id,version)
);
CREATE TABLE IF NOT EXISTS marketing_runs (
    id VARCHAR(40) PRIMARY KEY, request_id VARCHAR(60) NOT NULL UNIQUE,
    created_at VARCHAR(40) NOT NULL, payload CLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS marketing_results (
    run_id VARCHAR(40) REFERENCES marketing_runs(id), ordinal INT NOT NULL,
    risk_id VARCHAR(40) NOT NULL, payload CLOB NOT NULL, PRIMARY KEY(run_id,ordinal)
);
CREATE INDEX IF NOT EXISTS marketing_risk ON marketing_results(risk_id);
CREATE TABLE IF NOT EXISTS marketing_reservations (
    id VARCHAR(40) PRIMARY KEY, run_id VARCHAR(40) REFERENCES marketing_runs(id),
    customer_id VARCHAR(40) REFERENCES customers(id), campaign_id VARCHAR(60) NOT NULL,
    payload CLOB NOT NULL, UNIQUE(run_id,customer_id,campaign_id)
);
