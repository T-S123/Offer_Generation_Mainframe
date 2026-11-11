CREATE TABLE personalization_sessions(id varchar(80) PRIMARY KEY, customer_id varchar(80) NOT NULL, expires_at timestamptz NOT NULL, closed boolean NOT NULL DEFAULT false);
CREATE TABLE session_offers(id varchar(80) PRIMARY KEY, session_id varchar(80) NOT NULL REFERENCES personalization_sessions(id), customer_id varchar(80) NOT NULL, profile_version integer NOT NULL, approval_id varchar(80) NOT NULL, payload text NOT NULL, selection text);
CREATE INDEX session_offers_customer ON session_offers(customer_id,session_id);
