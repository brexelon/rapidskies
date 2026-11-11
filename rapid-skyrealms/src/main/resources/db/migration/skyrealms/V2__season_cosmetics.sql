-- Phase 5 (PLAN.md §6.1): season award cosmetics are owned in rapid-core's cosmetics_owned.
-- New awards are granted there in the same transaction as the award row; this hands over
-- the ones recorded before the cosmetics engine existed. Core's migrations (V6) run first,
-- since rapid-core enables before rapid-skyrealms.

INSERT IGNORE INTO cosmetics_owned (uuid, cosmetic_id, source, acquired_at)
SELECT uuid, cosmetic, CONCAT('season:', season), awarded_at
FROM skyrealms_season_awards
WHERE cosmetic <> '';
