-- Wave 5g (Core hardening) — mutable per-offer shop stock.
--
-- Splits the ShopRegistry into two ports: IShopRegistry keeps the
-- read-only spec surface (resolve / register / all), and the new
-- IShopStockRepository owns this table. ShopPurchaseUseCase calls
-- decrementStock after a successful wallet debit and reverses the debit
-- on OutOfStock, closing the "spec-read-but-never-decremented" bug.
--
-- Composite PK (shop_id, offer_id) matches the addressing key in the
-- IShopStockRepository port. CHECK (remaining >= 0) is defence-in-depth
-- — the Postgres impl gates writes via SELECT ... FOR UPDATE so the
-- application never SHOULD send a negative, but the constraint keeps
-- the table consistent with the ShopStock.Limited domain invariant.
--
-- Only offers authored as ShopStock.Limited are seeded; Unlimited
-- offers never receive a row, and currentStock returns null for them.
CREATE TABLE shop_stock (
    shop_id     VARCHAR(64) NOT NULL,
    offer_id    VARCHAR(64) NOT NULL,
    remaining   INTEGER NOT NULL CHECK (remaining >= 0),
    PRIMARY KEY (shop_id, offer_id)
);
