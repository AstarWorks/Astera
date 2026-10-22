-- ADR-0211: shop purchase ledger. One row per completed purchase; it is both the ownership record for
-- once-only unlocks (count(player, shop, offer) > 0) and the player-facing purchase history.
CREATE TABLE shop_purchases (
    id            BIGSERIAL PRIMARY KEY,
    player_id     UUID        NOT NULL,
    shop_id       VARCHAR(64) NOT NULL,
    offer_id      VARCHAR(64) NOT NULL,
    item_id       VARCHAR(64) NOT NULL,
    currency      VARCHAR(16) NOT NULL,
    amount        BIGINT      NOT NULL CHECK (amount >= 0),
    purchased_at  TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_shop_purchases_player_offer ON shop_purchases (player_id, shop_id, offer_id);
CREATE INDEX idx_shop_purchases_player_time ON shop_purchases (player_id, purchased_at DESC);
