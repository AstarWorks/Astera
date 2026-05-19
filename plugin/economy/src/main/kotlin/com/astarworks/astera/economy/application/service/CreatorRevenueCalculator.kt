package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.ICreatorRevenueRepository

/**
 * Pure-function split calculator over [ICreatorRevenueRepository] (RTM #12,
 * ADR-0035). Called from `PurchaseListingUseCase` (Worker β) at sale time —
 * no state of its own.
 *
 * Math (all long-integer, truncating toward zero):
 *  - `platformAmount   = floor(salePrice * platformFeeRate)`
 *  - `remainingAfterFee = salePrice - platformAmount`
 *  - If a creator record exists for [itemId]:
 *      - `creatorAmount = floor(remainingAfterFee * creator.revenueShare)`
 *      - `sellerAmount  = remainingAfterFee - creatorAmount`
 *  - Else:
 *      - `sellerAmount  = remainingAfterFee`, no creator amount
 *
 * Truncation is intentional: the platform absorbs no rounding error (it
 * always receives `floor(...)`), and the seller's residual absorbs the
 * creator-side rounding error. This keeps the seller+creator+platform sum
 * exactly equal to `salePrice` regardless of share values.
 *
 * **Shape is locked for Worker β** — names `split`, `RevenueSplit`,
 * `sellerAmount`, `creatorAmount`, `creatorId`, `platformAmount` are
 * referenced from `PurchaseListingUseCase`.
 */
public class CreatorRevenueCalculator(
    private val creatorRevenues: ICreatorRevenueRepository,
    private val platformFeeRate: Double = DEFAULT_PLATFORM_FEE,
) {
    init {
        require(platformFeeRate in 0.0..1.0) {
            "platformFeeRate must be in [0.0, 1.0]: $platformFeeRate"
        }
    }

    public fun split(itemId: ItemId, salePrice: Long): RevenueSplit {
        require(salePrice > 0) { "salePrice must be positive: $salePrice" }
        val platformAmount = (salePrice * platformFeeRate).toLong()
        val remainingAfterFee = salePrice - platformAmount
        val creator = creatorRevenues.findByItem(itemId)
        return if (creator != null) {
            val creatorAmount = (remainingAfterFee * creator.revenueShare).toLong()
            val sellerAmount = remainingAfterFee - creatorAmount
            RevenueSplit(
                sellerAmount = sellerAmount,
                creatorAmount = creatorAmount,
                creatorId = creator.creatorId,
                platformAmount = platformAmount,
            )
        } else {
            RevenueSplit(
                sellerAmount = remainingAfterFee,
                creatorAmount = null,
                creatorId = null,
                platformAmount = platformAmount,
            )
        }
    }

    public companion object {
        public const val DEFAULT_PLATFORM_FEE: Double = 0.10
    }
}

/**
 * Result of [CreatorRevenueCalculator.split]. Shape locked — Worker β reads
 * every field from `PurchaseListingUseCase`.
 *
 * - [sellerAmount] is always positive (seller receives at minimum
 *   `salePrice - floor(salePrice * platformFeeRate)`).
 * - [creatorAmount] / [creatorId] are both `null` when no creator record
 *   exists for the item, otherwise both non-null.
 * - [platformAmount] is `floor(salePrice * platformFeeRate)`.
 * - `sellerAmount + (creatorAmount ?: 0) + platformAmount == salePrice`.
 */
public data class RevenueSplit(
    public val sellerAmount: Long,
    public val creatorAmount: Long?,
    public val creatorId: PlayerId?,
    public val platformAmount: Long,
)
