-- Friends (PLAN.md §7, §10). Each friendship is stored as two rows (a -> b and b -> a),
-- written and deleted together, so listing someone's friends is one indexed lookup.
-- Requests wait here until the target accepts or denies them, online or not.

CREATE TABLE IF NOT EXISTS friends (
    uuid   CHAR(36)    NOT NULL,
    friend CHAR(36)    NOT NULL,
    since  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid, friend),
    KEY idx_friends_friend (friend)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS friend_requests (
    sender     CHAR(36)    NOT NULL,
    target     CHAR(36)    NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (sender, target),
    KEY idx_friend_requests_target (target, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
