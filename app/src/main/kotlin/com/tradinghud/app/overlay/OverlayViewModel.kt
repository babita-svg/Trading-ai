package com.tradinghud.app.overlay

import com.tradinghud.app.capture.CaptureService
import com.tradinghud.app.capture.ImageProcessor
import com.tradinghud.app.db.AppDatabase
import com.tradinghud.app.db.TradeLogEntity
import com.tradinghud.app.gemini.GeminiRepository
import com.tradinghud.app.util.RejectReasonText
import com.tradinghud.risk.RiskInputs
import com.tradinghud.risk.RiskManager
import com.tradinghud.risk.RiskVerdict
import com.tradinghud.risk.Signal
import com.tradinghud.risk.TradeProposal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

class OverlayViewModel(
    private val db: AppDatabase,
    private val geminiRepo: GeminiRepository,
    private val captureService: CaptureService,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _uiState = MutableStateFlow<OverlayState>(OverlayState.Bubble)
    val uiState: StateFlow<OverlayState> = _uiState

    private var activeJob: Job? = null

    fun onBubbleTap() {
        if (_uiState.value is OverlayState.Working) return
        _uiState.value = OverlayState.Input
    }

    fun cancel() {
        activeJob?.cancel()
        _uiState.value = OverlayState.Bubble
    }

    fun dismiss() {
        _uiState.value = OverlayState.Bubble
    }

    fun onTap(capitalInr: String, marketType: String) {
        val capital = capitalInr.toBigDecimalOrNull()
        if (capital == null || capital <= BigDecimal.ZERO) {
            _uiState.value = OverlayState.Blocked("Invalid capital amount. Enter a positive number.")
            return
        }

        activeJob = scope.launch(Dispatchers.IO) {
            _uiState.value = OverlayState.Working

            val startOfDay = todayStartMillis()
            val lossToday = db.tradeLogDao().sumLossesTodayBd(startOfDay)
            val inputs = RiskInputs(capital, lossToday)
            val dailyCheck = RiskManager.checkDailyLoss(inputs)
            if (dailyCheck != null) {
                _uiState.value = OverlayState.Blocked(RejectReasonText.from(dailyCheck.reason))
                return@launch
            }

            val bitmap = runCatching { captureService.captureFrame() }.getOrElse { e ->
                _uiState.value = OverlayState.Blocked("Screen capture failed: ${e.message}")
                return@launch
            }
            if (bitmap == null) {
                _uiState.value = OverlayState.Blocked("Screen capture failed: bitmap is null")
                return@launch
            }
            val imageBytes = ImageProcessor.process(bitmap)

            val signalResult = geminiRepo.analyze(imageBytes, capitalInr, marketType)
            val tradeSignal = signalResult.getOrElse { e ->
                _uiState.value = OverlayState.Blocked("Analysis failed: ${e.message}")
                return@launch
            }

            if (tradeSignal.market_type != marketType) {
                _uiState.value = OverlayState.Blocked(
                    "Market type mismatch: expected $marketType but model returned ${tradeSignal.market_type}."
                )
                return@launch
            }

            val signal = when (tradeSignal.signal) {
                "BUY" -> Signal.BUY
                "SELL" -> Signal.SELL
                "WAIT" -> {
                    _uiState.value = OverlayState.Blocked("No trade: ${tradeSignal.rationale}")
                    return@launch
                }
                else -> {
                    _uiState.value = OverlayState.Blocked("Unrecognized signal '${tradeSignal.signal}' from model.")
                    return@launch
                }
            }

            val proposal = TradeProposal(
                signal = signal,
                entry = BigDecimal(tradeSignal.entry_price),
                stopLoss = BigDecimal(tradeSignal.stop_loss),
                takeProfit = BigDecimal(tradeSignal.take_profit),
            )

            when (val verdict = RiskManager.assess(proposal, inputs)) {
                is RiskVerdict.Approved -> {
                    _uiState.value = OverlayState.Verdict(
                        proposal = proposal,
                        verdict = verdict,
                        marketType = marketType,
                        rationale = tradeSignal.rationale,
                    )
                }
                is RiskVerdict.Rejected -> {
                    _uiState.value = OverlayState.Blocked(
                        "🛑 TRADE REJECTED: RISK PARAMETERS EXCEEDED\n${RejectReasonText.from(verdict.reason)}"
                    )
                }
            }
        }
    }

    fun logResult(isWin: Boolean, amountInr: String) {
        val amount = amountInr.toBigDecimalOrNull() ?: BigDecimal.ZERO
        scope.launch(Dispatchers.IO) {
            db.tradeLogDao().insert(
                TradeLogEntity(
                    timestampMillis = System.currentTimeMillis(),
                    isWin = isWin,
                    amountInr = amount.toPlainString(),
                )
            )
            _uiState.value = OverlayState.Bubble
        }
    }
}

internal fun todayStartMillis(): Long {
    val zone = ZoneId.systemDefault()
    return LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
}
