-- Hub parkour best times (PLAN.md §7): one row per player, course and period, holding that
-- player's best. period is 'all' for all time, or an ISO week ('2026-W45', UTC) for the
-- weekly boards, which start empty every Monday so newcomers have a board they can top.

CREATE TABLE IF NOT EXISTS parkour_times (
    course      VARCHAR(32) NOT NULL,
    period      VARCHAR(10) NOT NULL,
    uuid        CHAR(36)    NOT NULL,
    time_ms     INT         NOT NULL,
    -- When this best was set: ties rank whoever got there first.
    achieved_at DATETIME(3) NOT NULL,
    PRIMARY KEY (course, period, uuid),
    KEY idx_parkour_board (course, period, time_ms, achieved_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
