-- rapid-essentials: homes and warps (PLAN.md §10). Never edit this file after it ships; add
-- V2__... instead (see Migrator). DATETIME columns are UTC.
--
-- `scope` is the server group (smp, skyrealms, hub...): a home set on Horizons belongs to the
-- Horizons worlds and is never offered on another mode. `world` is the world's key
-- (minecraft:overworld, rapidskies:spawn), which survives restarts.

CREATE TABLE IF NOT EXISTS essentials_homes (
    scope      VARCHAR(32)  NOT NULL,
    uuid       CHAR(36)     NOT NULL,
    name       VARCHAR(16)  NOT NULL,
    world      VARCHAR(128) NOT NULL,
    x          DOUBLE       NOT NULL,
    y          DOUBLE       NOT NULL,
    z          DOUBLE       NOT NULL,
    yaw        FLOAT        NOT NULL DEFAULT 0,
    pitch      FLOAT        NOT NULL DEFAULT 0,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (scope, uuid, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS essentials_warps (
    scope      VARCHAR(32)  NOT NULL,
    name       VARCHAR(16)  NOT NULL,
    world      VARCHAR(128) NOT NULL,
    x          DOUBLE       NOT NULL,
    y          DOUBLE       NOT NULL,
    z          DOUBLE       NOT NULL,
    yaw        FLOAT        NOT NULL DEFAULT 0,
    pitch      FLOAT        NOT NULL DEFAULT 0,
    created_by CHAR(36)     NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (scope, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
