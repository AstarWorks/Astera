package com.astarworks.astera.economy.domain.event

import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.domain.economy.Currency

public data class CurrencyChanged(
    val playerId: PlayerId,
    val currency: Currency,
    val delta: Long,
    val newBalance: Long,
    val reason: CurrencyChangeReason,
) : DomainEvent
