package com.tradinghud.risk

import com.tradinghud.risk.RiskConstants.MAX_DAILY_LOSS_FRACTION
import com.tradinghud.risk.RiskConstants.MAX_PLAUSIBLE_PRICE_DISTANCE_FRACTION
import com.tradinghud.risk.RiskConstants.MAX_RISK_FRACTION
import com.tradinghud.risk.RiskConstants.MIN_REWARD_TO_RISK
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

object RiskManager {

    /**
     * Returns null if the daily loss cap has not been reached, or a Rejected
     * verdict if it has. Call this before capturing the screen to save a round-trip.
     */
    fun checkDailyLoss(inputs: RiskInputs): RiskVerdict.Rejected? {
        val threshold = inputs.capitalInr.multiply(MAX_DAILY_LOSS_FRACTION)
        return if (inputs.realizedLossTodayInr >= threshold) {
            RiskVerdict.Rejected(RejectReason.DAILY_LOSS_LIMIT)
        } else null
    }

    /**
     * Full eight-step evaluation. Returns [RiskVerdict.Approved] only when
     * every constraint passes. First failure wins.
     */
    fun assess(proposal: TradeProposal, inputs: RiskInputs): RiskVerdict {

        // 1. Signal
        if (proposal.signal == Signal.WAIT) {
            return RiskVerdict.Rejected(RejectReason.NO_TRADE)
        }

        // 2. Capital
        if (inputs.capitalInr <= BigDecimal.ZERO) {
            return RiskVerdict.Rejected(RejectReason.INVALID_CAPITAL)
        }

        // 3. Prices
        val entry = proposal.entry
        val stop = proposal.stopLoss
        val target = proposal.takeProfit
        if (entry <= BigDecimal.ZERO || stop <= BigDecimal.ZERO || target <= BigDecimal.ZERO
            || entry == stop || entry == target || stop == target
        ) {
            return RiskVerdict.Rejected(RejectReason.INVALID_PRICES)
        }

        // 4. Stop side
        val stopOnWrongSide = when (proposal.signal) {
            Signal.BUY -> stop >= entry
            Signal.SELL -> stop <= entry
            Signal.WAIT -> false
        }
        if (stopOnWrongSide) {
            return RiskVerdict.Rejected(RejectReason.STOP_ON_WRONG_SIDE)
        }

        // 5. Plausible price distance sanity check (guards against hallucinations)
        val stopDistance = (entry - stop).abs()
        val targetDistance = (entry - target).abs()
        val maxAllowedDistance = entry.multiply(MAX_PLAUSIBLE_PRICE_DISTANCE_FRACTION)
        if (stopDistance > maxAllowedDistance || targetDistance > maxAllowedDistance) {
            return RiskVerdict.Rejected(RejectReason.IMPLAUSIBLE_PRICE_DISTANCE)
        }

        // 6. Daily cap
        checkDailyLoss(inputs)?.let { return it }

        // 7. Quantity
        val riskPerUnit = stopDistance
        val maxRisk = inputs.capitalInr.multiply(MAX_RISK_FRACTION)
        val rawQty = maxRisk.divide(riskPerUnit, MathContext.DECIMAL128)
        val quantity = try {
            rawQty.setScale(0, RoundingMode.FLOOR).longValueExact()
        } catch (e: Exception) {
            return RiskVerdict.Rejected(RejectReason.CALCULATION_ERROR)
        }
        if (quantity < 1L) {
            return RiskVerdict.Rejected(RejectReason.POSITION_TOO_SMALL)
        }

        // 8. Reward
        val profitable = when (proposal.signal) {
            Signal.BUY -> target > entry
            Signal.SELL -> target < entry
            Signal.WAIT -> false
        }

        val rewardToRisk = targetDistance.divide(stopDistance, MathContext.DECIMAL128)
            .setScale(2, RoundingMode.HALF_UP)

        if (!profitable || rewardToRisk < MIN_REWARD_TO_RISK) {
            return RiskVerdict.Rejected(RejectReason.REWARD_TOO_SMALL)
        }

        val riskInr = BigDecimal(quantity).multiply(riskPerUnit)
            .setScale(2, RoundingMode.HALF_UP)

        return RiskVerdict.Approved(
            quantity = quantity,
            riskInr = riskInr,
            rewardToRisk = rewardToRisk,
        )
    }
}
