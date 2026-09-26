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

    // ── Daily cap ────────────────────────────────────────────────────────────

    @Test fun `loss exactly at 3 percent blocks DAILY_LOSS_LIMIT`() {
        // ₹3,000 = exactly 3% of ₹1,00,000 — must block (≥ not >)
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
        // capital=₹1,00,000, risk=1.5%=₹1,500, stop distance=₹5
        // raw qty = 1500/5 = 300.0 → 300
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("95"), BigDecimal("108"))
        val verdict = RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO))
        verdict shouldBe RiskVerdict.Approved(
            quantity = 300L,
            riskInr = BigDecimal("1500.00"),
            rewardToRisk = BigDecimal("1.60"),
        )
    }

    @Test fun `quantity that rounds to exactly 1 is approved`() {
        // capital=₹100, risk=1.5%=₹1.50, stop distance=₹1
        // raw qty = 1.5 → floor = 1
        val proposal = TradeProposal(Signal.BUY, BigDecimal("10"), BigDecimal("9"), BigDecimal("12"))
        val verdict = RiskManager.assess(proposal, RiskInputs(BigDecimal("100"), BigDecimal.ZERO))
        verdict.shouldBeInstanceOf<RiskVerdict.Approved>()
        (verdict as RiskVerdict.Approved).quantity shouldBe 1L
    }

    @Test fun `quantity that rounds to zero returns POSITION_TOO_SMALL`() {
        // capital=₹50, risk=1.5%=₹0.75, stop distance=₹5
        // raw qty = 0.15 → floor = 0 → POSITION_TOO_SMALL
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("95"), BigDecimal("108"))
        RiskManager.assess(proposal, RiskInputs(BigDecimal("50"), BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.POSITION_TOO_SMALL)
    }

    // ── Reward ───────────────────────────────────────────────────────────────

    @Test fun `reward exactly at 1_5x passes`() {
        // stop distance = 5, target distance = 7.5 → ratio = 1.5 exactly
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("95"), BigDecimal("107.5"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO))
            .shouldBeInstanceOf<RiskVerdict.Approved>()
    }

    @Test fun `reward below 1_5x returns REWARD_TOO_SMALL`() {
        // stop distance = 5, target distance = 7 → ratio = 1.4
        val proposal = TradeProposal(Signal.BUY, BigDecimal("100"), BigDecimal("95"), BigDecimal("107"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.REWARD_TOO_SMALL)
    }

    @Test fun `SELL take profit must be below entry`() {
        // SELL: entry=100, stop=105 (above), target=90 (below = profit direction)
        // stop side is wrong; first check that fails wins
        val proposal = TradeProposal(Signal.SELL, BigDecimal("100"), BigDecimal("105"), BigDecimal("110")) // TP above (wrong direction)
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.REWARD_TOO_SMALL)
    }

    @Test fun `SELL with valid stop and valid target is approved`() {
        // SELL stop must be ABOVE entry: entry=100, stop=106, target=91 (distance stop=6, distance tp=9 → 1.5x ✓)
        val proposal = TradeProposal(Signal.SELL, BigDecimal("100"), BigDecimal("106"), BigDecimal("91"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO))
            .shouldBeInstanceOf<RiskVerdict.Approved>()
    }
}
