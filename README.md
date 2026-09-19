# StockPulse

StockPulse is a human-in-the-loop inventory and dynamic pricing advisor. A stock or demand signal creates price and reorder recommendations; only a merchandiser acceptance changes live price or stock.

## Simple overview

StockPulse helps an online store react before stock problems become expensive. When stock drops below its reorder threshold or demand becomes unusually high, it creates two suggestions: a new price and a reorder quantity. AI can generate the advice, but a merchandiser always makes the final decision.

## Tech stack

- **Backend:** Java 17, Spring Boot 3, Spring Data JPA, H2 database.
- **Frontend:** React 18 and Vite.
- **AI:** supplied LiteLLM OpenAI-compatible gateway with `qwen-cursor`.
- **Safety:** rule-based fallback, price/quantity validation, async processing, and human approval.

## Where AI is used

AI runs only when the active strategy is `AI` and the gateway credentials are configured. It receives product price, stock, reorder threshold, demand velocity, category context, and the trigger reason. It returns one paired recommendation for price and replenishment. The top of the console explicitly says either **AI advisor: ON · qwen-cursor**, **rule fallback**, or the source on each recommendation: **AI · qwen-cursor**, **RULE ENGINE**, or **RULE FALLBACK**.

<img width="1897" height="740" alt="image" src="https://github.com/user-attachments/assets/b59ce4bf-9fa6-4046-934d-91cef5ecc8a2" />
<img width="1890" height="857" alt="image" src="https://github.com/user-attachments/assets/256fae30-58e6-4870-8aeb-a8b38415cdca" />
<img width="1892" height="857" alt="image" src="https://github.com/user-attachments/assets/68363068-a4af-4001-99c2-d703521ee37b" />

## Human workflow

1. Click **Simulate sale** or receive a real stock/order update.
2. The system detects low stock or a demand spike in the background.
3. Review the price and reorder recommendation, reasoning, confidence, source, and business impact.
4. Click **Accept** or **Reject**. Accepting a price changes it; accepting a reorder simulates inbound stock.
5. The Decision Journal records the actual decision time, status, trigger, and source.

## Run locally

### Prerequisites (any Windows/macOS/Linux system)

- Java 17 or 21 (JDK, not only JRE). Confirm with `java -version`.
- Maven 3.9+ available as `mvn -v`.
- Node.js 20+ and npm 10+ available as `node -v` and `npm -v`.
- Internet access only if using AI mode; rule mode is fully offline after dependency installation.
- Port `8080` free for the backend and port `5173` free for the React UI.

### First run

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12'
cd backend; mvn spring-boot:run
cd ../frontend; npm install; npm run dev
```

Open `http://localhost:5173`. The backend runs on `http://localhost:8080` and includes seed data. The app works in `rule` mode with no external credentials. To enable AI recommendations, copy `.env.example` to a private environment and set `STRATEGY=ai` plus the LLM values. The API key is intentionally not present in this repository.

### Run checks

From `backend`, run `mvn test` to compile and run the backend test phase. From `frontend`, run `npm run build` to verify that React can produce a production build. Before a demo, open `http://localhost:8080/system/status`: it reports the active strategy, whether the gateway is configured, the model name, and whether approval is required.

After starting the backend, run `powershell -ExecutionPolicy Bypass -File .\smoke-test.ps1` from the project root. It verifies catalog/filtering, product creation, stock update, asynchronous paired recommendations, recommendation source, approve/reject side effects, and decision timestamps.

### Supplied LiteLLM gateway

The AI adapter is configured for the supplied OpenAI-compatible LiteLLM endpoint, including its required `product` and cookie headers.

1. Copy `.env.example` to `.env` beside `README.md`.
2. Privately paste the supplied gateway values into `LLM_API_KEY` and `LLM_COOKIE`; keep the supplied URL, `PC1`, and `qwen-cursor` defaults.
3. Start the AI backend with `powershell -ExecutionPolicy Bypass -File .\start-ai.ps1`.
4. In another terminal, start the React frontend with `cd frontend; npm install; npm run dev`.

Do **not** add credential values to source files, a ZIP, or GitHub. `.env` is ignored by Git. In `rule` mode the system remains fully functional without network or LLM access.

### Move to another computer

1. Copy the complete `StockPulse` folder—do not copy only `backend` or `frontend`.
2. Install the prerequisites above.
3. Create a new `.env` from `.env.example` and paste private gateway credentials only on that computer if AI mode is needed.
4. Run `start-ai.ps1` for AI mode, or run `mvn spring-boot:run` in `backend` for rule mode.
5. Run `npm install` then `npm run dev` in `frontend`.

## Demo path

1. Open the console and choose **Hoodie — Heather Grey**.
2. Simulate sales until it crosses its reorder threshold (or use the T-Shirt, already below threshold).
3. Refresh/poll the suggestion queue; an asynchronous `INVENTORY_LOW` pair appears.
4. Accept the pricing suggestion to update the live price; accept a reorder to simulate inbound stock.

## Real-world operating flow

1. An order, warehouse scan, or integration calls the stock/order API.
2. The API saves the inventory change and returns immediately; it does not wait on the LLM.
3. An asynchronous listener checks for below-threshold inventory or an abnormal demand velocity against category peers.
4. The advisor produces a paired price and reorder recommendation. AI mode validates its output and uses rule mode if anything goes wrong.
5. The console polls for the new recommendations. A merchandiser reads the reason and confidence, then accepts or rejects each decision.
6. Accepting a price changes the live product price; accepting a reorder adds simulated inbound stock. In production, this last step is the seam for a supplier/PO API.

## Console usability details

- Polling status and a manual refresh action so users know whether current data is visible.
- Loading and API-error states with a retry action.
- Category filter for larger catalogs.
- Scrollable catalog and decision queue on desktop; mobile expands naturally for touch scrolling.
- Stock heat indicators and progress meters: healthy, watch, low stock, and out of stock.
- Product status, current price, stock, threshold, and 24-hour demand shown together.
- Simulate sale for fast demonstrations and **Request advice** for manual merchandising review.
- Explicit auto/manual and trigger-reason badges, confidence bars, and separate accept/reject actions.

## Live updates and repeated actions

- A sale click immediately creates one order event: stock decreases by one and 24-hour demand increases by one. The button locks while that request is being saved, preventing accidental double-submission from rapid clicks.
- A later click is treated as a separate deliberate sale, so the catalog, stock-runway calculation, and demand number update again after polling refreshes.
- When a product crosses from at/above its reorder threshold to below it, the console displays a low-stock notification with the current stock and threshold. Products that are already low at startup are flagged too.
- When the asynchronous advisor finishes, the console announces the new price or reorder recommendation. Notifications are also dismissible and disappear automatically.
- Repeated low-stock or demand events cannot create duplicate **pending** recommendations for the same product, trigger, and suggestion type. This avoids alert spam while still recording every real sale.

## Architecture

`InventoryService` publishes an `InventorySignal`; `RecommendationListener` handles it asynchronously and calls the same `CommerceAdvisor` contract used by the manual endpoints. A registry resolves `rule` or `ai` on every request, so changing `app.strategy` changes behavior without redeploying. The AI adapter uses one structured call returning both recommendations, validates its result, and falls back to the deterministic advisor on timeout, parsing error, or unsafe values.

See [ADR.md](ADR.md) for decisions, tradeoffs, and sprint-two seams.

## Why StockPulse stands out

1. **It is agentic, not just a dashboard.** A stock or demand event automatically creates advice in the background; a user does not have to notice an alert and press a “generate” button.
2. **It makes one complete merchandising decision.** Each signal produces a linked pricing *and* replenishment suggestion, so the team can protect stock while planning how to refill it.
3. **Humans stay in control.** Suggestions never change a live price or stock quantity on their own. Accepting a price suggestion applies the price; accepting a reorder simulates the inbound shipment.
4. **It is safe when AI is unavailable.** The LLM output is checked for malformed JSON, impossible confidence, unsafe price movement, and invalid reorder quantities. Any failure falls back to deterministic rules instead of losing the recommendation.
5. **It is explainable.** Every recommendation contains a trigger badge, confidence score, current-versus-proposed outcome, and plain-language reasoning that a merchandiser can challenge.
6. **It avoids recommendation noise.** The asynchronous loop skips duplicate pending suggestions of the same type for the same product and trigger.
7. **It is configurable without redeployment.** Rule-based and AI advisors share one contract; changing `app.strategy` chooses the active advisor at runtime, and a future competitor-aware advisor can plug in without changing controllers or event handlers.
8. **It is designed for the next sprint.** The data model includes optional cost and margin-floor seams, while the architecture leaves room for competitor data, supplier selection, cooldowns, SSE, and purchase-order automation.

## Differentiators added beyond the brief

- **Stock runway:** every product estimates how many days of inventory remain from its current 24-hour demand velocity. The catalog is prioritised by shortest runway, not merely by SKU order.
- **Decision impact:** price advice shows the exact currency and percentage change; reorder advice shows estimated post-reorder coverage. Reviewers can assess the business consequence before approving.
- **Decision journal:** accepted and rejected pricing/reorder decisions remain visible with their trigger and timestamp, making the approval trail auditable rather than ephemeral.

These are deliberately deterministic operational aids. They complement the LLM rather than relying on it for basic visibility or accountability.
