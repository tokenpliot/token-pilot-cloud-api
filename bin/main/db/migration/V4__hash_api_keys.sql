ALTER TABLE api_keys
    ADD COLUMN hashed_key VARCHAR(255) NULL,
    ADD COLUMN display_key VARCHAR(255) NULL;

UPDATE api_keys
SET hashed_key = SHA2(api_key, 256),
    display_key = CONCAT(LEFT(api_key, 7), '...', RIGHT(api_key, 4))
WHERE api_key IS NOT NULL;

ALTER TABLE api_keys
    MODIFY hashed_key VARCHAR(255) NOT NULL,
    MODIFY display_key VARCHAR(255) NOT NULL,
    ADD CONSTRAINT uk_api_keys_hashed_key UNIQUE (hashed_key);

DROP INDEX idx_api_keys_value ON api_keys;

ALTER TABLE api_keys
    DROP COLUMN api_key;
