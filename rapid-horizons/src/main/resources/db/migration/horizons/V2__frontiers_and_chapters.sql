-- rapid-horizons phase 3b (PLAN.md §5.2, §10): the Frontier rotation and Chapters.
-- Never edit this file after it ships; add V3__... instead (see Migrator).
-- All DATETIME columns are UTC (Database sets the session time zone).

-- Every Frontier, past and present. Its worlds are rapidskies:frontier_<id> (plus _nether
-- and _end), generated from seed with the modifier's biomes. state: PREPARING (generated
-- ahead of its month), OPEN (the current one; at most one), CLOSED (replaced; the worlds
-- sit in archive_path until they're deleted, then archive_path is cleared).
-- end_opens_at is when the End opens for the community dragon fight; dragon_killed_at
-- when that fight was won (rewards are paid once).
CREATE TABLE IF NOT EXISTS frontier_history (
    id               INT          NOT NULL AUTO_INCREMENT,
    modifier         VARCHAR(32)  NOT NULL,
    seed             BIGINT       NOT NULL,
    chapter          VARCHAR(64)  NULL,
    state            VARCHAR(16)  NOT NULL,
    created_at       DATETIME(3)  NOT NULL,
    opened_at        DATETIME(3)  NULL,
    closes_at        DATETIME(3)  NULL,
    closed_at        DATETIME(3)  NULL,
    end_opens_at     DATETIME(3)  NULL,
    dragon_killed_at DATETIME(3)  NULL,
    archive_path     VARCHAR(255) NULL,
    PRIMARY KEY (id),
    KEY idx_frontier_history_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The Frontier a player logged out in (NULL: anywhere else). When that Frontier has closed
-- by the time they come back, they log in at the spawn instead of wherever Paper would put
-- someone whose world is gone.
ALTER TABLE horizons_players ADD COLUMN IF NOT EXISTS logout_frontier INT NULL;

-- Chapter collection logs: one row per player, chapter and entry. progress counts toward the
-- entry's amount; data holds what a "biomes" entry has seen (comma-separated keys).
CREATE TABLE IF NOT EXISTS chapter_progress (
    uuid         CHAR(36)    NOT NULL,
    chapter      VARCHAR(64) NOT NULL,
    entry        VARCHAR(64) NOT NULL,
    progress     INT         NOT NULL DEFAULT 0,
    data         TEXT        NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (uuid, chapter, entry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Finished Chapters and the cosmetic each one earned. The cosmetics engine (phase 5) grants
-- from here; the row also keeps the Bolts reward from ever being paid twice.
CREATE TABLE IF NOT EXISTS chapter_completions (
    uuid         CHAR(36)    NOT NULL,
    chapter      VARCHAR(64) NOT NULL,
    cosmetic     VARCHAR(64) NOT NULL DEFAULT '',
    completed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (uuid, chapter),
    KEY idx_chapter_completions_chapter (chapter)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
