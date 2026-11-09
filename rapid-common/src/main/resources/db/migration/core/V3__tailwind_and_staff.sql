-- Tailwind accrual (PLAN.md §5.3) and the staff action log (PLAN.md §9, §10).

-- When the player last left the network, written by rapid-proxy on disconnect and cleared
-- (NULL) when their offline time is converted into Tailwind on the next login. NULL also
-- covers players who were online when this column was added: they simply accrue nothing
-- for that one absence.
ALTER TABLE players ADD COLUMN IF NOT EXISTS last_seen DATETIME(3) NULL AFTER last_server;

-- Staff mode actions: freezes, inventory inspections, vanish toggles. Kept for audits and
-- for the staff GUI's history view.
CREATE TABLE IF NOT EXISTS staff_actions (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    staff      CHAR(36)     NOT NULL,
    action     VARCHAR(32)  NOT NULL,
    target     CHAR(36)     NULL,
    server     VARCHAR(32)  NOT NULL,
    detail     VARCHAR(255) NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_staff_actions_staff (staff, created_at),
    KEY idx_staff_actions_target (target, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
