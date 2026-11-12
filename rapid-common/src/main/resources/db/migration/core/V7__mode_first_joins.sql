-- First visit to each gamemode, so rapid-core can welcome players who join SkyRealms or
-- Horizons for the first time. mode is an EconomyMode id (skyrealms, horizons).

CREATE TABLE IF NOT EXISTS mode_first_joins (
    uuid       CHAR(36)    NOT NULL,
    mode       VARCHAR(16) NOT NULL,
    first_join DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid, mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Players who already earned or spent a mode's currency have played it: they're not new.
-- (rapid-core also skips the welcome for anyone the server's own player data already knows.)
INSERT IGNORE INTO mode_first_joins (uuid, mode, first_join)
SELECT uuid, mode, MIN(created_at) FROM mode_economy_ledger GROUP BY uuid, mode;
