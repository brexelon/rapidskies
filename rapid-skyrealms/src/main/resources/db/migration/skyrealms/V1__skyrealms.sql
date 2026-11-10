-- rapid-skyrealms (PLAN.md §5.1, §10): Realm Tiers, the server shop's soft cap, Trade Routes,
-- Expeditions and seasons. Islands themselves are SuperiorSkyblock2's (its own schema); every
-- `island` column here is an SS2 island uuid.
-- Never edit this file after it ships; add V2__... instead (see Migrator).
-- All DATETIME columns are UTC (Database sets the session time zone).

-- An island's Realm Tier (meadow, desert, frost...). Islands without a row are on the first tier.
CREATE TABLE IF NOT EXISTS skyrealms_islands (
    island     CHAR(36)    NOT NULL,
    tier       VARCHAR(32) NOT NULL,
    tier_since DATETIME(3) NOT NULL,
    PRIMARY KEY (island)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Progress toward the goals of the tier an island is working on (`tier` is the tier being
-- unlocked). Rows of a tier already reached are kept for the record.
CREATE TABLE IF NOT EXISTS skyrealms_tier_progress (
    island   CHAR(36)    NOT NULL,
    tier     VARCHAR(32) NOT NULL,
    goal     VARCHAR(64) NOT NULL,
    progress BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (island, tier, goal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Coins a player earned selling to the server shop per UTC day, before Tailwind, for the daily
-- soft cap (PLAN.md §5.3: diminishing returns on auto-farm income). Old days can be pruned.
CREATE TABLE IF NOT EXISTS skyrealms_shop_daily (
    uuid   CHAR(36) NOT NULL,
    day    DATE     NOT NULL,
    earned BIGINT   NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Trade Routes (PLAN.md §5.1): `qty` of a plain `item` (a material key) from the seller island
-- to the buyer island every `interval_hours`, for `price` Coins. OFFERED until the other island
-- accepts, ACTIVE while delivering, ENDED after a cancel, too many missed deliveries or a
-- disband (end_reason). The seller's contract chest is chest_world/x/y/z. The buyer island's
-- owner pays and the seller island's owner is paid, at delivery time.
CREATE TABLE IF NOT EXISTS skyrealms_trade_routes (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    seller_island  CHAR(36)     NOT NULL,
    buyer_island   CHAR(36)     NOT NULL,
    seller_name    VARCHAR(64)  NOT NULL,
    buyer_name     VARCHAR(64)  NOT NULL,
    item           VARCHAR(64)  NOT NULL,
    qty            INT          NOT NULL,
    price          BIGINT       NOT NULL,
    interval_hours INT          NOT NULL,
    state          VARCHAR(16)  NOT NULL,
    offered_by     CHAR(36)     NOT NULL,
    offered_side   VARCHAR(8)   NOT NULL,
    chest_world    VARCHAR(128) NULL,
    chest_x        INT          NULL,
    chest_y        INT          NULL,
    chest_z        INT          NULL,
    next_due       DATETIME(3)  NULL,
    deliveries     INT          NOT NULL DEFAULT 0,
    missed         INT          NOT NULL DEFAULT 0,
    created_at     DATETIME(3)  NOT NULL,
    signed_at      DATETIME(3)  NULL,
    ended_at       DATETIME(3)  NULL,
    end_reason     VARCHAR(32)  NULL,
    PRIMARY KEY (id),
    KEY idx_routes_seller (seller_island, state),
    KEY idx_routes_buyer (buyer_island, state),
    KEY idx_routes_due (state, next_due)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One row per delivery; the primary key makes each cycle happen once, even if a delivery
-- is retried after a lost commit.
CREATE TABLE IF NOT EXISTS skyrealms_route_deliveries (
    route_id     BIGINT      NOT NULL,
    cycle        INT         NOT NULL,
    qty          INT         NOT NULL,
    price        BIGINT      NOT NULL,
    tax          BIGINT      NOT NULL,
    delivered_at DATETIME(3) NOT NULL,
    PRIMARY KEY (route_id, cycle)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Delivered goods waiting for the buyer island to collect them (/route depot), by material.
CREATE TABLE IF NOT EXISTS skyrealms_route_depot (
    island   CHAR(36)    NOT NULL,
    item     VARCHAR(64) NOT NULL,
    amount   BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (island, item)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One row per Expedition, for history and the economy dashboard.
CREATE TABLE IF NOT EXISTS skyrealms_expeditions (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    theme        VARCHAR(32) NOT NULL,
    started_at   DATETIME(3) NOT NULL,
    ended_at     DATETIME(3) NULL,
    contributors INT         NOT NULL DEFAULT 0,
    coins_paid   BIGINT      NOT NULL DEFAULT 0,
    heart_broken TINYINT(1)  NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seasons (PLAN.md §5.1): the island-value leaderboard ranks the worth an island gained since
-- the season began (its baseline; 0 for islands created during the season). Islands persist.
CREATE TABLE IF NOT EXISTS skyrealms_seasons (
    id         INT         NOT NULL,
    started_at DATETIME(3) NOT NULL,
    ends_at    DATETIME(3) NOT NULL,
    ended_at   DATETIME(3) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS skyrealms_season_baseline (
    season INT            NOT NULL,
    island CHAR(36)       NOT NULL,
    worth  DECIMAL(38, 4) NOT NULL,
    PRIMARY KEY (season, island)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Season rewards per member of a top island: a cosmetic for the cosmetics engine to grant
-- (like chapter_completions) and the Bolts paid once (the ledger's txn id is season:<id>:<uuid>).
CREATE TABLE IF NOT EXISTS skyrealms_season_awards (
    season      INT            NOT NULL,
    uuid        CHAR(36)       NOT NULL,
    island      CHAR(36)       NOT NULL,
    island_name VARCHAR(64)    NOT NULL,
    island_rank INT            NOT NULL,
    gained      DECIMAL(38, 4) NOT NULL,
    cosmetic    VARCHAR(64)    NOT NULL,
    bolts       BIGINT         NOT NULL,
    awarded_at  DATETIME(3)    NOT NULL,
    PRIMARY KEY (season, uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
