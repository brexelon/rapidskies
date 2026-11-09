-- Grants (PLAN.md §6.3, §9): ranks and permissions given with /grant or by the store. The
-- permission system (LuckPerms) holds the effective result; this table is the record of
-- who gave what, for how long and why, and what /grants shows and revokes.

CREATE TABLE IF NOT EXISTS grants (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    uuid            CHAR(36)     NOT NULL,
    -- rank (a LuckPerms group) or permission (a node, true in every context).
    kind            VARCHAR(16)  NOT NULL,
    value           VARCHAR(255) NOT NULL,
    -- NULL granted_by: the console (the store, scripts).
    granted_by      CHAR(36)     NULL,
    granted_by_name VARCHAR(16)  NOT NULL,
    reason          VARCHAR(255) NULL,
    -- Store transaction id: a retried delivery finds its row instead of granting twice.
    txn_id          VARCHAR(128) NULL,
    created_at      DATETIME(3)  NOT NULL,
    -- Timed grants stack, so a grant can start after it was created.
    starts_at       DATETIME(3)  NOT NULL,
    -- NULL: permanent.
    expires_at      DATETIME(3)  NULL,
    revoked_at      DATETIME(3)  NULL,
    revoked_by      CHAR(36)     NULL,
    revoked_by_name VARCHAR(16)  NULL,
    revoke_reason   VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_grants_txn (txn_id),
    KEY idx_grants_player (uuid, id),
    KEY idx_grants_value (uuid, kind, value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
