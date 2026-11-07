-- The funding transaction of an imported investment uses the method OPENING_BALANCE (V18), which
-- is longer than the original VARCHAR(10).
ALTER TABLE investment.transactions ALTER COLUMN method TYPE VARCHAR(20);
