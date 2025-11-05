-- Basic schema for Sprint 1

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    display_name VARCHAR(255),
    locale VARCHAR(20),
    home_city VARCHAR(255),
    currency VARCHAR(10),
    preferences JSONB,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    destination VARCHAR(255) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    total_days INT NOT NULL,
    budget_amount NUMERIC(12,2),
    budget_currency VARCHAR(10),
    tags TEXT[],
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS days (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id UUID NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    "index" INT NOT NULL,
    date DATE NOT NULL,
    summary TEXT,
    accommodation JSONB,
    transport JSONB,
    notes TEXT
);

CREATE TABLE IF NOT EXISTS activities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    day_id UUID NOT NULL REFERENCES days(id) ON DELETE CASCADE,
    type VARCHAR(20) NOT NULL,
    title VARCHAR(255) NOT NULL,
    poi JSONB,
    start_time TIME,
    end_time TIME,
    transport JSONB,
    estimated_cost_amount NUMERIC(12,2),
    estimated_cost_currency VARCHAR(10),
    description TEXT,
    images TEXT[]
);

CREATE TABLE IF NOT EXISTS expenses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id UUID NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    day_id UUID REFERENCES days(id) ON DELETE SET NULL,
    category VARCHAR(30) NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    time TIMESTAMP,
    note TEXT,
    source VARCHAR(20) NOT NULL DEFAULT 'user'
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_plans_owner ON plans(owner_id);
CREATE INDEX IF NOT EXISTS idx_days_plan ON days(plan_id);
CREATE INDEX IF NOT EXISTS idx_activities_day ON activities(day_id);
CREATE INDEX IF NOT EXISTS idx_expenses_plan ON expenses(plan_id);