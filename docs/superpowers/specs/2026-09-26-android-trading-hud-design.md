# Android AI Trading HUD — Design Spec

Date: 2026-09-26
Status: Awaiting review. No implementation plan exists yet.

## Purpose

A private, single-user Android app that shows a risk-checked trade signal over the user's broker app. The user taps a floating bubble, types their capital and market, and the app captures the chart on screen, asks Gemini to read it, and then accepts or rejects the suggestion with local math before anything is shown. The user places any order by hand inside their broker app.

This app trades nothing. It never sends an order, holds no broker credentials, and has no broker SDK.

## Scope

In this version:

- A floating, draggable bubble drawn over other apps.
- A four-state panel: input, working, verdict, blocked.
- One-time screen-capture consent, then capture on each tap.
- A Gemini call per tap, with the chart image plus the typed inputs.
- A pure-Kotlin risk engine that can veto the model.
- A daily loss cap fed by trades the user logs by hand.

Not in this version:

- Order placement, or any Kite, Binance, or broker integration.
- A desktop overlay, a web HUD, Termux, or a FastAPI backend.
- An ATR or volatility check. A screenshot contains no candles to compute one from.
- Automatic retries of failed API calls.
- Analytics, crash reporting, telemetry, or any remote logging.

## Decisions already made

- Display only. The user acts on the signal themselves.
- The chart image and the typed inputs are sent to Google's API.
- Capital and market type are asked fresh on every tap. Capital is never stored.
- A WAIT, an unparseable reply, or any failed call shows a blocked panel naming the reason. No signal is ever implied.
- Direction comes from an explicit `signal` field: BUY, SELL, or WAIT.
- Model is `gemini-3.8-flash`. The key is read from a gitignored `.env` into `BuildConfig` at build time. It is never typed into the app and never committed.
- The risk math lives in its own Gradle module with no Android and no Retrofit dependency.
- Today's losses come from closed trades the user logs. A win does not refill the daily budget.

## Known tradeoffs

`BuildConfig` embeds the key in the APK, so anyone with the APK can extract it. This is accepted for a private single-user build. The `.env` file is gitignored, the key is restricted to the Generative Language API, and it appears in no log line. Moving the key to encrypted device storage would remove this exposure and is out of scope.

`gemini-3.8-flash` and the Interactions API are specified by the owner. At the time of writing, the public Google AI documentation lists `gemini-2.5-flash` as the current stable low-latency multimodal model and the `generateContent` endpoint as the standard REST path. The implementation will target the owner-specified model identifier and endpoint path. If the model name or endpoint does not resolve at build or runtime, the Retrofit client will surface a non-200 error and block the panel; no fallback to an older model will happen silently.

## Modules

### `:risk-engine`

A Kotlin JVM library. It depends on nothing Android and nothing Retrofit. All arithmetic uses `BigDecimal`, never `Double`. It returns reason codes, never display strings.

```kotlin
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
}
```

`RiskManager.assess(proposal, inputs)` checks in this order and stops at the first failure:

1. **Signal.** WAIT returns `NO_TRADE` without reading prices.
2. **Capital.** `capitalInr` must be greater than zero, else `INVALID_CAPITAL`.
3. **Prices.** Entry, stop, and target must each be greater than zero and all different, else `INVALID_PRICES`.
4. **Stop side.** BUY requires stop below entry. SELL requires stop above entry. Otherwise `STOP_ON_WRONG_SIDE`.
5. **Daily cap.** If `realizedLossTodayInr` is greater than or equal to 3% of `capitalInr`, return `DAILY_LOSS_LIMIT`. Compared with this tap's typed capital, not a stored one.
6. **Quantity.** Risk per unit is the absolute distance from entry to stop. Quantity is `(capitalInr × 0.015) / riskPerUnit`, rounded down to a whole number. Below 1 returns `POSITION_TOO_SMALL`. The engine never returns a fraction.
7. **Reward.** The target must sit on the profitable side of entry, at a distance of at least 1.5 times the stop distance. Otherwise `REWARD_TOO_SMALL`. The engine never moves the target to make a trade pass.

`Approved.riskInr` is `quantity × riskPerUnit`, which is at most 1.5% of capital and usually slightly under it because quantity rounds down. `rewardToRisk` is target distance divided by stop distance.

The daily cap is also reachable without a proposal. `RiskManager.checkDailyLoss(inputs)` runs check 5 alone, so the app can refuse before capturing the screen.

Constants, named and in one place: `MAX_RISK_FRACTION = 0.015`, `MIN_REWARD_TO_RISK = 1.5`, `MAX_DAILY_LOSS_FRACTION = 0.03`. No other code may write these numbers inline.

### `:app`

The Android application module. It depends on `:risk-engine`. It owns the overlay, the screen capture, the Gemini client, and the Room database. It maps `RejectReason` values to the text the panel shows.

## Pipeline

All of this runs in the overlay service. Only the tap handling touches the main thread. Every failure ends at the blocked panel. No failure falls through to a verdict.

1. **Input.** The panel asks for capital in INR and one of Indian equity, Indian F&O, or crypto. Capital must parse to a positive `BigDecimal`. Cancel collapses the panel and sends nothing.

2. **Daily cap, before capture.** The app sums today's logged losses and calls `checkDailyLoss`. A breach shows the blocked panel and stops. No screenshot is taken and no request is sent.

3. **Capture.** The first tap triggers the system screen-capture consent dialog. Its token is held by the service and reused until the user stops the projection. The frame is scaled so its longest side is 1024px and encoded as JPEG at quality 70. The bitmap is recycled at once. The bytes stay in memory for the request and are never written to disk.

4. **Request.** Retrofit posts to the Gemini Interactions API endpoint for `gemini-3.8-flash`, with the key from `BuildConfig`. This is a stateless multimodal request: the chart image (base64-encoded JPEG) and the prompt text are sent in a single parts array. The generation config sets `responseMimeType` to `application/json` and a `responseSchema` requiring `signal`, `market_type`, `entry_price`, `stop_loss`, `take_profit`, and `rationale`. Timeout is 30 seconds. A non-200 status, a timeout, or a network failure shows the blocked panel naming the failure. Nothing retries on its own.

5. **Parse.** kotlinx.serialization decodes the body, ignoring unknown keys. A missing field, a null, a `signal` outside the three values, or a `market_type` that does not match the market selected in step 1 blocks with the reason. No default is substituted for a missing value.

6. **Verdict.** WAIT blocks as no-trade. BUY and SELL go to `RiskManager.assess` with the typed capital and today's logged losses. `Approved` shows the signal, entry, stop, target, whole-number quantity, rupees at risk, and the rationale. `Rejected` shows `TRADE REJECTED: RISK PARAMETERS EXCEEDED` plus the specific reason. Quantity is always the engine's number, never the model's.

7. **Logging.** Only an approved verdict offers a Log result action. The user marks Won or Lost and enters the realized P&L in INR, stored in Room. A rejected or blocked result has no log action, so a vetoed idea can never add to the day's losses.

## Overlay

One foreground service draws one overlay window through `TYPE_APPLICATION_OVERLAY`. The bubble is small and draggable. A tap expands the panel in place. The panel has exactly four states:

- **Input** — capital field, market choice, confirm, cancel.
- **Working** — a spinner and a cancel. Cancel aborts the request.
- **Verdict** — the approved signal and a Log result action.
- **Blocked** — the reason, and nothing else actionable.

A second tap while a request is running does nothing. Dismissing a verdict or blocked panel collapses back to the bubble.

## Persistence

One Room table, `closed_trades`: an id, the epoch millis it was logged, the result (WIN or LOSS), and the realized amount in INR stored as a string to preserve `BigDecimal` precision. The device timezone decides which rows belong to today, and the day changes at midnight. Nothing else is persisted. Capital, market choice, images, and verdicts are not stored. The screen-capture consent token is held by Android, not by the app's database.

## Permissions

`SYSTEM_ALERT_WINDOW` for the overlay, requested through the system settings screen. `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION` for the capture service, which shows a persistent notification while capture is active. The screen-capture consent itself is the system dialog, shown once. No other permission is requested. The app does not ask for network-state, storage, or accounts permissions. Internet access is declared but nothing is requested at runtime.

## Error handling

Each stage catches its own failure type and produces a blocked state. There is no catch-all that swallows an exception and continues. A serialization failure, an HTTP failure, a capture failure, and a risk rejection are distinct states with distinct reasons, and all of them render through the same blocked panel. If the service is killed, it stops. It does not restart itself into a half-captured state.

## Testing

`:risk-engine` is tested with plain JVM unit tests, one per check, plus the boundaries: quantity that rounds down to exactly 1, quantity that rounds down to 0, loss exactly at the 3% line, reward exactly at 1.5, stop equal to entry, and stop on the wrong side of entry.

`:app` tests the parts that don't need a device: JSON fixtures for a valid reply, a reply missing a field, a reply with a wrong `market_type`, and a WAIT; the mapping from `RejectReason` to panel text; and the sum of today's losses across a midnight boundary. The overlay and the capture flow are checked on a device, not asserted from a unit test.

## Build

Gradle Kotlin DSL with two modules. The `.env` file is read at configuration time and written into `BuildConfig.GEMINI_API_KEY`. A missing `.env` fails the build with a message naming the file, rather than compiling an empty key. `.gitignore` covers `.env`, keystores, and build outputs.
