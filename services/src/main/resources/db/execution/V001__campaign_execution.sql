CREATE TABLE execution_schema(version integer PRIMARY KEY,checksum varchar(64) NOT NULL);
CREATE TABLE execution_state(key varchar(80) PRIMARY KEY,value bigint NOT NULL);
CREATE TABLE execution_tasks(id varchar(80) PRIMARY KEY,customer_id varchar(40) NOT NULL,campaign_id varchar(80) NOT NULL,due timestamptz NOT NULL DEFAULT now(),state varchar(32) NOT NULL DEFAULT 'QUEUED',reason varchar(160));
CREATE INDEX execution_due ON execution_tasks(due,id);
CREATE TABLE execution_packages(id varchar(80) PRIMARY KEY,customer_id varchar(40) NOT NULL,campaign_id varchar(80) NOT NULL,version bigint NOT NULL,fingerprint varchar(64) NOT NULL,status varchar(32) NOT NULL,file_version bigint NOT NULL DEFAULT 0,payload text NOT NULL,UNIQUE(customer_id,campaign_id));
CREATE INDEX execution_customer ON execution_packages(customer_id,id);
CREATE TABLE execution_history(sequence bigserial PRIMARY KEY,package_id varchar(80) NOT NULL,version bigint NOT NULL,payload text NOT NULL,recorded_at timestamptz NOT NULL DEFAULT now(),UNIQUE(package_id,version));
CREATE TABLE execution_dispatches(source_key varchar(80) PRIMARY KEY,package_id varchar(80) NOT NULL,customer_id varchar(40) NOT NULL,created_at timestamptz NOT NULL,exported_at timestamptz);
CREATE INDEX execution_frequency ON execution_dispatches(customer_id,created_at);
