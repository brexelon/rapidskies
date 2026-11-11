-- Phase 5 (PLAN.md §6, §7, §10): cosmetics, the Bolt shop, quests, achievements, network
-- levels, the Season Pass, daily login streaks and the store's transaction record.

-- Daily login streak (PLAN.md §6.2): consecutive UTC days with a login, and the last one.
ALTER TABLE players ADD COLUMN IF NOT EXISTS login_streak INT NOT NULL DEFAULT 0 AFTER tailwind_banked;
ALTER TABLE players ADD COLUMN IF NOT EXISTS streak_day DATE NULL AFTER login_streak;

-- Cosmetics a player owns for good (bought, earned, awarded). Rank sets aren't stored: they
-- come with the rank while it lasts. cosmetic_id is "<type>:<name>" (title:pathfinder).
-- source says where it came from: shop, level:10, achievement:<id>, pass:<season>,
-- chapter:<id>, season:<n>, admin:<name>.
CREATE TABLE IF NOT EXISTS cosmetics_owned (
    uuid        CHAR(36)    NOT NULL,
    cosmetic_id VARCHAR(64) NOT NULL,
    source      VARCHAR(64) NOT NULL,
    acquired_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid, cosmetic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One cosmetic per slot (title, tag, namecolor, badge, trail, arrow). Kept when it stops
-- being usable (a rank set after the rank lapses), so it comes back with the rank.
CREATE TABLE IF NOT EXISTS cosmetics_equipped (
    uuid        CHAR(36)    NOT NULL,
    slot        VARCHAR(16) NOT NULL,
    cosmetic_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (uuid, slot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Every Bolt shop purchase. tag is COSMETIC or CONVENIENCE: the shop sells nothing else
-- (PLAN.md §8). Cosmetics and the pass are delivered in the purchase's own transaction
-- (DELIVERED at once); a rank pass is PENDING until the proxy has granted it (grant_id).
CREATE TABLE IF NOT EXISTS shop_purchases (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    uuid         CHAR(36)     NOT NULL,
    item         VARCHAR(64)  NOT NULL,
    kind         VARCHAR(16)  NOT NULL,
    tag          VARCHAR(16)  NOT NULL,
    price        BIGINT       NOT NULL,
    featured     BOOLEAN      NOT NULL DEFAULT FALSE,
    txn_id       VARCHAR(128) NOT NULL,
    state        VARCHAR(16)  NOT NULL,
    grant_id     BIGINT       NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    delivered_at DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_shop_purchases_txn (txn_id),
    KEY idx_shop_purchases_player (uuid, id),
    KEY idx_shop_purchases_state (state, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Assigned quests (PLAN.md §7). period is a UTC day (2026-11-10) for dailies or an ISO week
-- (2026-W46) for weeklies; slot orders them within it. The quest's definition stays in
-- rapid-core's quests.yml; target is copied so a config change never moves a goal mid-way.
CREATE TABLE IF NOT EXISTS quests_progress (
    uuid         CHAR(36)    NOT NULL,
    period       VARCHAR(10) NOT NULL,
    slot         TINYINT     NOT NULL,
    kind         VARCHAR(8)  NOT NULL,
    quest        VARCHAR(64) NOT NULL,
    progress     BIGINT      NOT NULL DEFAULT 0,
    target       BIGINT      NOT NULL,
    assigned_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (uuid, period, slot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Lifetime counters per objective (blocks-broken, fish-caught, expeditions...), for
-- achievements.
CREATE TABLE IF NOT EXISTS player_stats (
    uuid  CHAR(36)    NOT NULL,
    stat  VARCHAR(32) NOT NULL,
    value BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, stat)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS achievements (
    uuid         CHAR(36)    NOT NULL,
    achievement  VARCHAR(64) NOT NULL,
    completed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid, achievement)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Season Pass (PLAN.md §7): network XP earned during the season, and whether the premium
-- track was bought. Rewards are cosmetics, granted as tiers are reached.
CREATE TABLE IF NOT EXISTS season_progress (
    uuid       CHAR(36)    NOT NULL,
    season     VARCHAR(32) NOT NULL,
    xp         BIGINT      NOT NULL DEFAULT 0,
    premium    BOOLEAN     NOT NULL DEFAULT FALSE,
    premium_at DATETIME(3) NULL,
    PRIMARY KEY (uuid, season),
    KEY idx_season_progress_board (season, xp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Store deliveries (PLAN.md §6.3), one row per Tebex transaction: what it was, and whether
-- it was charged back or refunded (which revokes what it gave).
CREATE TABLE IF NOT EXISTS store_transactions (
    txn_id     VARCHAR(128) NOT NULL,
    uuid       CHAR(36)     NOT NULL,
    package    VARCHAR(64)  NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    note       VARCHAR(255) NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (txn_id),
    KEY idx_store_transactions_player (uuid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
