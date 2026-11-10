-- rapid-horizons (PLAN.md §5.2, §10): personal PvP and Trades.
-- Never edit this file after it ships; add V2__... instead (see Migrator).
-- All DATETIME columns are UTC (Database sets the session time zone).

-- Per-player Horizons state. pvp is the /pvp toggle (off by default); pvp_changed_at
-- drives its cooldown. trades_changed_at is when the player last dropped a Trade, which
-- starts the switching cooldown.
CREATE TABLE IF NOT EXISTS horizons_players (
    uuid              CHAR(36)    NOT NULL,
    pvp               TINYINT(1)  NOT NULL DEFAULT 0,
    pvp_changed_at    DATETIME(3) NULL,
    trades_changed_at DATETIME(3) NULL,
    PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Trade progress. A dropped Trade keeps its row (and level) with active = 0, so picking it
-- again later carries on where it stopped. xp is the total ever earned; level follows from it
-- and is stored for leaderboards.
CREATE TABLE IF NOT EXISTS horizons_trades (
    uuid   CHAR(36)    NOT NULL,
    trade  VARCHAR(16) NOT NULL,
    level  INT         NOT NULL DEFAULT 0,
    xp     BIGINT      NOT NULL DEFAULT 0,
    active TINYINT(1)  NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, trade),
    KEY idx_horizons_trades_level (trade, level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Trade points earned per UTC day, before Tailwind, for the daily soft cap (PLAN.md §5.3:
-- diminishing returns past a threshold). Old days can be pruned at will.
CREATE TABLE IF NOT EXISTS horizons_trade_daily (
    uuid   CHAR(36) NOT NULL,
    day    DATE     NOT NULL,
    points BIGINT   NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
