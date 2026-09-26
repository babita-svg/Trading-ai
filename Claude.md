# Project: Private AI Trading HUD (Real Money)

## Architecture
- Backend: Python (FastAPI), connecting to broker WebSockets and Gemini API.
- Frontend: Mobile-responsive Web Dashboard (served locally) to act as the HUD.
- Environment: Must run headless on Android Termux (Node.js/Python) and MacOS/Windows.

## Core Trading Directives (NON-NEGOTIABLE)
1. **Real Money Guardrails:** This system trades live capital. Do not write code that assumes a 100% win rate. All logic must account for market invalidation and strict Stop Losses.
2. **Max Risk Cap:** Hardcode a circuit breaker. Maximum risk per trade is STRICTLY 1% to 2% of total capital. 
3. **Volatility Sizing:** Position size is ALWAYS calculated as: `(Capital * Risk %) / (Entry - Stop Loss)`. Never size based on the user's desired profit.
4. **Data Over Vision:** Prioritize raw OHLCV broker data over screen parsing. Use Gemini API purely for structural chart validation and confluence scoring.

## System Prompt Memory
- The Gemini API must always be initialized with the JSON schema requiring `market_regime`, `confluence_score_percent`, and `user_profit_target_status`.
- If a target is unfeasible based on the 14-day Average True Range (ATR), the code must reject the trade or suggest an `adjusted_profit_target_inr`.