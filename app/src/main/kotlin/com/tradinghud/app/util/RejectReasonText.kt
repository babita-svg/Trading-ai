package com.tradinghud.app.util

import com.tradinghud.risk.RejectReason

object RejectReasonText {
    fun from(reason: RejectReason): String = when (reason) {
        RejectReason.NO_TRADE -> "No trade signal — market structure is unclear."
        RejectReason.INVALID_CAPITAL -> "Invalid capital amount. Please enter a positive number."
        RejectReason.INVALID_PRICES -> "Invalid price levels returned by the model."
        RejectReason.STOP_ON_WRONG_SIDE -> "Stop loss is on the wrong side of the entry price."
        RejectReason.DAILY_LOSS_LIMIT -> "Daily loss limit reached (3% of capital). No more trades today."
        RejectReason.POSITION_TOO_SMALL -> "Position size is too small to trade. Increase capital or widen the stop."
        RejectReason.REWARD_TOO_SMALL -> "Take profit does not meet the minimum 1.5:1 reward-to-risk ratio."
        RejectReason.IMPLAUSIBLE_PRICE_DISTANCE -> "Stop loss or take profit distance exceeds 50% of entry price (implausible price levels)."
        RejectReason.CALCULATION_ERROR -> "Calculation error occurred during risk evaluation."
    }
}
