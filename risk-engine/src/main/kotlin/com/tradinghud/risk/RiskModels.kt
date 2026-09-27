package com.tradinghud.risk

import java.math.BigDecimal

enum class Signal { BUY, SELL, WAIT }

data class TradeProposal(
    val signal: Signal,
    val entry: BigDecimal,
    val stopLoss: BigDecimal,
    val takeProfit: BigDecimal,
)

data class RiskInputs(
    val capitalInr: BigDecimal,
    val realizedLossTodayInr: BigDecimal,
)

sealed interface RiskVerdict {
    data class Approved(
        val quantity: Long,
        val riskInr: BigDecimal,
        val rewardToRisk: BigDecimal,
    ) : RiskVerdict

    data class Rejected(val reason: RejectReason) : RiskVerdict
}

enum class RejectReason {
    NO_TRADE,
    INVALID_CAPITAL,
    INVALID_PRICES,
    STOP_ON_WRONG_SIDE,
    DAILY_LOSS_LIMIT,
    POSITION_TOO_SMALL,
    REWARD_TOO_SMALL,
    IMPLAUSIBLE_PRICE_DISTANCE,
    CALCULATION_ERROR,
}
