-- rapid-claims (PLAN.md §4.1, §10): chunk claims, trust, towns, plots and Town Projects.
-- Never edit this file after it ships; add V2__... instead (see Migrator).
-- All DATETIME columns are UTC (Database sets the session time zone).
--
-- An owner is a player or a town: owner_type 'PLAYER' or 'TOWN', owner_id its uuid. A
-- town's uuid is also its bank account: the bank is the town's row in mode_balance
-- (mode 'horizons'), so deposits, upkeep and payouts go through the Crowns ledger like
-- every other Crowns change.

CREATE TABLE IF NOT EXISTS towns (
    uuid              CHAR(36)     NOT NULL,
    -- Unique ignoring case (the collation is case-insensitive).
    name              VARCHAR(24)  NOT NULL,
    tag               VARCHAR(4)   NOT NULL,
    -- A named chat color (TagColor); red shades are reserved for staff.
    tag_color         VARCHAR(16)  NOT NULL,
    mayor             CHAR(36)     NOT NULL,
    -- The town center chunk: every town chunk connects back to it and it can't be unclaimed.
    world             VARCHAR(64)  NOT NULL,
    center_x          INT          NOT NULL,
    center_z          INT          NOT NULL,
    -- "world, x, y, z, yaw, pitch" once the town warp is unlocked and set.
    warp              VARCHAR(128) NULL,
    -- Upkeep is charged a day at a time; a town is behind when this is in the past.
    upkeep_paid_until DATETIME(3)  NOT NULL,
    -- ACTIVE or RUINS (14 days behind on upkeep; unclaimed 7 days later).
    state             VARCHAR(8)   NOT NULL DEFAULT 'ACTIVE',
    ruins_since       DATETIME(3)  NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_towns_name (name),
    UNIQUE KEY uq_towns_tag (tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One town per player.
CREATE TABLE IF NOT EXISTS town_members (
    uuid      CHAR(36)    NOT NULL,
    town      CHAR(36)    NOT NULL,
    -- MAYOR, ASSISTANT, RESIDENT or RECRUIT.
    role      VARCHAR(12) NOT NULL,
    joined_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid),
    KEY idx_town_members_town (town),
    CONSTRAINT fk_town_members_town FOREIGN KEY (town) REFERENCES towns (uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- What each town role may do, per town (the mayor may do everything). permissions is a
-- comma-separated list of TownPermission names; land_role is the claim Role the town role
-- has on the town's own land outside plots.
CREATE TABLE IF NOT EXISTS town_roles (
    town        CHAR(36)     NOT NULL,
    role        VARCHAR(12)  NOT NULL,
    permissions VARCHAR(255) NOT NULL,
    land_role   VARCHAR(12)  NOT NULL,
    PRIMARY KEY (town, role),
    CONSTRAINT fk_town_roles_town FOREIGN KEY (town) REFERENCES towns (uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One row per claimed chunk. flags is a bit set of Flag (PvP, mob spawning, fire spread,
-- leaf decay); explosions are always blocked and aren't a flag.
CREATE TABLE IF NOT EXISTS claims (
    world      VARCHAR(64) NOT NULL,
    chunk_x    INT         NOT NULL,
    chunk_z    INT         NOT NULL,
    owner_type VARCHAR(6)  NOT NULL,
    owner_id   CHAR(36)    NOT NULL,
    flags      INT         NOT NULL,
    claimed_by CHAR(36)    NOT NULL,
    claimed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (world, chunk_x, chunk_z),
    KEY idx_claims_owner (owner_type, owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Trust on all of an owner's land. uuid 00000000-0000-0000-0000-000000000000 is "everyone".
-- A town plot uses its resident's own trust list.
CREATE TABLE IF NOT EXISTS claim_trust (
    owner_type VARCHAR(6)  NOT NULL,
    owner_id   CHAR(36)    NOT NULL,
    uuid       CHAR(36)    NOT NULL,
    -- TRUSTED, MEMBER, BUILDER or CO_OWNER.
    role       VARCHAR(12) NOT NULL,
    PRIMARY KEY (owner_type, owner_id, uuid),
    KEY idx_claim_trust_uuid (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Players kept out of all of an owner's land (the "deny entry" flag, per player).
CREATE TABLE IF NOT EXISTS claim_denied (
    owner_type VARCHAR(6) NOT NULL,
    owner_id   CHAR(36)   NOT NULL,
    uuid       CHAR(36)   NOT NULL,
    PRIMARY KEY (owner_type, owner_id, uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Personal claim allowance beyond the starter chunks and playtime: chunks bought with
-- Crowns (never money or Bolts) and chunks staff granted. ruins_since is set when the
-- player has been away 90 days; their claims are released 7 days later unless they return.
CREATE TABLE IF NOT EXISTS claim_players (
    uuid        CHAR(36)    NOT NULL,
    purchased   INT         NOT NULL DEFAULT 0,
    bonus       INT         NOT NULL DEFAULT 0,
    ruins_since DATETIME(3) NULL,
    PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Town chunks handed to a resident, who controls them with their own trust list.
CREATE TABLE IF NOT EXISTS town_plots (
    world    VARCHAR(64) NOT NULL,
    chunk_x  INT         NOT NULL,
    chunk_z  INT         NOT NULL,
    town     CHAR(36)    NOT NULL,
    resident CHAR(36)    NOT NULL,
    PRIMARY KEY (world, chunk_x, chunk_z),
    KEY idx_town_plots_resident (resident),
    CONSTRAINT fk_town_plots_town FOREIGN KEY (town) REFERENCES towns (uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Town Projects (PLAN.md §5.2): the catalog is rapid-claims' config; these rows are each
-- town's progress. state is ACTIVE or COMPLETED.
CREATE TABLE IF NOT EXISTS town_projects (
    town         CHAR(36)    NOT NULL,
    project      VARCHAR(32) NOT NULL,
    state        VARCHAR(10) NOT NULL,
    started_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (town, project),
    CONSTRAINT fk_town_projects_town FOREIGN KEY (town) REFERENCES towns (uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Who gave what. resource is an item key (minecraft:stone_bricks) or 'crowns'.
CREATE TABLE IF NOT EXISTS town_project_contributions (
    town     CHAR(36)    NOT NULL,
    project  VARCHAR(32) NOT NULL,
    uuid     CHAR(36)    NOT NULL,
    resource VARCHAR(64) NOT NULL,
    amount   BIGINT      NOT NULL,
    PRIMARY KEY (town, project, uuid, resource),
    CONSTRAINT fk_town_project_contributions FOREIGN KEY (town, project)
        REFERENCES town_projects (town, project) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The shared town vault (a Town Project unlock): its items, from ItemStack#serializeItemsAsBytes.
CREATE TABLE IF NOT EXISTS town_vaults (
    town       CHAR(36)    NOT NULL,
    contents   MEDIUMBLOB  NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (town),
    CONSTRAINT fk_town_vaults_town FOREIGN KEY (town) REFERENCES towns (uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
