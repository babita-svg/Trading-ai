package com.tradinghud.risk

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RiskManagerTest {

    private val capital = BigDecimal("100000") // ₹1,00,000

    // ── Signal: WAIT ──────────────────────────────────────────────────────────

    @Test fun `WAIT signal returns NO_TRADE without reading prices`() {
        val proposal = TradeProposal(
            signal = Signal.WAIT,
            entry = BigDecimal("0"),
            stopLoss = BigDecimal("0"),
            takeProfit = BigDecimal("0"),
        )
        val inputs = RiskInputs(capital, BigDecimal.ZERO)
        RiskManager.assess(proposal, inputs) shouldBe RiskVerdict.Rejected(RejectReason.NO_TRADE)
    }

    // ── Capital validation ────────────────────────────────────────────────────

    @Test fun `zero capital returns INVALID_CAPITAL`() {
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("95"), BigDecimal("108"))
        RiskManager.assess(proposal, RiskInputs(BigDecimal.ZERO, BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.INVALID_CAPITAL)
    }

    @Test fun `negative capital returns INVALID_CAPITAL`() {
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("95"), BigDecimal("108"))
        RiskManager.assess(proposal, RiskInputs(BigDecimal("-1"), BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.INVALID_CAPITAL)
    }

    // ── Price validation ──────────────────────────────────────────────────────

    @Test fun `entry equals stop returns INVALID_PRICES`() {
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("100"), BigDecimal("110"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.INVALID_PRICES)
    }

    @Test fun `zero entry price returns INVALID_PRICES`() {
        val proposal = TradeProposal(Signal.BUY, BigDecimal("0"), BigDecimal("95"), BigDecimal("108"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.INVALID_PRICES)
    }

    // ── Stop side ────────────────────────────────────────────────────────────

    @Test fun `BUY with stop above entry returns STOP_ON_WRONG_SIDE`() {
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("105"), BigDecimal("115"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.STOP_ON_WRONG_SIDE)
    }

    @Test fun `SELL with stop below entry returns STOP_ON_WRONG_SIDE`() {
        val proposal = TradeProposal(Signal.SELL, BigDecimal("100"), BigDecimal("95"), BigDecimal("85"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.STOP_ON_WRONG_SIDE)
    }

    // ── Plausible Price Distance (Anti-Hallucination) ─────────────────────────

    @Test fun `stop distance greater than 50 percent of entry returns IMPLAUSIBLE_PRICE_DISTANCE`() {
        // Entry 100, Stop 40 -> distance 60 > 50 (50% of 100)
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("40"), BigDecimal("190"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.IMPLAUSIBLE_PRICE_DISTANCE)
    }

    @Test fun `target distance greater than 50 percent of entry returns IMPLAUSIBLE_PRICE_DISTANCE`() {
        // Entry 100, Stop 95, Target 160 -> target distance 60 > 50 (50% of 100)
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("95"), BigDecimal("160"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.IMPLAUSIBLE_PRICE_DISTANCE)
    }

    @Test fun `stop and target distance within 50 percent of entry are accepted`() {
        // Entry 100, Stop 90 (dist 10), Target 116 (dist 16) -> both <= 50
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("90"), BigDecimal("116"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO))
            .shouldBeInstanceOf<RiskVerdict.Approved>()
    }

    // ── Daily cap ────────────────────────────────────────────────────────────

    @Test fun `loss exactly at 3 percent blocks DAILY_LOSS_LIMIT`() {
        val inputs = RiskInputs(capital, BigDecimal("3000"))
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("95"), BigDecimal("108"))
        RiskManager.assess(proposal, inputs) shouldBe
            RiskVerdict.Rejected(RejectReason.DAILY_LOSS_LIMIT)
    }

    @Test fun `loss below 3 percent does not block`() {
        val inputs = RiskInputs(capital, BigDecimal("2999.99"))
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("95"), BigDecimal("108"))
        RiskManager.assess(proposal, inputs).shouldBeInstanceOf<RiskVerdict.Approved>()
    }

    @Test fun `checkDailyLoss returns Rejected at threshold`() {
        val inputs = RiskInputs(capital, BigDecimal("3000"))
        RiskManager.checkDailyLoss(inputs) shouldBe
            RiskVerdict.Rejected(RejectReason.DAILY_LOSS_LIMIT)
    }

    @Test fun `checkDailyLoss returns null below threshold`() {
        val inputs = RiskInputs(capital, BigDecimal("2999"))
        RiskManager.checkDailyLoss(inputs) shouldBe null
    }

    // ── Position sizing ──────────────────────────────────────────────────────

    @Test fun `quantity rounds down correctly`() {
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("95"), BigDecimal("108"))
        val verdict = RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO))
        verdict shouldBe RiskVerdict.Approved(
            quantity = 300L,
            riskInr = BigDecimal("1500.00"),
            rewardToRisk = BigDecimal("1.60"),
        )
    }

    @Test fun `quantity that rounds to exactly 1 is approved`() {
        val proposal = TradeProposal(Signal.BUY, BigDecimal("10"), BigDecimal("9"), BigDecimal("12"))
        val verdict = RiskManager.assess(proposal, RiskInputs(BigDecimal("100"), BigDecimal.ZERO))
        verdict.shouldBeInstanceOf<RiskVerdict.Approved>()
        (verdict as RiskVerdict.Approved).quantity shouldBe 1L
    }

    @Test fun `quantity that rounds to zero returns POSITION_TOO_SMALL`() {
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("95"), BigDecimal("108"))
        RiskManager.assess(proposal, RiskInputs(BigDecimal("50"), BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.POSITION_TOO_SMALL)
    }

    // ── High precision crypto price decimals ─────────────────────────────────

    @Test fun `high decimal precision crypto prices calculated exactly`() {
        // Bitcoin satoshi or low-value altcoin with 8 decimal places
        val entry = BigDecimal("0.00012345")
        val stop = BigDecimal("0.00011000") // diff 0.00001345
        val target = BigDecimal("0.00015000") // diff 0.00002655 (R:R > 1.97)
        val proposal = TradeProposal(Signal.BUY, entry, stop, target)
        val verdict = RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO))
        verdict.shouldBeInstanceOf<RiskVerdict.Approved>()
    }

    // ── Reward ───────────────────────────────────────────────────────────────

    @Test fun `reward exactly at 1_5x passes`() {
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("95"), BigDecimal("107.5"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO))
            .shouldBeInstanceOf<RiskVerdict.Approved>()
    }

    @Test fun `reward below 1_5x returns REWARD_TOO_SMALL`() {
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("95"), BigDecimal("107"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.REWARD_TOO_SMALL)
    }

    @Test fun `SELL take profit must be below entry`() {
        val proposal = TradeProposal(Signal.SELL, BigDecimal("100"), BigDecimal("105"), BigDecimal("110"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.REWARD_TOO_SMALL)
    }

    @Test fun `SELL with valid stop and valid target is approved`() {
        val proposal = TradeProposal(Signal.SELL, BigDecimal("100"), BigDecimal("106"), BigDecimal("91"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO))
            .shouldBeInstanceOf<RiskVerdict.Approved>()
    }

    @Test fun `overflow-safe for extremely large capital`() {
        val hugeCapital = BigDecimal("999999999999999999")
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("95"), BigDecimal("110"))
        val verdict = RiskManager.assess(proposal, RiskInputs(hugeCapital, BigDecimal.ZERO))
        verdict.shouldBeInstanceOf<RiskVerdict.Approved>()
    }

    @Test fun `quantity overflow returns CALCULATION_ERROR`() {
        // Extremely massive capital with a tiny fractional stop distance exceeding Long.MAX_VALUE
        val absurdCapital = BigDecimal("999999999999999999999999999999999")
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("99.99999999"), BigDecimal("110"))
        val verdict = RiskManager.assess(proposal, RiskInputs(absurdCapital, BigDecimal.ZERO))
        verdict shouldBe RiskVerdict.Rejected(RejectReason.CALCULATION_ERROR)
    }
}
