package com.tradinghud.app.overlay

import com.tradinghud.risk.RiskVerdict
import com.tradinghud.risk.TradeProposal

sealed interface OverlayState {
    data object Bubble : OverlayState
    data object Input : OverlayState
    data object Working : OverlayState

    data class Verdict(
        val proposal: TradeProposal,
        val verdict: RiskVerdict.Approved,
        val marketType: String,
        val rationale: String,
    ) : OverlayState

    data class Blocked(val message: String) : OverlayState
}
