-- AI-only additive schema. Existing customer, catalog, bureau and storage migrations are unchanged.
CREATE TABLE IF NOT EXISTS records(kind VARCHAR(40),id VARCHAR(160),version INT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(kind,id));
CREATE TABLE IF NOT EXISTS artifacts(id VARCHAR(160) PRIMARY KEY,hash VARCHAR(64) NOT NULL,payload TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS budgets(id VARCHAR(160) PRIMARY KEY,payload TEXT NOT NULL,evaluations INT NOT NULL,calls INT NOT NULL,tokens BIGINT NOT NULL,cost DECIMAL(20,8) NOT NULL);
CREATE TABLE IF NOT EXISTS reservations(workflow_id VARCHAR(160),id VARCHAR(160),hash VARCHAR(64) NOT NULL,PRIMARY KEY(workflow_id,id));
CREATE TABLE IF NOT EXISTS reservation_values(workflow_id VARCHAR(160),id VARCHAR(160),tokens BIGINT NOT NULL,cost DECIMAL(20,8) NOT NULL,PRIMARY KEY(workflow_id,id));
CREATE TABLE IF NOT EXISTS settlements(workflow_id VARCHAR(160),id VARCHAR(160),tokens BIGINT NOT NULL,cost DECIMAL(20,8) NOT NULL,PRIMARY KEY(workflow_id,id));
