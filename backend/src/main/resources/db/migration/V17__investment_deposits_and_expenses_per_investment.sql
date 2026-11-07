-- Investments that are run like a business (e.g. an Airbnb unit) and topped up over time (e.g. a
-- money market fund):
--   * deposits: more money put into an active investment after it was first funded;
--   * expenses can be charged to an investment, so its profit is its income less its running costs.

ALTER TABLE investment.investments
    ADD COLUMN deposited_minor BIGINT NOT NULL DEFAULT 0 CHECK (deposited_minor >= 0);

ALTER TABLE investment.transactions DROP CONSTRAINT transactions_kind_check;
ALTER TABLE investment.transactions
    ADD CONSTRAINT transactions_kind_check CHECK (kind IN ('FUNDING', 'DEPOSIT', 'INCOME', 'WITHDRAWAL', 'CLOSURE'));

-- No foreign key: investments belong to another module. The application checks the investment
-- exists and is active when the expense is recorded.
ALTER TABLE expense.expenses ADD COLUMN investment_id UUID;
CREATE INDEX idx_expenses_tenant_investment ON expense.expenses (tenant_id, investment_id)
    WHERE investment_id IS NOT NULL;
