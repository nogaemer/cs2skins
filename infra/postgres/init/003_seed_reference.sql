INSERT INTO games (code, name)
VALUES ('cs2', 'Counter-Strike 2')
ON CONFLICT (code) DO NOTHING;

INSERT INTO wear_buckets
(code, display_name, min_float, max_float, generation_min_float, probability)
VALUES
    ('factory_new', 'Factory New', 0.0000000, 0.0700000, 0.0000000, 0.0300000000),
    ('minimal_wear', 'Minimal Wear', 0.0700000, 0.1500000, 0.0800000, 0.2400000000),
    ('field_tested', 'Field-Tested', 0.1500000, 0.3800000, 0.1600000, 0.3300000000),
    ('well_worn', 'Well-Worn', 0.3800000, 0.4500000, 0.3900000, 0.2400000000),
    ('battle_scarred', 'Battle-Scarred', 0.4500000, 1.0000000, 0.4600000, 0.1600000000)
ON CONFLICT (code) DO NOTHING;

INSERT INTO price_sources (code, name, currency_code)
VALUES
    ('sih_market', 'SIH Market', 'EUR'),
    ('steam', 'Steam Community Market', 'EUR')
ON CONFLICT (code) DO NOTHING;