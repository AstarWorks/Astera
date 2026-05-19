package com.astarworks.astera.economy.domain.policy

/**
 * How a currency unit was acquired. Determines policy variant.
 *
 *  - [Purchased]: bought with real money (subject to 資金決済法; expirable + non-redeemable)
 *  - [Earned]: gained from matches / missions / vote (looser rules; typically non-expirable)
 *  - [Bonus]: airdrop / promotional grant (often time-limited; expirable)
 *
 * Wave 5f (ADR-0035): part of the per-issuance ledger surface — each row in
 * [com.astarworks.astera.economy.application.port.outbound.ICurrencyIssuanceLog]
 * carries the source class so reporting + audit can break down outstanding
 * balance by acquisition channel.
 */
public sealed class SourceClass {
    public data object Purchased : SourceClass()
    public data object Earned : SourceClass()
    public data object Bonus : SourceClass()
}
