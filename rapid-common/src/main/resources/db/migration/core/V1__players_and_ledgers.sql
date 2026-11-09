-- Core schema (PLAN.md §10): player profiles, the Bolts ledger and the per-mode ledgers.
-- Never edit this file after it ships; add V2__... instead (see Migrator).
-- All DATETIME columns are UTC (Database sets the session time zone).

CREATE TABLE IF NOT EXISTS players (
    uuid            CHAR(36)    NOT NULL,
    name            VARCHAR(16) NOT NULL,
    first_join      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_join       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_server     VARCHAR(32) NULL,
    network_xp      BIGINT      NOT NULL DEFAULT 0,
    locale          VARCHAR(16) NULL,
    -- Chosen plus color id (rank/PlusColor). Kept when a rank lapses, so it comes back
    -- with the rank (PLAN.md §6.1).
    plus_color      VARCHAR(32) NULL,
    -- Tailwind rested bonus banked, in seconds of bonus play (PLAN.md §5.3).
    tailwind_banked BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid),
    KEY idx_players_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Every Bolts change is a ledger row; bolts_balance is the running total, updated in the
-- same transaction. txn_id makes store grants idempotent (PLAN.md §6.3).
CREATE TABLE IF NOT EXISTS bolts_ledger (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    uuid       CHAR(36)     NOT NULL,
    delta      BIGINT       NOT NULL,
    reason     VARCHAR(64)  NOT NULL,
    source     VARCHAR(64)  NOT NULL,
    txn_id     VARCHAR(128) NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_bolts_ledger_txn (txn_id),
    KEY idx_bolts_ledger_uuid (uuid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS bolts_balance (
    uuid       CHAR(36)    NOT NULL,
    balance    BIGINT      NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid),
    CONSTRAINT chk_bolts_balance_nonneg CHECK (balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Skyrealms Coins and Horizons Crowns. Kept apart from Bolts on purpose: the currencies
-- never convert into each other (PLAN.md §5, §8).
CREATE TABLE IF NOT EXISTS mode_economy_ledger (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    mode       VARCHAR(16)  NOT NULL,
    uuid       CHAR(36)     NOT NULL,
    delta      BIGINT       NOT NULL,
    reason     VARCHAR(64)  NOT NULL,
    source     VARCHAR(64)  NOT NULL,
    txn_id     VARCHAR(128) NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_mode_ledger_txn (mode, txn_id),
    KEY idx_mode_ledger_uuid (mode, uuid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mode_balance (
    mode       VARCHAR(16) NOT NULL,
    uuid       CHAR(36)    NOT NULL,
    balance    BIGINT      NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (mode, uuid),
    CONSTRAINT chk_mode_balance_nonneg CHECK (balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
