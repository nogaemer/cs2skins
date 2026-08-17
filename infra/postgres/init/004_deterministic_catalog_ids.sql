-- ============================================================================
-- Migration: deterministic catalog identity (Phase 2a) -- SIMPLIFIED VERSION
--
-- Assumes tradeup_recipes / tradeup_recipe_outcomes / item_wear_availability /
-- item_current_prices contain only regenerable dev data and are truncated
-- as step 0. Because of that, re-keying items/collections/weapons/rarities
-- never has to remap rows in those tables -- it only has to keep `items`
-- itself (real catalog data) internally consistent. This also means
-- tradeup_recipes.canonical_hash needs no separate backfill: the table is
-- empty, and the next optimizer run computes canonical_hash fresh from the
-- new deterministic ids automatically.
--
-- Run against a staging copy first regardless -- this drops and recreates
-- several FK constraints via CASCADE.
-- ============================================================================
BEGIN;

-- ----------------------------------------------------------------------------
-- 0. Wipe calculated / regenerable data (dev data -- will be recomputed)
-- ----------------------------------------------------------------------------
TRUNCATE TABLE calculator_runs, tradeup_recipes, item_wear_availability, item_current_prices CASCADE;
-- cascades into: ingest_batches (via calculator_runs), tradeup_recipe_outcomes (via tradeup_recipes)

-- ----------------------------------------------------------------------------
-- 1. Deterministic id helpers (mirrored exactly in KeyDerivation.kt)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION deterministic_id(input text) RETURNS bigint AS $$
DECLARE
    h bytea;
BEGIN
    h := digest(input, 'sha256');
    RETURN (get_byte(h, 0)::bigint << 56)
               | (get_byte(h, 1)::bigint << 48)
               | (get_byte(h, 2)::bigint << 40)
               | (get_byte(h, 3)::bigint << 32)
               | (get_byte(h, 4)::bigint << 24)
               | (get_byte(h, 5)::bigint << 16)
               | (get_byte(h, 6)::bigint << 8)
        |  get_byte(h, 7)::bigint;
END;
$$ LANGUAGE plpgsql IMMUTABLE STRICT;

CREATE OR REPLACE FUNCTION deterministic_id_16(input text) RETURNS smallint AS $$
DECLARE
    h bytea;
    v int;
BEGIN
    h := digest(input, 'sha256');
    v := (get_byte(h, 0) << 8) | get_byte(h, 1);
    RETURN (7 + (v % 32000))::smallint; -- 1..6 reserved for curated tiers below
END;
$$ LANGUAGE plpgsql IMMUTABLE STRICT;

-- ----------------------------------------------------------------------------
-- 2. RARITIES -- curated fixed ids for the 6 tradeup tiers, hash fallback
--    otherwise. items.rarity_id is the only live referencing column now
--    (tradeup_recipes.input_rarity_id/output_rarity_id are empty).
-- ----------------------------------------------------------------------------
CREATE TABLE rarity_id_map AS
SELECT id AS old_id, external_id, name,
       CASE name
           WHEN 'Consumer Grade'   THEN 1
           WHEN 'Industrial Grade' THEN 2
           WHEN 'Mil-Spec Grade'   THEN 3
           WHEN 'Restricted'       THEN 4
           WHEN 'Classified'       THEN 5
           WHEN 'Covert'           THEN 6
           ELSE deterministic_id_16(external_id)
           END AS new_id
FROM rarities;

DO $$
    DECLARE dup_count int;
    BEGIN
        SELECT count(*) INTO dup_count FROM (
                                                SELECT new_id FROM rarity_id_map GROUP BY new_id HAVING count(*) > 1
                                            ) d;
        IF dup_count > 0 THEN
            RAISE EXCEPTION 'rarity_id_map produced % colliding id(s) -- abort', dup_count;
        END IF;
    END $$;

ALTER TABLE rarities DROP CONSTRAINT rarities_pkey CASCADE; -- cascades away items_rarity_id_fkey,
-- tradeup_recipes_input/output_rarity_id_fkey
UPDATE rarities r SET id = m.new_id FROM rarity_id_map m WHERE r.id = m.old_id;
UPDATE items i SET rarity_id = m.new_id FROM rarity_id_map m WHERE i.rarity_id = m.old_id;

ALTER TABLE rarities ADD PRIMARY KEY (id);
ALTER TABLE rarities ALTER COLUMN id DROP DEFAULT;
DROP SEQUENCE IF EXISTS rarities_id_seq;

ALTER TABLE items ADD CONSTRAINT items_rarity_id_fkey
    FOREIGN KEY (rarity_id) REFERENCES rarities(id) ON DELETE SET NULL;
ALTER TABLE tradeup_recipes ADD CONSTRAINT tradeup_recipes_input_rarity_id_fkey
    FOREIGN KEY (input_rarity_id) REFERENCES rarities(id);
ALTER TABLE tradeup_recipes ADD CONSTRAINT tradeup_recipes_output_rarity_id_fkey
    FOREIGN KEY (output_rarity_id) REFERENCES rarities(id);

DROP TABLE rarity_id_map;

-- ----------------------------------------------------------------------------
-- 3. WEAPONS -- PK becomes external_id directly. Only items.weapon_id
--    references it.
-- ----------------------------------------------------------------------------
ALTER TABLE items DROP CONSTRAINT items_weapon_id_fkey;
ALTER TABLE items ADD COLUMN weapon_external_id TEXT;
UPDATE items i SET weapon_external_id = w.external_id FROM weapons w WHERE i.weapon_id = w.id;

ALTER TABLE weapons DROP CONSTRAINT weapons_pkey;
ALTER TABLE weapons DROP COLUMN id;
ALTER TABLE weapons ADD PRIMARY KEY (external_id);
DROP SEQUENCE IF EXISTS weapons_id_seq;

ALTER TABLE items DROP COLUMN weapon_id;
ALTER TABLE items RENAME COLUMN weapon_external_id TO weapon_id;
ALTER TABLE items ADD CONSTRAINT items_weapon_id_fkey
    FOREIGN KEY (weapon_id) REFERENCES weapons(external_id);

-- ----------------------------------------------------------------------------
-- 4. COLLECTIONS -- deterministic BIGINT hash, same column width as today.
--    items.collection_id is the only live referencing column
--    (tradeup_recipe_outcomes.source_collection_id is empty).
-- ----------------------------------------------------------------------------
CREATE TABLE collection_id_map AS
SELECT id AS old_id, external_id, deterministic_id(external_id) AS new_id
FROM collections;

DO $$
    DECLARE dup_count int;
    BEGIN
        SELECT count(*) INTO dup_count FROM (
                                                SELECT new_id FROM collection_id_map GROUP BY new_id HAVING count(*) > 1
                                            ) d;
        IF dup_count > 0 THEN
            RAISE EXCEPTION 'collection_id_map produced % colliding id(s) -- investigate before proceeding', dup_count;
        END IF;
    END $$;

ALTER TABLE collections DROP CONSTRAINT collections_pkey CASCADE; -- cascades away items_collection_id_fkey,
-- tradeup_recipe_outcomes_source_collection_id_fkey
UPDATE collections c SET id = m.new_id FROM collection_id_map m WHERE c.id = m.old_id;
UPDATE items i SET collection_id = m.new_id FROM collection_id_map m WHERE i.collection_id = m.old_id;

ALTER TABLE collections ADD PRIMARY KEY (id);
ALTER TABLE collections ALTER COLUMN id DROP DEFAULT;
DROP SEQUENCE IF EXISTS collections_id_seq;

ALTER TABLE items ADD CONSTRAINT items_collection_id_fkey
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE SET NULL;
ALTER TABLE tradeup_recipe_outcomes ADD CONSTRAINT tradeup_recipe_outcomes_source_collection_id_fkey
    FOREIGN KEY (source_collection_id) REFERENCES collections(id);

DROP TABLE collection_id_map;

-- ----------------------------------------------------------------------------
-- 5. ITEMS -- deterministic BIGINT hash. Every table that references
--    items.id (item_wear_availability, item_current_prices, tradeup_recipes,
--    tradeup_recipe_outcomes) is empty from step 0, so this is now a pure
--    self-contained re-key with no cross-table data to fix up.
-- ----------------------------------------------------------------------------
CREATE TABLE item_id_map AS
SELECT id AS old_id, external_id, deterministic_id(external_id) AS new_id
FROM items;

DO $$
    DECLARE dup_count int;
    BEGIN
        SELECT count(*) INTO dup_count FROM (
                                                SELECT new_id FROM item_id_map GROUP BY new_id HAVING count(*) > 1
                                            ) d;
        IF dup_count > 0 THEN
            RAISE EXCEPTION 'item_id_map produced % colliding id(s) -- investigate before proceeding', dup_count;
        END IF;
    END $$;

ALTER TABLE items DROP CONSTRAINT items_pkey CASCADE; -- cascades away item_wear_availability_item_id_fkey,
-- item_current_prices_item_id_fkey, tradeup_recipes_skin_1/2_item_id_fkey,
-- tradeup_recipe_outcomes_outcome_item_id_fkey (all on empty tables)
UPDATE items i SET id = m.new_id FROM item_id_map m WHERE i.id = m.old_id;

ALTER TABLE items ADD PRIMARY KEY (id);
ALTER TABLE items ALTER COLUMN id DROP DEFAULT;
DROP SEQUENCE IF EXISTS items_id_seq;

ALTER TABLE item_wear_availability ADD CONSTRAINT item_wear_availability_item_id_fkey
    FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE;
ALTER TABLE item_current_prices ADD CONSTRAINT item_current_prices_item_id_fkey
    FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE;
ALTER TABLE tradeup_recipes ADD CONSTRAINT tradeup_recipes_skin_1_item_id_fkey
    FOREIGN KEY (skin_1_item_id) REFERENCES items(id);
ALTER TABLE tradeup_recipes ADD CONSTRAINT tradeup_recipes_skin_2_item_id_fkey
    FOREIGN KEY (skin_2_item_id) REFERENCES items(id);
ALTER TABLE tradeup_recipe_outcomes ADD CONSTRAINT tradeup_recipe_outcomes_outcome_item_id_fkey
    FOREIGN KEY (outcome_item_id) REFERENCES items(id);

DROP TABLE item_id_map;

COMMIT;

-- After this: run PriceIngestionService.ingestCurrentPrices() and
-- PostgresSeedService.seedWears-equivalent (item_wear_availability repopulates
-- via seedSkins -> upsertItemWearAvailability) before running the optimizer,
-- since both were truncated in step 0.