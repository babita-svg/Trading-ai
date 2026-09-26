package com.tradinghud.risk

import java.math.BigDecimal

object RiskConstants {
    val MAX_RISK_FRACTION: BigDecimal = BigDecimal("0.015")
    val MIN_REWARD_TO_RISK: BigDecimal = BigDecimal("1.5")
    val MAX_DAILY_LOSS_FRACTION: BigDecimal = BigDecimal("0.03")
}
