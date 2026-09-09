-- Reference schema for the fingerprint store.
--
-- This file is NOT executed at startup. The table is shared with the existing BioMini
-- service and is owned by that deployment; it is kept here so the columns this service
-- depends on are documented in one place, and so a fresh environment can be created.
--
-- IMPORTANT: the upsert in FingerprintRepository.save requires a unique constraint on
-- RELATION_NO. Without it, ON CONFLICT (RELATION_NO) raises
--   "there is no unique or exclusion constraint matching the ON CONFLICT specification".
-- Existing deployments that predate this service must add the constraint before use.

CREATE TABLE IF NOT EXISTS TB_TBLSIG_DETAILS_TEMP (
    RELATION_NO          VARCHAR(64) NOT NULL,

    -- Biometric templates, in the format selected by fingerprint.device.template-format.
    -- Both services must agree on that format or matching silently returns no results.
    FINGER_PRINT_ONE     BYTEA,
    FINGER_PRINT_TWO     BYTEA,

    -- Compressed JPEG previews of each capture, stored as raw bytes.
    B64_FINGER_PRINT_ONE BYTEA,
    B64_FINGER_PRINT_TWO BYTEA,

    FINGER_SIZE          INTEGER,
    FINGER_QUALITY       INTEGER,

    CONSTRAINT PK_TBLSIG_DETAILS_TEMP PRIMARY KEY (RELATION_NO)
);

-- If the table already exists without the constraint, add it:
--   ALTER TABLE TB_TBLSIG_DETAILS_TEMP
--     ADD CONSTRAINT UQ_TBLSIG_DETAILS_TEMP_RELATION_NO UNIQUE (RELATION_NO);

-- Identification pages through this table ordered by RELATION_NO, so an index on it
-- keeps the scan from re-sorting the whole table for every page.
CREATE INDEX IF NOT EXISTS IX_TBLSIG_TEMP_ONE
    ON TB_TBLSIG_DETAILS_TEMP (RELATION_NO)
    WHERE FINGER_PRINT_ONE IS NOT NULL;

CREATE INDEX IF NOT EXISTS IX_TBLSIG_TEMP_TWO
    ON TB_TBLSIG_DETAILS_TEMP (RELATION_NO)
    WHERE FINGER_PRINT_TWO IS NOT NULL;
