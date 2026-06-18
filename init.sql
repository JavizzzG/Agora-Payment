CREATE TABLE IF NOT EXISTS subscriptions (
    id UUID PRIMARY KEY,
    stripe_subscription_id VARCHAR(255),
    stripe_customer_id VARCHAR(255),
    user_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'INCOMPLETE',
    product_id VARCHAR(255),
    price_id VARCHAR(255),
    current_period_start TIMESTAMPTZ,
    current_period_end TIMESTAMPTZ,
    cancel_at_period_end BOOLEAN DEFAULT FALSE,
    canceled_at TIMESTAMPTZ,
    trial_start TIMESTAMPTZ,
    trial_end TIMESTAMPTZ,
    currency VARCHAR(3),
    amount BIGINT,
    metadata TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_user_id ON subscriptions(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_subscriptions_stripe_subscription_id ON subscriptions(stripe_subscription_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_stripe_customer_id ON subscriptions(stripe_customer_id);
