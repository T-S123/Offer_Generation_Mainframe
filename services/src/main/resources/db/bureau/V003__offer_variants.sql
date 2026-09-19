CREATE TABLE offer_variant_qualifications (
  id varchar(80) PRIMARY KEY,
  fingerprint varchar(64) NOT NULL,
  response_id varchar(80) NOT NULL REFERENCES decision_responses(id),
  payload text NOT NULL
);
CREATE INDEX offer_variants_source ON offer_variant_qualifications(response_id);
