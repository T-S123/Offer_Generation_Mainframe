CREATE TABLE storage_current(id varchar(80) PRIMARY KEY,version bigint NOT NULL,customer_id varchar(80) NOT NULL,phase integer NOT NULL,response_version bigint NOT NULL,payload text NOT NULL);
CREATE INDEX storage_customer ON storage_current(customer_id,id);
CREATE TABLE storage_events(sequence bigserial PRIMARY KEY,event_id varchar(80) NOT NULL UNIQUE,offer_id varchar(80) NOT NULL,version bigint NOT NULL,payload text NOT NULL,sent_at timestamptz,attempts integer NOT NULL DEFAULT 0,last_error varchar(120),UNIQUE(offer_id,version));
CREATE INDEX storage_outbox_pending ON storage_events(sequence) WHERE sent_at IS NULL;
CREATE INDEX storage_event_offer ON storage_events(offer_id,version);
CREATE TABLE storage_seen(origin_key varchar(160) PRIMARY KEY);
