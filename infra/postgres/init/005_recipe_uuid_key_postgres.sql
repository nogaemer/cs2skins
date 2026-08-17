-- ============================================================================
-- Migration: deterministic recipe identity (Phase 2b)
--
-- tradeup_recipes.id moves from BIGSERIAL to UUID, derived from the first 16
-- bytes of canonical_hash (already computed, already stable now that it's
-- built on Phase 2a's deterministic catalog ids). Same logical recipe -> same
-- UUID, every time, regardless of insert order, thread scheduling, or a
-- crash-and-regenerate cycle.
--
-- Truncates tradeup_recipes/tradeup_recipe_outcomes/calculator_runs first
-- (dev data, same policy as every other migration this session) so the type
-- change is a trivial ALTER on empty tables instead of a live remap of 11M+
-- rows. Re-run optimizeAll() after this to repopulate.
-- ============================================================================

BEGIN;

TRUNCATE TABLE calculator_runs, tradeup_recipes CASCADE;
-- cascades into: ingest_batches (via calculator_runs), tradeup_recipe_outcomes (via tradeup_recipes)

ALTER TABLE tradeup_recipe_outcomes DROP CONSTRAINT tradeup_recipe_outcomes_pkey;
ALTER TABLE tradeup_recipe_outcomes DROP CONSTRAINT IF EXISTS tradeup_recipe_outcomes_tradeup_recipe_id_fkey;
ALTER TABLE tradeup_recipe_outcomes ALTER COLUMN tradeup_recipe_id TYPE UUID USING NULL::uuid;
ALTER TABLE tradeup_recipe_outcomes ADD PRIMARY KEY (tradeup_recipe_id, outcome_item_id);

ALTER TABLE tradeup_recipes DROP CONSTRAINT tradeup_recipes_pkey;
ALTER TABLE tradeup_recipes ALTER COLUMN id DROP DEFAULT;
ALTER TABLE tradeup_recipes ALTER COLUMN id TYPE UUID USING NULL::uuid;
ALTER TABLE tradeup_recipes ADD PRIMARY KEY (id);
DROP SEQUENCE IF EXISTS tradeup_recipes_id_seq;

ALTER TABLE tradeup_recipe_outcomes ADD CONSTRAINT tradeup_recipe_outcomes_tradeup_recipe_id_fkey
    FOREIGN KEY (tradeup_recipe_id) REFERENCES tradeup_recipes(id) ON DELETE CASCADE;

COMMIT;
