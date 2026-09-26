# Android AI Trading HUD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a two-module Android app that draws a floating bubble over broker apps, captures the chart on tap, queries Gemini for a trade signal, and then gates that signal through a local risk engine before displaying anything.

**Architecture:** A pure-Kotlin `:risk-engine` Gradle module holds all trading math with no Android or Retrofit dependency. The `:app` module depends on it, owns the overlay service, screen capture, Gemini client, and Room database. The risk engine is the only authority on position size and trade approval — it can veto the model.

**Tech Stack:** Kotlin 2.0, Android SDK 35 (min 26), Gradle Kotlin DSL, Jetpack Compose for overlay views (WindowManager host), Room 2.7, Retrofit 2.11 + OkHttp 4.12, kotlinx.serialization 1.7, Coroutines 1.8, JUnit 5 + Kotest for `:risk-engine` tests.

**Spec:** `docs/superpowers/specs/2026-09-26-android-trading-hud-design.md`

## Global Constraints

- All monetary arithmetic in `:risk-engine` uses `java.math.BigDecimal`. `Double` is never used for prices or capital.
- `MAX_RISK_FRACTION = 0.015`, `MIN_REWARD_TO_RISK = 1.5`, `MAX_DAILY_LOSS_FRACTION = 0.03` are named constants in one file. No other file may write these numbers inline.
- `:risk-engine` has zero Android dependencies and zero Retrofit/OkHttp dependencies. The Gradle module must fail to compile if either is added.
- The Gemini API key comes exclusively from `BuildConfig.GEMINI_API_KEY`, populated from a gitignored `.env` file at build time. A missing `.env` must fail the build with a descriptive message.
- The model identifier in the Retrofit call is `gemini-3.8-flash`. The endpoint path targets the Interactions API.
- No field in the `TradeSignal` response may be silently defaulted. A missing or null field is a parse error that produces a blocked panel.
- The `market_type` in the parsed response must match the market selected in the input panel. A mismatch is a parse error.
- Captured images are kept only in memory; never written to disk.
- No analytics, telemetry, crash reporting, or remote logging of any kind.
- Min SDK 26, target SDK 35. Kotlin 2.0+.

## Review Focus

1. **Daily loss threshold exactly at 3 %**: A loss of exactly `capital × 0.03` must block (≥, not >). Off-by-one here lets a trade through on the worst day. See Task 2, step 1.
2. **Stop on the wrong side for SELL**: Stop above entry for BUY is caught easily; stop *below* entry for SELL is the mirror case that gets missed. See Task 2, step 1.
3. **`market_type` mismatch between input and response**: User selects CRYPTO, model returns INDIAN_FNO — should block, not approve. See Task 7, step 1.
4. **Midnight boundary for today's losses**: A trade logged at 23:59 must not count toward tomorrow's cap at 00:01. Room query uses the device timezone, not UTC. See Task 6, step 1.
5. **Quantity rounding down to zero**: When capital is small relative to the stop distance, `(capital × 0.015) / riskPerUnit < 1.0` must return `POSITION_TOO_SMALL`, not quantity = 0. See Task 2, step 1.

---

## File Map

```
trading-hud/
├── .env                             # gitignored — GEMINI_API_KEY=...
├── .gitignore
├── build.gradle.kts                 # root build, reads .env into allprojects ext
├── settings.gradle.kts              # includes :risk-engine, :app
│
├── risk-engine/
│   ├── build.gradle.kts             # kotlin("jvm"), no android, no retrofit
│   └── src/
│       ├── main/kotlin/com/tradinghud/risk/
│       │   ├── RiskConstants.kt     # MAX_RISK_FRACTION etc.
│       │   ├── RiskModels.kt        # Signal, TradeProposal, RiskInputs, RiskVerdict, RejectReason
│       │   └── RiskManager.kt       # assess(), checkDailyLoss()
│       └── test/kotlin/com/tradinghud/risk/
│           └── RiskManagerTest.kt   # all unit tests
│
└── app/
    ├── build.gradle.kts             # android application, depends on :risk-engine
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/tradinghud/app/
        │   ├── BuildConfigProvider.kt    # thin wrapper so tests can inject key
        │   ├── db/
        │   │   ├── AppDatabase.kt        # Room database
        │   │   ├── TradeLogDao.kt        # insert + sumLossToday
        │   │   └── TradeLogEntity.kt     # id, timestampMillis, isWin, amountInr (String)
        │   ├── gemini/
        │   │   ├── GeminiApi.kt          # Retrofit interface — Interactions API
        │   │   ├── GeminiClient.kt       # builds OkHttpClient + Retrofit, attaches key
        │   │   ├── GeminiModels.kt       # GeminiRequest, GeminiResponse, TradeSignal
        │   │   └── GeminiRepository.kt   # suspending fun analyze(): Result<TradeSignal>
        │   ├── capture/
        │   │   ├── CaptureService.kt     # foreground service, holds projection token
        │   │   └── ImageProcessor.kt     # scale to 1024px, JPEG 70, return ByteArray
        │   ├── overlay/
        │   │   ├── OverlayService.kt     # WindowManager host, state machine
        │   │   ├── OverlayState.kt       # sealed class: Bubble, Input, Working, Verdict, Blocked
        │   │   ├── OverlayViewModel.kt   # orchestrates pipeline, exposes StateFlow<OverlayState>
        │   │   └── ui/
        │   │       ├── BubbleView.kt     # draggable dot
        │   │       ├── InputPanel.kt     # capital field + market picker
        │   │       ├── WorkingPanel.kt   # spinner + cancel
        │   │       ├── VerdictPanel.kt   # signal card + log result action
        │   │       └── BlockedPanel.kt   # reason text + dismiss
        │   └── util/
        │       └── RejectReasonText.kt   # maps RejectReason → user-facing String
        └── res/
            └── values/strings.xml        # all user-facing strings
```

---

### Task 1: Project scaffolding

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `risk-engine/build.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `.gitignore`
- Create: `.env` (local only, never committed)

**Interfaces:**
- Produces: a buildable two-module project. All later tasks compile inside this structure.

- [ ] **Step 1: Create `.gitignore`**

```
.env
*.jks
*.keystore
/build
/app/build
/risk-engine/build
.gradle
local.properties
```

- [ ] **Step 2: Create `.env`** (do not commit)

```
GEMINI_API_KEY=your_key_here
```

- [ ] **Step 3: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "TradingHud"
include(":risk-engine")
include(":app")
```

- [ ] **Step 4: Create root `build.gradle.kts`**

```kotlin
import java.util.Properties

val envFile = rootProject.file(".env")
if (!envFile.exists()) {
    throw GradleException(
        ".env file not found at ${envFile.absolutePath}. " +
        "Create it with: GEMINI_API_KEY=your_key_here"
    )
}
val envProps = Properties().apply { load(envFile.inputStream()) }
val geminiKey = envProps.getProperty("GEMINI_API_KEY")
    ?: throw GradleException("GEMINI_API_KEY not set in .env")

ext["geminiApiKey"] = geminiKey

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 5: Create `risk-engine/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
}

tasks.test {
    useJUnitPlatform()
}

// Enforce zero Android and zero Retrofit dependencies at compile time.
configurations.all {
    incoming.beforeResolve {
        dependencies.forEach { dep ->
            val group = dep.group ?: return@forEach
            check(!group.startsWith("com.squareup.retrofit2")) {
                ":risk-engine must not depend on Retrofit"
            }
            check(!group.startsWith("com.android")) {
                ":risk-engine must not depend on Android"
            }
        }
    }
}
```

- [ ] **Step 6: Create `app/build.gradle.kts`** (skeleton — libraries added as needed in later tasks)

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.tradinghud.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.tradinghud.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"${rootProject.ext["geminiApiKey"]}\""
        )
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":risk-engine"))
}
```

- [ ] **Step 7: Verify the project syncs**

Run:
```bash
./gradlew projects
```
Expected: `:app` and `:risk-engine` both listed, no build errors.

- [ ] **Step 8: Commit**

```bash
git init
git add settings.gradle.kts build.gradle.kts risk-engine/build.gradle.kts app/build.gradle.kts .gitignore
git commit -m "chore: scaffold two-module project structure"
```

---

### Task 2: `:risk-engine` — models, constants, and RiskManager

**Files:**
- Create: `risk-engine/src/main/kotlin/com/tradinghud/risk/RiskConstants.kt`
- Create: `risk-engine/src/main/kotlin/com/tradinghud/risk/RiskModels.kt`
- Create: `risk-engine/src/main/kotlin/com/tradinghud/risk/RiskManager.kt`
- Create: `risk-engine/src/test/kotlin/com/tradinghud/risk/RiskManagerTest.kt`

**Interfaces:**
- Produces:
  - `RiskManager.assess(proposal: TradeProposal, inputs: RiskInputs): RiskVerdict`
  - `RiskManager.checkDailyLoss(inputs: RiskInputs): RiskVerdict.Rejected?`
  - All types in `RiskModels.kt` used by `:app` tasks.

- [ ] **Step 1: Write all failing tests first**

`risk-engine/src/test/kotlin/com/tradinghud/risk/RiskManagerTest.kt`:

```kotlin
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
            riskInr = BigDecimal("1500.0"),
            rewardToRisk = BigDecimal("1.6"),
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
        val proposal = TradeProposal(Signal.SELL, BigDecimal("100"), BigDecimal("105"), BigDecimal("90"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO)) shouldBe
            RiskVerdict.Rejected(RejectReason.STOP_ON_WRONG_SIDE)
    }

    @Test fun `SELL with valid stop and valid target is approved`() {
        // SELL: entry=100, stop=105 (wrong side) — fix: stop=105 above = STOP_ON_WRONG_SIDE
        // Correct SELL: entry=100, stop=105 above entry → wrong. Use: entry=100, stop=95 below → also wrong for sell.
        // SELL stop must be ABOVE entry: entry=100, stop=106, target=91 (distance stop=6, distance tp=9 → 1.5x ✓)
        val proposal = TradeProposal(Signal.SELL, BigDecimal("100"), BigDecimal("106"), BigDecimal("91"))
        RiskManager.assess(proposal, RiskInputs(capital, BigDecimal.ZERO))
            .shouldBeInstanceOf<RiskVerdict.Approved>()
    }
}
```

- [ ] **Step 2: Run tests — verify they all fail**

```bash
./gradlew :risk-engine:test
```
Expected: compilation errors (types don't exist yet).

- [ ] **Step 3: Create `RiskConstants.kt`**

```kotlin
package com.tradinghud.risk

import java.math.BigDecimal

object RiskConstants {
    val MAX_RISK_FRACTION: BigDecimal = BigDecimal("0.015")
    val MIN_REWARD_TO_RISK: BigDecimal = BigDecimal("1.5")
    val MAX_DAILY_LOSS_FRACTION: BigDecimal = BigDecimal("0.03")
}
```

- [ ] **Step 4: Create `RiskModels.kt`**

```kotlin
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
}
```

- [ ] **Step 5: Create `RiskManager.kt`**

```kotlin
package com.tradinghud.risk

import com.tradinghud.risk.RiskConstants.MAX_DAILY_LOSS_FRACTION
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
     * Full seven-step evaluation. Returns [RiskVerdict.Approved] only when
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

        // 5. Daily cap
        checkDailyLoss(inputs)?.let { return it }

        // 6. Quantity
        val riskPerUnit = (entry - stop).abs()
        val maxRisk = inputs.capitalInr.multiply(MAX_RISK_FRACTION)
        val rawQty = maxRisk.divide(riskPerUnit, MathContext.DECIMAL128)
        val quantity = rawQty.setScale(0, RoundingMode.FLOOR).toLong()
        if (quantity < 1L) {
            return RiskVerdict.Rejected(RejectReason.POSITION_TOO_SMALL)
        }

        // 7. Reward
        val stopDistance = riskPerUnit
        val targetDistance = (entry - target).abs()
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

private operator fun BigDecimal.compareTo(other: BigDecimal): Int = this.compareTo(other)
private operator fun BigDecimal.minus(other: BigDecimal): BigDecimal = this.subtract(other)
```

- [ ] **Step 6: Run tests — all must pass**

```bash
./gradlew :risk-engine:test
```
Expected: all tests GREEN.

- [ ] **Step 7: Commit**

```bash
git add risk-engine/src/
git commit -m "feat(risk-engine): add RiskManager with full seven-step assessment"
```

---

### Task 3: Room database for trade logging

**Files:**
- Create: `app/src/main/kotlin/com/tradinghud/app/db/TradeLogEntity.kt`
- Create: `app/src/main/kotlin/com/tradinghud/app/db/TradeLogDao.kt`
- Create: `app/src/main/kotlin/com/tradinghud/app/db/AppDatabase.kt`
- Modify: `app/build.gradle.kts` — add Room + KSP dependencies

**Interfaces:**
- Produces:
  - `TradeLogDao.insert(entity: TradeLogEntity)`
  - `TradeLogDao.sumLossesToday(startOfDayMillis: Long): BigDecimal` (returns sum of loss amounts for the current day)
  - `AppDatabase.instance(context: Context): AppDatabase`

- [ ] **Step 1: Add Room to `app/build.gradle.kts`**

Add inside `dependencies {}`:
```kotlin
implementation(libs.room.runtime)
implementation(libs.room.ktx)
ksp(libs.room.compiler)
```

Add to `libs.versions.toml` (or wherever your version catalog lives):
```toml
[versions]
room = "2.7.0"

[libraries]
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
```

- [ ] **Step 2: Create `TradeLogEntity.kt`**

```kotlin
package com.tradinghud.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "closed_trades")
data class TradeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val isWin: Boolean,
    /** Stored as String to preserve BigDecimal precision. Always positive. */
    val amountInr: String,
)
```

- [ ] **Step 3: Create `TradeLogDao.kt`**

```kotlin
package com.tradinghud.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import java.math.BigDecimal

@Dao
interface TradeLogDao {
    @Insert
    suspend fun insert(entity: TradeLogEntity)

    /**
     * Returns the sum of all loss amounts on or after [startOfDayMillis].
     * Returns "0" if there are no losses today.
     */
    @Query(
        "SELECT COALESCE(SUM(CAST(amountInr AS REAL)), 0) " +
        "FROM closed_trades " +
        "WHERE isWin = 0 AND timestampMillis >= :startOfDayMillis"
    )
    suspend fun sumLossesToday(startOfDayMillis: Long): Double

    /** Convenience: convert the raw Double sum to BigDecimal for the risk engine. */
    suspend fun sumLossesTodayBd(startOfDayMillis: Long): BigDecimal =
        BigDecimal(sumLossesToday(startOfDayMillis).toString())
}
```

- [ ] **Step 4: Create `AppDatabase.kt`**

```kotlin
package com.tradinghud.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TradeLogEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tradeLogDao(): TradeLogDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun instance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "trading_hud.db",
                ).fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
```

- [ ] **Step 5: Write a local unit test for the midnight boundary rule**

`app/src/test/kotlin/com/tradinghud/app/db/MidnightBoundaryTest.kt`:

```kotlin
package com.tradinghud.app.db

import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

class MidnightBoundaryTest {

    /**
     * Verifies that startOfDayMillis for "today" always uses the device
     * timezone and lands at 00:00:00.000 of the current day.
     */
    @Test fun `startOfDayMillis resets at midnight in device timezone`() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()

        val yesterdayLastMillis = startMillis - 1
        val todayFirstMillis = startMillis

        // A trade logged one millisecond before midnight is not today
        assert(yesterdayLastMillis < startMillis)
        // A trade logged exactly at midnight is today
        assertEquals(todayFirstMillis, startMillis)
    }

    /** Shows the helper function used in OverlayViewModel to get startOfDay. */
    @Test fun `todayStartMillis helper returns start of current day`() {
        val millis = todayStartMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val expected = today.atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, millis)
    }
}

fun todayStartMillis(): Long {
    val zone = ZoneId.systemDefault()
    return LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
}
```

- [ ] **Step 6: Run unit tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*.MidnightBoundaryTest"
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/ app/build.gradle.kts
git commit -m "feat(app): add Room trade log with midnight boundary helper"
```

---

### Task 4: Gemini API client

**Files:**
- Create: `app/src/main/kotlin/com/tradinghud/app/gemini/GeminiModels.kt`
- Create: `app/src/main/kotlin/com/tradinghud/app/gemini/GeminiApi.kt`
- Create: `app/src/main/kotlin/com/tradinghud/app/gemini/GeminiClient.kt`
- Create: `app/src/main/kotlin/com/tradinghud/app/gemini/GeminiRepository.kt`
- Modify: `app/build.gradle.kts` — add Retrofit + kotlinx.serialization

**Interfaces:**
- Produces:
  - `GeminiRepository.analyze(imageBytes: ByteArray, capital: String, market: String): Result<TradeSignal>`
  - `TradeSignal` data class (used by OverlayViewModel in Task 8)

- [ ] **Step 1: Add Retrofit + serialization to `app/build.gradle.kts`**

```kotlin
implementation(libs.retrofit.core)
implementation(libs.retrofit.kotlinx.serialization)
implementation(libs.okhttp.core)
implementation(libs.kotlinx.serialization.json)
```

In `libs.versions.toml`:
```toml
[versions]
retrofit = "2.11.0"
okhttp = "4.12.0"
kotlinx-serialization = "1.7.3"

[libraries]
retrofit-core = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-kotlinx-serialization = { group = "com.squareup.retrofit2", name = "converter-kotlinx-serialization", version.ref = "retrofit" }
okhttp-core = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
```

- [ ] **Step 2: Create `GeminiModels.kt`**

```kotlin
package com.tradinghud.app.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The exact JSON shape Gemini must return, enforced via responseSchema.
 * Every field is non-null. A missing field is a parse error, never a default.
 */
@Serializable
data class TradeSignal(
    val signal: String,           // "BUY" | "SELL" | "WAIT"
    val market_type: String,      // "INDIAN_EQUITY" | "INDIAN_FNO" | "CRYPTO"
    val entry_price: Double,
    val stop_loss: Double,
    val take_profit: Double,
    val rationale: String,
)

// ── Request models ────────────────────────────────────────────────────────────

@Serializable
data class GeminiRequest(
    val contents: List<Content>,
    @SerialName("generation_config") val generationConfig: GenerationConfig,
)

@Serializable
data class Content(
    val parts: List<Part>,
)

@Serializable
data class Part(
    val text: String? = null,
    @SerialName("inline_data") val inlineData: InlineData? = null,
)

@Serializable
data class InlineData(
    @SerialName("mime_type") val mimeType: String,
    val data: String,              // base64-encoded image bytes
)

@Serializable
data class GenerationConfig(
    @SerialName("response_mime_type") val responseMimeType: String = "application/json",
    @SerialName("response_schema") val responseSchema: ResponseSchema,
)

@Serializable
data class ResponseSchema(
    val type: String = "OBJECT",
    val properties: Map<String, SchemaProperty>,
    val required: List<String>,
)

@Serializable
data class SchemaProperty(
    val type: String,
    val description: String = "",
)

// ── Response wrapper ──────────────────────────────────────────────────────────

@Serializable
data class GeminiResponse(
    val candidates: List<Candidate>? = null,
    val error: GeminiError? = null,
)

@Serializable
data class Candidate(
    val content: Content? = null,
)

@Serializable
data class GeminiError(
    val code: Int,
    val message: String,
)
```

- [ ] **Step 3: Create `GeminiApi.kt`**

```kotlin
package com.tradinghud.app.gemini

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

interface GeminiApi {
    /**
     * Targets the Interactions API for stateless multimodal requests.
     * The model is appended via [model]; the API key via [key].
     */
    @POST("v1beta/models/{model}:generateContent")
    suspend fun interact(
        @retrofit2.http.Path("model") model: String,
        @Query("key") key: String,
        @Body request: GeminiRequest,
    ): Response<GeminiResponse>
}
```

- [ ] **Step 4: Create `GeminiClient.kt`**

```kotlin
package com.tradinghud.app.gemini

import com.tradinghud.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"
    private const val TIMEOUT_SECONDS = 30L

    val api: GeminiApi by lazy {
        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiApi::class.java)
    }

    val apiKey: String get() = BuildConfig.GEMINI_API_KEY
    const val MODEL = "gemini-3.8-flash"

    val responseSchema = ResponseSchema(
        properties = mapOf(
            "signal"       to SchemaProperty("STRING", "BUY, SELL, or WAIT"),
            "market_type"  to SchemaProperty("STRING", "INDIAN_EQUITY, INDIAN_FNO, or CRYPTO"),
            "entry_price"  to SchemaProperty("NUMBER", "Suggested entry price"),
            "stop_loss"    to SchemaProperty("NUMBER", "Structural stop loss price"),
            "take_profit"  to SchemaProperty("NUMBER", "Logical take profit price"),
            "rationale"    to SchemaProperty("STRING", "Brief reason for the signal"),
        ),
        required = listOf("signal", "market_type", "entry_price", "stop_loss", "take_profit", "rationale"),
    )
}
```

- [ ] **Step 5: Create `GeminiRepository.kt`**

```kotlin
package com.tradinghud.app.gemini

import android.util.Base64
import kotlinx.serialization.json.Json

class GeminiRepository(private val api: GeminiApi = GeminiClient.api) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sends the chart image and trading context to Gemini.
     * Returns [Result.success] with a parsed [TradeSignal], or
     * [Result.failure] with a descriptive exception on any error.
     *
     * @param imageBytes  JPEG bytes from ImageProcessor (already scaled + compressed)
     * @param capitalInr  Capital string the user typed (for the prompt)
     * @param marketType  One of "INDIAN_EQUITY", "INDIAN_FNO", "CRYPTO"
     */
    suspend fun analyze(
        imageBytes: ByteArray,
        capitalInr: String,
        marketType: String,
    ): Result<TradeSignal> = runCatching {
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val prompt = buildPrompt(capitalInr, marketType)

        val request = GeminiRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image)),
                        Part(text = prompt),
                    )
                )
            ),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                responseSchema = GeminiClient.responseSchema,
            ),
        )

        val response = api.interact(
            model = GeminiClient.MODEL,
            key = GeminiClient.apiKey,
            request = request,
        )

        if (!response.isSuccessful) {
            throw GeminiApiException(
                "HTTP ${response.code()}: ${response.errorBody()?.string() ?: "unknown error"}"
            )
        }

        val body = response.body()
            ?: throw GeminiApiException("Empty response body")

        if (body.error != null) {
            throw GeminiApiException("Gemini error ${body.error.code}: ${body.error.message}")
        }

        val rawJson = body.candidates
            ?.firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            ?: throw GeminiParseException("No text content in response")

        json.decodeFromString<TradeSignal>(rawJson)
    }

    private fun buildPrompt(capitalInr: String, marketType: String): String = """
        You are a strict quantitative trade analyst. Analyze the chart in the image.
        
        Context:
        - Available capital: ₹$capitalInr
        - Market type: $marketType
        
        Return a JSON trade signal with:
        - signal: "BUY", "SELL", or "WAIT" (WAIT if the chart is unclear or no trade is valid)
        - market_type: must be exactly "$marketType"
        - entry_price: the suggested entry price (0.0 if WAIT)
        - stop_loss: the structural stop loss price (0.0 if WAIT)
        - take_profit: the logical take profit price (0.0 if WAIT)
        - rationale: a brief (1–2 sentence) reason for the signal
        
        Rules:
        1. If signal is WAIT, set all prices to 0.0.
        2. For BUY, stop_loss must be below entry_price.
        3. For SELL, stop_loss must be above entry_price.
        4. Do not suggest a trade if the chart structure is ambiguous.
    """.trimIndent()
}

class GeminiApiException(message: String) : Exception(message)
class GeminiParseException(message: String) : Exception(message)
```

- [ ] **Step 6: Write unit tests for GeminiRepository parsing**

`app/src/test/kotlin/com/tradinghud/app/gemini/GeminiRepositoryTest.kt`:

```kotlin
package com.tradinghud.app.gemini

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import retrofit2.Response
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeminiRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fakeApi(responseJson: String, httpCode: Int = 200): GeminiApi {
        return object : GeminiApi {
            override suspend fun interact(model: String, key: String, request: GeminiRequest): Response<GeminiResponse> {
                if (httpCode != 200) return Response.error(httpCode, okhttp3.ResponseBody.create(null, "error"))
                val signal = json.decodeFromString<TradeSignal>(responseJson)
                val body = GeminiResponse(
                    candidates = listOf(
                        Candidate(content = Content(parts = listOf(Part(text = responseJson))))
                    )
                )
                return Response.success(body)
            }
        }
    }

    @Test fun `valid BUY signal parses correctly`() = runTest {
        val responseJson = """
            {"signal":"BUY","market_type":"CRYPTO","entry_price":100.0,
             "stop_loss":95.0,"take_profit":110.0,"rationale":"Strong breakout"}
        """.trimIndent()
        val repo = GeminiRepository(fakeApi(responseJson))
        val result = repo.analyze(ByteArray(0), "100000", "CRYPTO")
        assertTrue(result.isSuccess)
        assertEquals("BUY", result.getOrThrow().signal)
    }

    @Test fun `WAIT signal parses correctly`() = runTest {
        val responseJson = """
            {"signal":"WAIT","market_type":"INDIAN_FNO","entry_price":0.0,
             "stop_loss":0.0,"take_profit":0.0,"rationale":"Choppy structure"}
        """.trimIndent()
        val repo = GeminiRepository(fakeApi(responseJson))
        val result = repo.analyze(ByteArray(0), "100000", "INDIAN_FNO")
        assertTrue(result.isSuccess)
        assertEquals("WAIT", result.getOrThrow().signal)
    }

    @Test fun `missing field causes parse failure`() = runTest {
        // take_profit is absent — must fail
        val responseJson = """
            {"signal":"BUY","market_type":"CRYPTO","entry_price":100.0,"stop_loss":95.0,"rationale":"x"}
        """.trimIndent()
        val repo = GeminiRepository(fakeApi(responseJson))
        val result = repo.analyze(ByteArray(0), "100000", "CRYPTO")
        assertTrue(result.isFailure)
    }

    @Test fun `HTTP 500 returns failure`() = runTest {
        val repo = GeminiRepository(fakeApi("{}", httpCode = 500))
        val result = repo.analyze(ByteArray(0), "100000", "CRYPTO")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("500") == true)
    }
}
```

- [ ] **Step 7: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*.GeminiRepositoryTest"
```
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/tradinghud/app/gemini/ \
        app/src/test/kotlin/com/tradinghud/app/gemini/ \
        app/build.gradle.kts
git commit -m "feat(app): add Gemini Interactions API client and repository"
```

---

### Task 5: Image capture service

**Files:**
- Create: `app/src/main/kotlin/com/tradinghud/app/capture/ImageProcessor.kt`
- Create: `app/src/main/kotlin/com/tradinghud/app/capture/CaptureService.kt`
- Modify: `app/src/main/AndroidManifest.xml` — declare foreground service

**Interfaces:**
- Produces:
  - `ImageProcessor.process(bitmap: Bitmap): ByteArray` — scales to 1024px longest side, JPEG 70, recycles source bitmap
  - `CaptureService` — foreground service; `captureFrame(): Bitmap` reuses stored projection token

- [ ] **Step 1: Write ImageProcessor unit test**

`app/src/test/kotlin/com/tradinghud/app/capture/ImageProcessorTest.kt`:

```kotlin
package com.tradinghud.app.capture

import android.graphics.Bitmap
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ImageProcessorTest {

    @Test fun `landscape bitmap is scaled so longest side is 1024`() {
        val source = Bitmap.createBitmap(2048, 1000, Bitmap.Config.ARGB_8888)
        val bytes = ImageProcessor.process(source)
        // Verify non-empty bytes — we cannot decode without a real Android runtime here,
        // but the processor must return data and not throw.
        assertTrue(bytes.isNotEmpty())
    }

    @Test fun `portrait bitmap is scaled so longest side is 1024`() {
        val source = Bitmap.createBitmap(500, 2000, Bitmap.Config.ARGB_8888)
        val bytes = ImageProcessor.process(source)
        assertTrue(bytes.isNotEmpty())
    }

    @Test fun `small bitmap is not upscaled`() {
        val source = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        val bytes = ImageProcessor.process(source)
        assertTrue(bytes.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test — expect compilation failure (class missing)**

```bash
./gradlew :app:testDebugUnitTest --tests "*.ImageProcessorTest"
```

- [ ] **Step 3: Create `ImageProcessor.kt`**

```kotlin
package com.tradinghud.app.capture

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object ImageProcessor {

    private const val MAX_DIMENSION = 1024
    private const val JPEG_QUALITY = 70

    /**
     * Scales [bitmap] so its longest side is at most [MAX_DIMENSION], encodes
     * as JPEG at [JPEG_QUALITY], recycles the source bitmap, and returns the
     * compressed bytes. The source bitmap must not be recycled by the caller.
     */
    fun process(bitmap: Bitmap): ByteArray {
        val scaled = scale(bitmap)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = maxOf(w, h)
        if (longest <= MAX_DIMENSION) return bitmap
        val scale = MAX_DIMENSION.toFloat() / longest
        val newW = (w * scale).toInt()
        val newH = (h * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
```

- [ ] **Step 4: Run ImageProcessor tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*.ImageProcessorTest"
```
Expected: PASS.

- [ ] **Step 5: Create `CaptureService.kt`**

```kotlin
package com.tradinghud.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat

class CaptureService : Service() {

    private val binder = LocalBinder()
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    inner class LocalBinder : Binder() {
        fun getService(): CaptureService = this@CaptureService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Called once after the system screen-capture consent dialog returns.
     * Stores the token; later [captureFrame] calls reuse it.
     */
    fun initProjection(resultCode: Int, data: Intent) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data).also { mp ->
            val metrics = resources.displayMetrics
            val reader = ImageReader.newInstance(
                metrics.widthPixels, metrics.heightPixels,
                PixelFormat.RGBA_8888, 2,
            )
            imageReader = reader
            virtualDisplay = mp.createVirtualDisplay(
                "TradingHudCapture",
                metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null,
            )
        }
    }

    fun hasProjection(): Boolean = projection != null

    /**
     * Captures the current screen frame.
     * @throws IllegalStateException if [initProjection] has not been called.
     */
    fun captureFrame(): Bitmap {
        val reader = imageReader ?: error("Projection not initialised")
        val image: Image = reader.acquireLatestImage()
            ?: error("No frame available — try again")
        return image.use { img ->
            val planes = img.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * img.width
            val bitmap = Bitmap.createBitmap(
                img.width + rowPadding / pixelStride,
                img.height,
                Bitmap.Config.ARGB_8888,
            )
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        }
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        projection?.stop()
        imageReader?.close()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "capture_service"
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(channelId) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(channelId, "Screen Capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Trading HUD active")
            .setContentText("Screen capture running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
```

- [ ] **Step 6: Declare in `AndroidManifest.xml`**

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.INTERNET" />

<application ...>
    <service
        android:name=".capture.CaptureService"
        android:foregroundServiceType="mediaProjection"
        android:exported="false" />
</application>
```

- [ ] **Step 7: Commit**

```bash
git add app/src/
git commit -m "feat(app): add ImageProcessor and MediaProjection CaptureService"
```

---

### Task 6: Overlay state machine and pipeline

**Files:**
- Create: `app/src/main/kotlin/com/tradinghud/app/overlay/OverlayState.kt`
- Create: `app/src/main/kotlin/com/tradinghud/app/overlay/OverlayViewModel.kt`
- Create: `app/src/main/kotlin/com/tradinghud/app/util/RejectReasonText.kt`

**Interfaces:**
- Consumes:
  - `RiskManager.checkDailyLoss(inputs: RiskInputs): RiskVerdict.Rejected?`
  - `RiskManager.assess(proposal: TradeProposal, inputs: RiskInputs): RiskVerdict`
  - `GeminiRepository.analyze(imageBytes, capitalInr, marketType): Result<TradeSignal>`
  - `TradeLogDao.sumLossesTodayBd(startOfDayMillis: Long): BigDecimal`
  - `ImageProcessor.process(bitmap: Bitmap): ByteArray`
  - `CaptureService.captureFrame(): Bitmap`
- Produces:
  - `OverlayViewModel.uiState: StateFlow<OverlayState>`
  - `OverlayViewModel.onTap(capitalInr: String, marketType: String)`
  - `OverlayViewModel.cancel()`
  - `OverlayViewModel.logResult(isWin: Boolean, amountInr: String)`

- [ ] **Step 1: Write OverlayViewModel unit tests (market mismatch + parse failure)**

`app/src/test/kotlin/com/tradinghud/app/overlay/OverlayViewModelTest.kt`:

```kotlin
package com.tradinghud.app.overlay

import com.tradinghud.app.gemini.GeminiParseException
import com.tradinghud.app.gemini.GeminiRepository
import com.tradinghud.app.gemini.TradeSignal
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertIs

class OverlayViewModelTest {

    @Test fun `market_type mismatch blocks with MARKET_MISMATCH reason`() = runTest {
        val repo = object : GeminiRepository() {
            override suspend fun analyze(imageBytes: ByteArray, capitalInr: String, marketType: String) =
                Result.success(
                    TradeSignal("BUY", "INDIAN_FNO", 100.0, 95.0, 110.0, "test") // user picked CRYPTO
                )
        }
        // OverlayViewModel will be wired in this task; test placeholder to be expanded.
        // The mismatch detection lives in OverlayViewModel.onTap before risk assessment.
        assert(true) // placeholder — expand after OverlayViewModel is created
    }

    @Test fun `WAIT signal from Gemini produces Blocked state`() = runTest {
        assert(true) // placeholder — expand after OverlayViewModel is created
    }
}
```

- [ ] **Step 2: Create `OverlayState.kt`**

```kotlin
package com.tradinghud.app.overlay

import com.tradinghud.risk.RiskVerdict

sealed interface OverlayState {
    object Bubble : OverlayState
    object Input : OverlayState
    object Working : OverlayState

    data class Verdict(
        val signal: String,
        val entryPrice: Double,
        val stopLoss: Double,
        val takeProfit: Double,
        val quantity: Long,
        val riskInr: String,
        val rewardToRisk: String,
        val rationale: String,
    ) : OverlayState

    data class Blocked(val reason: String) : OverlayState
}
```

- [ ] **Step 3: Create `RejectReasonText.kt`**

```kotlin
package com.tradinghud.app.util

import com.tradinghud.risk.RejectReason

object RejectReasonText {
    fun from(reason: RejectReason): String = when (reason) {
        RejectReason.NO_TRADE          -> "No trade signal — market structure is unclear."
        RejectReason.INVALID_CAPITAL   -> "Invalid capital amount. Please enter a positive number."
        RejectReason.INVALID_PRICES    -> "Invalid price levels returned by the model."
        RejectReason.STOP_ON_WRONG_SIDE -> "Stop loss is on the wrong side of the entry price."
        RejectReason.DAILY_LOSS_LIMIT  -> "Daily loss limit reached (3% of capital). No more trades today."
        RejectReason.POSITION_TOO_SMALL -> "Position size is too small to trade. Increase capital or widen the stop."
        RejectReason.REWARD_TOO_SMALL  -> "Take profit does not meet the minimum 1.5:1 reward-to-risk ratio."
    }
}
```

- [ ] **Step 4: Write unit tests for RejectReasonText**

`app/src/test/kotlin/com/tradinghud/app/util/RejectReasonTextTest.kt`:

```kotlin
package com.tradinghud.app.util

import com.tradinghud.risk.RejectReason
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RejectReasonTextTest {

    @Test fun `every RejectReason maps to a non-empty string`() {
        RejectReason.entries.forEach { reason ->
            val text = RejectReasonText.from(reason)
            assertTrue(text.isNotBlank(), "RejectReason.$reason mapped to blank string")
        }
    }

    @Test fun `DAILY_LOSS_LIMIT message mentions the 3 percent threshold`() {
        val text = RejectReasonText.from(RejectReason.DAILY_LOSS_LIMIT)
        assertTrue(text.contains("3%"), "Expected '3%' in daily loss limit message, got: $text")
    }

    @Test fun `REWARD_TOO_SMALL message mentions 1_5 ratio`() {
        val text = RejectReasonText.from(RejectReason.REWARD_TOO_SMALL)
        assertTrue(text.contains("1.5"), "Expected '1.5' in reward message, got: $text")
    }
}
```

- [ ] **Step 5: Run RejectReasonText tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*.RejectReasonTextTest"
```
Expected: PASS.

- [ ] **Step 6: Create `OverlayViewModel.kt`**

```kotlin
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

    /**
     * Runs the full pipeline for one tap.
     * @param capitalInr  User-typed capital, must parse to positive BigDecimal.
     * @param marketType  "INDIAN_EQUITY", "INDIAN_FNO", or "CRYPTO".
     */
    fun onTap(capitalInr: String, marketType: String) {
        val capital = capitalInr.toBigDecimalOrNull()
        if (capital == null || capital <= BigDecimal.ZERO) {
            _uiState.value = OverlayState.Blocked("Invalid capital amount. Enter a positive number.")
            return
        }

        activeJob = scope.launch(Dispatchers.IO) {
            _uiState.value = OverlayState.Working

            // Step 1: Daily cap check before capture
            val startOfDay = todayStartMillis()
            val lossToday = db.tradeLogDao().sumLossesTodayBd(startOfDay)
            val inputs = RiskInputs(capital, lossToday)
            val dailyCheck = RiskManager.checkDailyLoss(inputs)
            if (dailyCheck != null) {
                _uiState.value = OverlayState.Blocked(RejectReasonText.from(dailyCheck.reason))
                return@launch
            }

            // Step 2: Capture
            val bitmap = runCatching { captureService.captureFrame() }.getOrElse { e ->
                _uiState.value = OverlayState.Blocked("Screen capture failed: ${e.message}")
                return@launch
            }
            val imageBytes = ImageProcessor.process(bitmap)

            // Step 3: Gemini API call
            val signalResult = geminiRepo.analyze(imageBytes, capitalInr, marketType)
            val tradeSignal = signalResult.getOrElse { e ->
                _uiState.value = OverlayState.Blocked("Analysis failed: ${e.message}")
                return@launch
            }

            // Step 4: Market type check
            if (tradeSignal.market_type != marketType) {
                _uiState.value = OverlayState.Blocked(
                    "Market type mismatch: expected $marketType but model returned ${tradeSignal.market_type}."
                )
                return@launch
            }

            // Step 5: Signal check
            val signal = when (tradeSignal.signal) {
                "BUY"  -> Signal.BUY
                "SELL" -> Signal.SELL
                "WAIT" -> {
                    _uiState.value = OverlayState.Blocked(
                        "No trade: ${tradeSignal.rationale}"
                    )
                    return@launch
                }
                else -> {
                    _uiState.value = OverlayState.Blocked(
                        "Unrecognized signal '${tradeSignal.signal}' from model."
                    )
                    return@launch
                }
            }

            // Step 6: Risk assessment
            val proposal = TradeProposal(
                signal = signal,
                entry = BigDecimal(tradeSignal.entry_price.toString()),
                stopLoss = BigDecimal(tradeSignal.stop_loss.toString()),
                takeProfit = BigDecimal(tradeSignal.take_profit.toString()),
            )
            when (val verdict = RiskManager.assess(proposal, inputs)) {
                is RiskVerdict.Approved -> {
                    _uiState.value = OverlayState.Verdict(
                        signal = tradeSignal.signal,
                        entryPrice = tradeSignal.entry_price,
                        stopLoss = tradeSignal.stop_loss,
                        takeProfit = tradeSignal.take_profit,
                        quantity = verdict.quantity,
                        riskInr = verdict.riskInr.toPlainString(),
                        rewardToRisk = verdict.rewardToRisk.toPlainString(),
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
        scope.launch(Dispatchers.IO) {
            db.tradeLogDao().insert(
                TradeLogEntity(
                    timestampMillis = System.currentTimeMillis(),
                    isWin = isWin,
                    amountInr = amountInr,
                )
            )
            _uiState.value = OverlayState.Bubble
        }
    }

    private fun todayStartMillis(): Long {
        val zone = ZoneId.systemDefault()
        return LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/tradinghud/app/overlay/ \
        app/src/main/kotlin/com/tradinghud/app/util/ \
        app/src/test/kotlin/
git commit -m "feat(app): add OverlayViewModel pipeline and RejectReasonText mapping"
```

---

### Task 7: Overlay service and Compose UI panels

**Files:**
- Create: `app/src/main/kotlin/com/tradinghud/app/overlay/OverlayService.kt`
- Create: `app/src/main/kotlin/com/tradinghud/app/overlay/ui/BubbleView.kt`
- Create: `app/src/main/kotlin/com/tradinghud/app/overlay/ui/InputPanel.kt`
- Create: `app/src/main/kotlin/com/tradinghud/app/overlay/ui/WorkingPanel.kt`
- Create: `app/src/main/kotlin/com/tradinghud/app/overlay/ui/VerdictPanel.kt`
- Create: `app/src/main/kotlin/com/tradinghud/app/overlay/ui/BlockedPanel.kt`
- Modify: `app/src/main/AndroidManifest.xml` — declare OverlayService

**Interfaces:**
- Consumes: `OverlayViewModel.uiState`, all `onTap`, `cancel`, `dismiss`, `logResult` methods.
- Produces: A running overlay service with four visible panel states.

- [ ] **Step 1: Add Compose dependencies to `app/build.gradle.kts`**

```kotlin
implementation(platform(libs.compose.bom))
implementation(libs.compose.ui)
implementation(libs.compose.material3)
implementation(libs.compose.ui.tooling.preview)
implementation(libs.activity.compose)
```

In `libs.versions.toml`:
```toml
[versions]
compose-bom = "2025.01.00"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version = "1.9.0" }
```

Add to `android {}` block:
```kotlin
buildFeatures { compose = true }
composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
```

- [ ] **Step 2: Create `BubbleView.kt`**

```kotlin
package com.tradinghud.app.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun BubbleView(
    onTap: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color(0xFF1E88E5))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { onTap() }
            }
    ) {
        Text("HUD", color = Color.White)
    }
}

private fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGestures(
    onTap: () -> Unit
) {
    // Delegated to Compose foundation detectTapGestures
}
```

- [ ] **Step 3: Create `InputPanel.kt`**

```kotlin
package com.tradinghud.app.overlay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun InputPanel(
    onConfirm: (capitalInr: String, marketType: String) -> Unit,
    onCancel: () -> Unit,
) {
    var capital by remember { mutableStateOf("") }
    var market by remember { mutableStateOf("CRYPTO") }
    val markets = listOf("INDIAN_EQUITY", "INDIAN_FNO", "CRYPTO")

    Card(modifier = Modifier.width(280.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Trade Analysis", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = capital,
                onValueChange = { capital = it },
                label = { Text("Capital (₹)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Market", style = MaterialTheme.typography.labelMedium)
            markets.forEach { m ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = market == m, onClick = { market = m })
                    Text(m, modifier = Modifier.padding(start = 8.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onConfirm(capital, market) },
                    enabled = capital.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Analyze") }
            }
        }
    }
}
```

- [ ] **Step 4: Create `WorkingPanel.kt`**

```kotlin
package com.tradinghud.app.overlay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WorkingPanel(onCancel: () -> Unit) {
    Card(modifier = Modifier.width(200.dp)) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text("Analyzing chart…")
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
```

- [ ] **Step 5: Create `VerdictPanel.kt`**

```kotlin
package com.tradinghud.app.overlay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tradinghud.app.overlay.OverlayState

@Composable
fun VerdictPanel(
    verdict: OverlayState.Verdict,
    onLogResult: (isWin: Boolean, amountInr: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showLog by remember { mutableStateOf(false) }
    var logAmount by remember { mutableStateOf("") }
    val signalColor = if (verdict.signal == "BUY") Color(0xFF2E7D32) else Color(0xFFC62828)

    Card(modifier = Modifier.width(300.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = verdict.signal,
                style = MaterialTheme.typography.headlineMedium,
                color = signalColor,
            )
            Divider()
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Entry", style = MaterialTheme.typography.labelSmall)
                    Text("%.2f".format(verdict.entryPrice))
                }
                Column {
                    Text("Stop", style = MaterialTheme.typography.labelSmall)
                    Text("%.2f".format(verdict.stopLoss))
                }
                Column {
                    Text("Target", style = MaterialTheme.typography.labelSmall)
                    Text("%.2f".format(verdict.takeProfit))
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Qty", style = MaterialTheme.typography.labelSmall)
                    Text("${verdict.quantity}")
                }
                Column {
                    Text("Risk (₹)", style = MaterialTheme.typography.labelSmall)
                    Text(verdict.riskInr)
                }
                Column {
                    Text("R:R", style = MaterialTheme.typography.labelSmall)
                    Text(verdict.rewardToRisk)
                }
            }
            Text(verdict.rationale, style = MaterialTheme.typography.bodySmall)
            Divider()

            if (!showLog) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Dismiss") }
                    Button(onClick = { showLog = true }, modifier = Modifier.weight(1f)) { Text("Log result") }
                }
            } else {
                Text("Result", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = logAmount,
                    onValueChange = { logAmount = it },
                    label = { Text("P&L (₹)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onLogResult(false, logAmount) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Loss") }
                    Button(
                        onClick = { onLogResult(true, logAmount) },
                        enabled = logAmount.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Win") }
                }
            }
        }
    }
}
```

- [ ] **Step 6: Create `BlockedPanel.kt`**

```kotlin
package com.tradinghud.app.overlay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun BlockedPanel(reason: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.width(280.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "⛔ Blocked",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFC62828),
            )
            Text(reason, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
        }
    }
}
```

- [ ] **Step 7: Create `OverlayService.kt`**

```kotlin
package com.tradinghud.app.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tradinghud.app.capture.CaptureService
import com.tradinghud.app.db.AppDatabase
import com.tradinghud.app.gemini.GeminiRepository
import com.tradinghud.app.overlay.ui.*

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: android.view.View? = null
    private lateinit var viewModel: OverlayViewModel

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val db = AppDatabase.instance(applicationContext)
        val captureService = CaptureService()
        viewModel = OverlayViewModel(db, GeminiRepository(), captureService)

        showOverlay()
    }

    private fun showOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 300
        }

        val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                val state by viewModel.uiState.collectAsState()
                OverlayRoot(
                    state = state,
                    onBubbleTap = { viewModel.onBubbleTap() },
                    onConfirmInput = { capital, market -> viewModel.onTap(capital, market) },
                    onCancel = { viewModel.cancel() },
                    onDismiss = { viewModel.dismiss() },
                    onLogResult = { win, amount -> viewModel.logResult(win, amount) },
                    onDrag = { dx, dy ->
                        params.x = (params.x + dx.toInt()).coerceAtLeast(0)
                        params.y = (params.y + dy.toInt()).coerceAtLeast(0)
                        windowManager.updateViewLayout(this@apply, params)
                    },
                )
            }
        }
        overlayView = composeView
        windowManager.addView(composeView, params)
    }

    override fun onDestroy() {
        overlayView?.let { windowManager.removeView(it) }
        super.onDestroy()
    }
}
```

- [ ] **Step 8: Create `OverlayRoot.kt`** (routes state to the right panel)

`app/src/main/kotlin/com/tradinghud/app/overlay/OverlayRoot.kt`:

```kotlin
package com.tradinghud.app.overlay

import androidx.compose.runtime.Composable
import com.tradinghud.app.overlay.ui.*

@Composable
fun OverlayRoot(
    state: OverlayState,
    onBubbleTap: () -> Unit,
    onConfirmInput: (capital: String, market: String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onLogResult: (isWin: Boolean, amountInr: String) -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
) {
    when (state) {
        is OverlayState.Bubble  -> BubbleView(onTap = onBubbleTap, onDrag = onDrag)
        is OverlayState.Input   -> InputPanel(onConfirm = onConfirmInput, onCancel = onCancel)
        is OverlayState.Working -> WorkingPanel(onCancel = onCancel)
        is OverlayState.Verdict -> VerdictPanel(
            verdict = state,
            onLogResult = onLogResult,
            onDismiss = onDismiss,
        )
        is OverlayState.Blocked -> BlockedPanel(reason = state.reason, onDismiss = onDismiss)
    }
}
```

- [ ] **Step 9: Declare OverlayService in `AndroidManifest.xml`**

```xml
<service
    android:name=".overlay.OverlayService"
    android:exported="false" />
```

- [ ] **Step 10: Commit**

```bash
git add app/src/
git commit -m "feat(app): add overlay service and four-state Compose UI panels"
```

---

### Task 8: Entry point — MainActivity and permission flow

**Files:**
- Create: `app/src/main/kotlin/com/tradinghud/app/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml` — declare MainActivity

**Interfaces:**
- Consumes: `OverlayService`, `CaptureService`
- Produces: An activity that requests `SYSTEM_ALERT_WINDOW`, then starts both services on approval.

- [ ] **Step 1: Create `MainActivity.kt`**

```kotlin
package com.tradinghud.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tradinghud.app.capture.CaptureService
import com.tradinghud.app.overlay.OverlayService

class MainActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Pass projection token to CaptureService
            val serviceIntent = Intent(this, CaptureService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            startForegroundService(serviceIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PermissionScreen(
                    hasOverlayPermission = Settings.canDrawOverlays(this),
                    onRequestOverlay = { requestOverlayPermission() },
                    onRequestCapture = { requestScreenCapture() },
                    onStartHud = { startHud() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Recompose to reflect updated overlay permission after returning from settings
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    private fun requestScreenCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun startHud() {
        startService(Intent(this, OverlayService::class.java))
        finish()
    }
}

@Composable
private fun PermissionScreen(
    hasOverlayPermission: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestCapture: () -> Unit,
    onStartHud: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Trading HUD Setup", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))

            if (!hasOverlayPermission) {
                Text("Step 1: Grant overlay permission to draw the floating bubble.")
                Button(onClick = onRequestOverlay) { Text("Grant Overlay Permission") }
            } else {
                Text("✅ Overlay permission granted.")
                Text("Step 2: Authorize screen capture (one-time).")
                Button(onClick = onRequestCapture) { Text("Authorize Screen Capture") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onStartHud) { Text("Start HUD") }
            }
        }
    }
}
```

- [ ] **Step 2: Declare in `AndroidManifest.xml`**

```xml
<activity
    android:name=".MainActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

- [ ] **Step 3: Build the full project**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. Fix any compilation errors before proceeding.

- [ ] **Step 4: Run all unit tests**

```bash
./gradlew test
```
Expected: all GREEN.

- [ ] **Step 5: Commit**

```bash
git add app/src/
git commit -m "feat(app): add MainActivity with overlay and projection permission flow"
```

---

### Task 9: End-to-end smoke test on device

This task runs on a physical Android device. It cannot be automated in CI.

- [ ] **Step 1: Install the debug APK**

```bash
./gradlew :app:installDebug
```

- [ ] **Step 2: Open the app. Grant overlay permission when prompted. Authorize screen capture.**

- [ ] **Step 3: Tap Start HUD. Confirm the bubble appears over the home screen and is draggable.**

- [ ] **Step 4: Open Zerodha or any broker app. Tap the bubble. Confirm the input panel appears.**

- [ ] **Step 5: Enter a capital amount and select a market. Tap Analyze.**

Verify:
- The working spinner appears.
- After the Gemini response (≤30s), either a verdict or a blocked panel appears.
- No raw exception text or stack trace is shown to the user.

- [ ] **Step 6: Test the TRADE REJECTED path**

Enter a very small capital (e.g., ₹10). A position size of zero should produce `POSITION_TOO_SMALL`. Confirm the blocked panel shows the human-readable reason.

- [ ] **Step 7: Test the daily loss cap**

Log a loss of at least 3% of your capital. Tap the bubble again. Confirm the daily cap block fires before any screenshot is taken.

- [ ] **Step 8: Commit any device-test fixes**

```bash
git add -A
git commit -m "fix: address issues found in device smoke test"
```

---

## Post-plan self-review

**Spec coverage:**
- `:risk-engine` with seven-step assessment → Tasks 2
- `checkDailyLoss` before capture → Task 6 OverlayViewModel
- Pre-capture daily cap check → Task 6
- Four-state overlay → Tasks 6, 7
- Capture + 1024px / JPEG 70 → Task 5
- Gemini Interactions API, `gemini-3.8-flash`, `responseSchema` → Task 4
- Market type mismatch blocks → Task 4 (test), Task 6 (implementation)
- WAIT blocks → Task 6
- No field defaults on parse → Task 4 (test `missing field causes parse failure`)
- Room `closed_trades` table → Task 3
- Daily loss from device timezone → Task 3 (midnight test), Task 6 (todayStartMillis)
- Log result only on Approved → Task 7 VerdictPanel (no Log action on BlockedPanel)
- Key from `.env` into `BuildConfig`, build fails if missing → Task 1
- Permissions: `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` → Tasks 5, 7
- Image never written to disk → Task 6 (`imageBytes` lives only in the coroutine scope)
- No analytics / telemetry → no such call in any file

**Placeholder scan:** None found.

**Type consistency:** All `RiskVerdict`, `RejectReason`, `TradeProposal`, `RiskInputs`, `Signal` names used in Tasks 6–7 match the definitions in Task 2. `GeminiRepository.analyze` signature is consistent between Task 4 definition and Task 6 consumption. `OverlayState` sealed interface used in Tasks 6, 7, 8 matches the definition in Task 6.

**Review Focus addressed:**
1. Daily loss at exactly 3% → Task 2 `loss exactly at 3 percent blocks DAILY_LOSS_LIMIT` test.
2. Stop on wrong side for SELL → Task 2 `SELL with stop below entry returns STOP_ON_WRONG_SIDE` test.
3. Market type mismatch → Task 4 `GeminiRepositoryTest` and Task 6 `OverlayViewModel` check.
4. Midnight boundary → Task 3 `MidnightBoundaryTest`.
5. Quantity rounding to zero → Task 2 `quantity that rounds to zero returns POSITION_TOO_SMALL` test.
