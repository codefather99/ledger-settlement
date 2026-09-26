-- V1__create_payments.sql
--
-- amount_minor is bigint, never a floating point column: this is the same rule the three
-- earlier labs enforced in Java, now enforced in the schema so no ORM mapping mistake or
-- raw SQL migration can quietly widen it to numeric/real/double precision later.

CREATE TABLE payments (
    id            VARCHAR(64)  NOT NULL,
    merchant_id   VARCHAR(64)  NOT NULL,
    amount_minor  BIGINT       NOT NULL,
    currency      VARCHAR(3)   NOT NULL,
    recorded_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT chk_payments_amount_minor_positive CHECK (amount_minor > 0),
    CONSTRAINT chk_payments_currency_length CHECK (char_length(currency) = 3)
);

CREATE INDEX idx_payments_merchant_id ON payments (merchant_id);
