# Phase 2 Final Hardening and Verification Plan

**Goal:** Finalize security, precision, and testing integrity for real-money deployment.

**Spec:** [docs/superpowers/specs/2026-09-26-android-trading-hud-design.md](docs/superpowers/specs/2026-09-26-android-trading-hud-design.md)

## Tasks
- [ ] Part 1: BigDecimal Purity & Risk-Engine Safety (Fix `toLongExact`, new `CALCULATION_ERROR`, Audit `assess` exceptions)
- [ ] Part 2: Test Integrity (MidnightBoundaryTest timezone handling, DailyLossPurityTest assertions)
- [ ] Part 3: Code Cleanup (Dead extensions, redundant toStrings, hardcoded constant removal)
- [ ] Part 4: Security Hardening & Integration Review (AndroidManifest, NetworkSecurityConfig, API Key logging, Gemini API resilience, Sanity checks)
- [ ] Part 5: Final Verification (Static analysis, Full Suite, Hand-worked logic audit)
