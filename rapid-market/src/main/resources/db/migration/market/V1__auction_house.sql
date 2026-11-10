-- Auction house (PLAN.md §4.2, §10). One set of tables for both gamemodes, scoped by mode
-- (an EconomyMode id: horizons, skyrealms); listings never cross modes.
-- Never edit this file after it ships; add V2__... instead (see Migrator).
-- All DATETIME columns are UTC (Database sets the session time zone).

-- Every item for sale. An item is in exactly one place at a time: a player's inventory, an
-- ACTIVE listing here, or a row of market_collection. Every move between the last two is one
-- transaction; buying is the conditional update status ACTIVE -> SOLD, which only one buyer
-- on one server can win.
CREATE TABLE IF NOT EXISTS market_listings (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    -- The listing server's custody journal entry (rapid-market Custody): after a crash, the
    -- journal checks whether the listing went through by this token.
    token       CHAR(36)     NOT NULL,
    mode        VARCHAR(16)  NOT NULL,
    seller      CHAR(36)     NOT NULL,
    seller_name VARCHAR(16)  NOT NULL,
    -- Paper's ItemStack#serializeAsBytes: carries its data version and is upgraded on read.
    item_blob   MEDIUMBLOB   NOT NULL,
    -- The real English name, shown in lore and searched; item_key keys the price history.
    item_name   VARCHAR(128) NOT NULL,
    item_key    VARCHAR(128) NOT NULL,
    category    VARCHAR(16)  NOT NULL,
    amount      INT          NOT NULL,
    -- For the whole stack.
    price       BIGINT       NOT NULL,
    fee         BIGINT       NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at  DATETIME(3)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    buyer       CHAR(36)     NULL,
    buyer_name  VARCHAR(16)  NULL,
    sold_at     DATETIME(3)  NULL,
    tax         BIGINT       NULL,
    -- Sold far below the item's average (AlertRule): counted for alt-transfer alerts.
    flagged     TINYINT(1)   NOT NULL DEFAULT 0,
    -- When it stopped being ACTIVE other than by a sale, and the staff member for REMOVED.
    closed_at   DATETIME(3)  NULL,
    closed_by   CHAR(36)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_market_listings_token (token),
    KEY idx_market_listings_browse (mode, status, category, created_at),
    KEY idx_market_listings_expiry (mode, status, expires_at),
    KEY idx_market_listings_seller (seller, status),
    KEY idx_market_listings_buyer (buyer, sold_at),
    CONSTRAINT chk_market_listings_price CHECK (price > 0),
    CONSTRAINT chk_market_listings_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Collection bins (/ah collect): bought, expired, cancelled and removed items wait here
-- until their owner has room. token makes each way in happen once: listing:<id> for an item
-- leaving a listing, custody:<token> and restore:<id> for the custody journal's recoveries.
CREATE TABLE IF NOT EXISTS market_collection (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    token      VARCHAR(64)  NOT NULL,
    mode       VARCHAR(16)  NOT NULL,
    uuid       CHAR(36)     NOT NULL,
    item_blob  MEDIUMBLOB   NOT NULL,
    item_name  VARCHAR(128) NOT NULL,
    amount     INT          NOT NULL,
    reason     VARCHAR(16)  NOT NULL,
    listing_id BIGINT       NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_market_collection_token (token),
    KEY idx_market_collection_owner (mode, uuid, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Messages for sellers (sold, expired, removed), each shown once: by the server they're on
-- (over Redis) or at their next login. Deleted when shown.
CREATE TABLE IF NOT EXISTS market_notices (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    uuid       CHAR(36)     NOT NULL,
    mode       VARCHAR(16)  NOT NULL,
    kind       VARCHAR(16)  NOT NULL,
    item_name  VARCHAR(128) NOT NULL,
    amount     INT          NOT NULL,
    price      BIGINT       NOT NULL,
    proceeds   BIGINT       NOT NULL DEFAULT 0,
    other_name VARCHAR(16)  NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_market_notices_uuid (uuid, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Sales per item per UTC day: the 7-day average price per item shown when listing and
-- buying, and the economy health dashboard's source. total is the sum of prices, volume
-- the number of items, so the average is total / volume.
CREATE TABLE IF NOT EXISTS market_price_history (
    mode     VARCHAR(16)  NOT NULL,
    item_key VARCHAR(128) NOT NULL,
    day      DATE         NOT NULL,
    total    BIGINT       NOT NULL,
    volume   BIGINT       NOT NULL,
    sales    INT          NOT NULL,
    PRIMARY KEY (mode, item_key, day),
    KEY idx_market_price_history_day (mode, day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
