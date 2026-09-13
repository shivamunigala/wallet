-- Demo users with fixed bearer tokens, so the burst script and the live probes
-- run against a fresh database with zero manual setup.
--
-- These tokens are public on purpose: the brief states auth sophistication is not
-- graded, and the service holds no real money. Do not copy this pattern anywhere real.

INSERT INTO users (external_id, bearer_token) VALUES
    ('alice',   'token-alice'),
    ('bob',     'token-bob'),
    ('carol',   'token-carol'),
    ('dave',    'token-dave')
ON CONFLICT (external_id) DO NOTHING;
