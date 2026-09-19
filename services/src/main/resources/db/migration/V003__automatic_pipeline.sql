CREATE TABLE IF NOT EXISTS customer_contacts(customer_id VARCHAR(40) PRIMARY KEY REFERENCES customers(id),payload CLOB NOT NULL);
CREATE TABLE IF NOT EXISTS customer_pipeline(customer_id VARCHAR(40) PRIMARY KEY REFERENCES customers(id),token VARCHAR(64) NOT NULL,state VARCHAR(32) NOT NULL,next_at BIGINT NOT NULL,payload CLOB NOT NULL);
CREATE INDEX IF NOT EXISTS pipeline_ready ON customer_pipeline(state,next_at);
