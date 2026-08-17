CREATE TABLE games (
                       id SMALLSERIAL PRIMARY KEY,
                       code TEXT NOT NULL UNIQUE,
                       name TEXT NOT NULL
);

CREATE TABLE collections (
                             id BIGSERIAL PRIMARY KEY,
                             game_id SMALLINT NOT NULL REFERENCES games(id),
                             external_id TEXT NOT NULL,
                             name TEXT NOT NULL,
                             image_url TEXT,
                             source_type TEXT NOT NULL DEFAULT 'collection',
                             metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                             UNIQUE (game_id, external_id)
);

CREATE TABLE weapons (
                         id SMALLSERIAL PRIMARY KEY,
                         game_id SMALLINT NOT NULL REFERENCES games(id),
                         external_id TEXT NOT NULL,
                         name TEXT NOT NULL,
                         image_url TEXT,
                         UNIQUE (game_id, external_id)
);

CREATE TABLE rarities (
                          id SMALLSERIAL PRIMARY KEY,
                          game_id SMALLINT NOT NULL REFERENCES games(id),
                          external_id TEXT NOT NULL,
                          name TEXT NOT NULL,
                          color_hex CHAR(6),
                          sort_order SMALLINT NOT NULL,
                          UNIQUE (game_id, external_id)
);

CREATE TABLE wear_buckets (
                              id SMALLSERIAL PRIMARY KEY,
                              code TEXT NOT NULL UNIQUE,
                              display_name TEXT NOT NULL,
                              min_float NUMERIC(8,7) NOT NULL,
                              max_float NUMERIC(8,7) NOT NULL,
                              generation_min_float NUMERIC(8,7) NOT NULL,
                              probability NUMERIC(12,10) NOT NULL
);

CREATE TABLE finish_styles (
                               id SMALLSERIAL PRIMARY KEY,
                               external_id TEXT UNIQUE,
                               name TEXT NOT NULL
);

CREATE TABLE items (
                       id BIGSERIAL PRIMARY KEY,
                       game_id SMALLINT NOT NULL REFERENCES games(id),
                       external_id TEXT NOT NULL,
                       market_hash_name TEXT NOT NULL,
                       name TEXT NOT NULL,
                       weapon_id SMALLINT REFERENCES weapons(id),
                       collection_id BIGINT REFERENCES collections(id) ON DELETE SET NULL,
                       rarity_id SMALLINT REFERENCES rarities(id) ON DELETE SET NULL,
                       finish_style_id SMALLINT REFERENCES finish_styles(id) ON DELETE SET NULL,
                       pattern_id TEXT,
                       pattern_name TEXT,
                       min_float NUMERIC(8,7) NOT NULL,
                       max_float NUMERIC(8,7) NOT NULL,
                       stattrak BOOLEAN NOT NULL DEFAULT FALSE,
                       souvenir BOOLEAN NOT NULL DEFAULT FALSE,
                       image_url TEXT,
                       inspect_link_template TEXT,
                       metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                       active BOOLEAN NOT NULL DEFAULT TRUE,
                       UNIQUE (game_id, external_id),
                       UNIQUE (market_hash_name)
);

CREATE TABLE item_wear_availability (
                                        item_id BIGINT NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                                        wear_bucket_id SMALLINT NOT NULL REFERENCES wear_buckets(id),
                                        PRIMARY KEY (item_id, wear_bucket_id)
);

CREATE TABLE price_sources (
                               id SMALLSERIAL PRIMARY KEY,
                               code TEXT NOT NULL UNIQUE,
                               name TEXT NOT NULL,
                               currency_code CHAR(3) NOT NULL,
                               metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE item_current_prices (
                                     item_id BIGINT NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                                     wear_bucket_id SMALLINT NOT NULL REFERENCES wear_buckets(id),
                                     price_source_id SMALLINT NOT NULL REFERENCES price_sources(id),
                                     observed_at TIMESTAMPTZ NOT NULL,
                                     buy_price NUMERIC(18,4),
                                     sell_price NUMERIC(18,4),
                                     average_price NUMERIC(18,4),
                                     volume_24h INTEGER,
                                     listings INTEGER,
                                     liquidity_score NUMERIC(10,4),
                                     PRIMARY KEY (item_id, wear_bucket_id, price_source_id)
);

CREATE TABLE calculator_runs (
                                 id BIGSERIAL PRIMARY KEY,
                                 started_at TIMESTAMPTZ NOT NULL,
                                 finished_at TIMESTAMPTZ,
                                 snapshot_at TIMESTAMPTZ NOT NULL,
                                 interval_label TEXT NOT NULL,
                                 calculator_version TEXT NOT NULL,
                                 pricing_snapshot TEXT,
                                 status TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
                                 row_count BIGINT,
                                 error_message TEXT
);

CREATE TABLE tradeup_recipes (
                                 id BIGSERIAL PRIMARY KEY,
                                 canonical_hash BYTEA NOT NULL UNIQUE,
                                 game_id SMALLINT NOT NULL REFERENCES games(id),
                                 input_rarity_id SMALLINT NOT NULL REFERENCES rarities(id),
                                 output_rarity_id SMALLINT NOT NULL REFERENCES rarities(id),
                                 skin_1_item_id BIGINT NOT NULL REFERENCES items(id),
                                 skin_2_item_id BIGINT NOT NULL REFERENCES items(id),
                                 skin_1_count SMALLINT NOT NULL CHECK (skin_1_count >= 0),
                                 skin_2_count SMALLINT NOT NULL CHECK (skin_2_count >= 0),
                                 wear_bucket_id SMALLINT NOT NULL REFERENCES wear_buckets(id),
                                 allow_stattrak BOOLEAN NOT NULL DEFAULT FALSE,
                                 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                 CHECK (skin_1_count + skin_2_count = 10),
                                 CHECK (skin_1_item_id < skin_2_item_id OR skin_1_item_id = skin_2_item_id)
);

CREATE TABLE tradeup_recipe_outcomes (
                                         tradeup_recipe_id BIGINT NOT NULL REFERENCES tradeup_recipes(id) ON DELETE CASCADE,
                                         outcome_item_id BIGINT NOT NULL REFERENCES items(id),
                                         theoretical_probability NUMERIC(12,10) NOT NULL,
                                         source_collection_id BIGINT REFERENCES collections(id),
                                         PRIMARY KEY (tradeup_recipe_id, outcome_item_id)
);

CREATE TABLE ingest_batches (
                                id BIGSERIAL PRIMARY KEY,
                                calculator_run_id BIGINT NOT NULL REFERENCES calculator_runs(id) ON DELETE CASCADE,
                                batch_number INTEGER NOT NULL,
                                row_count INTEGER NOT NULL,
                                started_at TIMESTAMPTZ NOT NULL,
                                finished_at TIMESTAMPTZ,
                                status TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
                                error_message TEXT,
                                UNIQUE (calculator_run_id, batch_number)
);

CREATE INDEX idx_items_collection ON items(collection_id);
CREATE INDEX idx_items_weapon ON items(weapon_id);
CREATE INDEX idx_items_rarity ON items(rarity_id);
CREATE INDEX idx_item_prices_source ON item_current_prices(price_source_id, item_id);
CREATE INDEX idx_tradeup_recipes_inputs ON tradeup_recipes(skin_1_item_id, skin_2_item_id);
CREATE INDEX idx_tradeup_recipes_filter ON tradeup_recipes(input_rarity_id, wear_bucket_id);
CREATE INDEX idx_outcomes_item ON tradeup_recipe_outcomes(outcome_item_id);