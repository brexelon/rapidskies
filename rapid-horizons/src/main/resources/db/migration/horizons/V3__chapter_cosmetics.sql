-- Phase 5 (PLAN.md §6.1): Chapter reward cosmetics are owned in rapid-core's cosmetics_owned.
-- New completions are granted there by ChapterService; this hands over the ones recorded
-- before the cosmetics engine existed. Core's migrations (V6) run first, since rapid-core
-- enables before rapid-horizons.

INSERT IGNORE INTO cosmetics_owned (uuid, cosmetic_id, source, acquired_at)
SELECT uuid, cosmetic, CONCAT('chapter:', chapter), completed_at
FROM chapter_completions
WHERE cosmetic <> '';
