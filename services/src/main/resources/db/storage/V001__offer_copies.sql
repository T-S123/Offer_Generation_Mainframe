CREATE TABLE copy_schema(version integer PRIMARY KEY,checksum varchar(64) NOT NULL);
CREATE TABLE offer_events(event_id varchar(160) PRIMARY KEY,offer_id varchar(160) NOT NULL,version bigint NOT NULL,source_sequence bigint NOT NULL UNIQUE,fingerprint varchar(64) NOT NULL,payload text NOT NULL,copied_at timestamptz NOT NULL DEFAULT now(),UNIQUE(offer_id,version));
CREATE TABLE current_offers(id varchar(160) PRIMARY KEY,customer_id varchar(160) NOT NULL,version bigint NOT NULL,event_id varchar(160) NOT NULL REFERENCES offer_events(event_id),payload text NOT NULL,copied_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX current_offers_customer ON current_offers(customer_id,id);
CREATE TABLE copy_state(key varchar(80) PRIMARY KEY,value bigint NOT NULL);
CREATE TABLE rejected_events(id varchar(160) PRIMARY KEY,fingerprint varchar(64) NOT NULL,reason varchar(120) NOT NULL,received_at timestamptz NOT NULL DEFAULT now());
