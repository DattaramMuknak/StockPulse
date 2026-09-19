# Architecture Decision Record — StockPulse

## 1. Put commerce decisions behind a dedicated advisor
**Context.** HTTP endpoints and asynchronous inventory events both need pricing and replenishment advice.  
**Options.** Put it in product services, embed it in entities, or introduce an advisor contract.  
**Decision.** `CommerceAdvisor` returns a combined `RecommendationBundle`; persistence and event publication remain in services.  
**Tradeoff.** More types and mapping code, in exchange for a testable boundary and a future `CompetitorAwareAdvisor` that does not modify callers.

## 2. Use one AI call for a paired recommendation
**Context.** A stock event is one merchandising decision involving both price protection and replenishment.  
**Options.** Separate AI calls or one structured bundle.  
**Decision.** One call returns both recommendations, reducing latency, cost, and contradictory advice.  
**Tradeoff.** Price and reorder cannot independently use different providers yet; deterministic fallback still creates both.

## 3. Resolve the strategy through a runtime registry
**Context.** The active rule/AI strategy must change without restart and must work in HTTP and async paths.  
**Options.** Conditional logic in controllers, a factory at startup, or a registry selected at call time.  
**Decision.** `AdvisorRegistry` reads `app.strategy` on every call and looks up an advisor by key.  
**Tradeoff.** Misconfiguration is discovered at request time, so it safely defaults to `rule`.

## 4. Treat LLM output as untrusted input
**Context.** The LLM can time out, emit malformed JSON, or suggest impossible values.  
**Options.** Fail the event, persist unvalidated AI text, or validate and fall back.  
**Decision.** Bound price to 50–150% of the current price, require confidence 0–1 and positive reorder quantity; on any failure use the rule advisor.  
**Tradeoff.** A fallback is less tailored, but the async loop never silently loses a merchandising signal.

## 5. Decouple inventory updates with async domain events
**Context.** Sales and stock updates must return immediately while recommendation generation may call a slow model.  
**Options.** Call AI in the request, poll on a cron, or publish an event.  
**Decision.** `InventorySignal` is handled with `@Async`; duplicate pending suggestion types for the same product/trigger are skipped.  
**Tradeoff.** UI polling is eventual rather than real-time. SSE is an intentional sprint-two enhancement.

## 6. Preserve sprint-two seams without building sprint two
**Context.** Margin floors, suppliers, competitor prices, cooldowns, and PO creation are deferred.  
**Decision.** Product contains optional `costPrice` and `marginFloor` fields; the advisor contract and registry admit new strategies.  
**Tradeoff.** We do not scrape competitors or issue purchase orders in this sprint, keeping human approval explicit.
