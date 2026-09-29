-- Offline H2 reset, not PostgreSQL. Run only on the working COPY after backup.
-- All application processes must be stopped.
-- Keeps catalog offers with published rules, campaigns and global policy.
-- Do not use RunScript -continueOnError: failure must stop before COMMIT.
SET AUTOCOMMIT FALSE;
DELETE FROM marketing_reservations;
DELETE FROM marketing_results;
DELETE FROM marketing_runs;
DELETE FROM customer_contacts;
DELETE FROM customer_pipeline;
DELETE FROM members;
DELETE FROM populations;
DELETE FROM imports;
DELETE FROM jobs;
DELETE FROM customers;
DELETE FROM marketing_revisions WHERE kind NOT IN ('OFFER', 'CAMPAIGN', 'POLICY');
COMMIT;
SELECT COUNT(*) AS remaining_customers FROM customers;
SELECT kind, COUNT(*) AS retained_revisions
FROM marketing_revisions GROUP BY kind ORDER BY kind;
