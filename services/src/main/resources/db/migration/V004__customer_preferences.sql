CREATE TABLE IF NOT EXISTS customer_price_preferences (
    customer_id VARCHAR(40) PRIMARY KEY REFERENCES customers(id) ON DELETE CASCADE,
    profile_version INT NOT NULL,
    model_version VARCHAR(40) NOT NULL,
    fee_sensitivity VARCHAR(30) NOT NULL
);
