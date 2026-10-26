package com.astarworks.astera.core.domain.shop

/**
 * Ownership rule of a [ShopOffer] (ADR-0211).
 *
 * - [ONCE]: the purchase is an *unlock* (weapons, gear). It is paid once; afterwards the offer shows as
 *   owned and a free replica can be taken whenever the player no longer carries the item, so a dropped
 *   or lost weapon never costs money twice.
 * - [UNLIMITED]: a consumable - every purchase is paid and delivered.
 */
public enum class PurchaseLimit { ONCE, UNLIMITED }
