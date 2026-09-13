-- Disposable users for the get-or-create race, which can only be exercised by a user that
-- has no wallet yet.
--
-- Why this exists: the four demo users acquire wallets the first time anything runs against
-- the service, and from then on `POST /wallets` takes the found path, not the insert path.
-- Firing 50 concurrent creates at them proves nothing -- every caller reads a row that was
-- already there, and the assertion passes trivially. The same trap as the conservation test
-- that stayed green while every request failed (HANDOVER.md, finding #2).
--
-- So: a pool of users that start with no wallet. The burst script claims an unused one per
-- run and confirms the race actually ran by watching `wallet_wallets_created_total` rise by
-- exactly one. Each token is single-use for that purpose; once its wallet exists, the
-- script moves to the next.
--
-- Tokens are non-secret by design, exactly like the demo users in V2. This service holds no
-- real money. Do not copy this pattern anywhere that does.

INSERT INTO users (external_id, bearer_token)
SELECT 'burst-' || to_char(n, 'FM000'), 'token-burst-' || to_char(n, 'FM000')
FROM generate_series(1, 200) AS n
ON CONFLICT (external_id) DO NOTHING;
