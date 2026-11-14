# 0002. Serve rates from an in-memory snapshot refreshed by per-provider polling

- Status: Accepted
- Date: 2026-11-14
- Deciders: repository owner (spec 0001)

## Context
Spec 0001 serves live asset rates without a provider call on the request path, keeps working when a provider
is down, and must not expose provider URLs, symbols or response bodies in logs or telemetry.

## Decision
- **Snapshot cache.** `RatesService` keeps one immutable `Snapshot(rate, asOf, fetchedAt)` per asset in a
  `ConcurrentHashMap`, replaced as a whole, so a reader never sees a rate with another fetch's `asOf`.
  Staleness is `now - fetchedAt > stale-after`; the last rate is served with `stale=true`.
- **Polling.** One fixed-delay task per provider on its own scheduler thread (`config/RatePollingConfig`).
  A provider's polls never overlap, so each snapshot has one writer. Poll interval is global
  (`market-rates.poll-interval`), a simplification of "configurable per provider" until a second provider exists.
- **Sequential calls, bounded time.** The Binance adapter calls once per symbol so an unlisted symbol (HTTP 400)
  cannot fail the rest. Worst-case poll time is assets x (connect + read timeout, default 1 s + 2 s); startup
  fails if that exceeds the poll interval.
- **No leaks.** Adapters and the service log provider name, asset id and HTTP status or exception class only.
  Never an exception message, URL, query string or body (client exceptions embed them); no cause is chained.
  The RestClient observation drops `http.url`; the `uri` tag is the template.
- **503 for "no rate yet".** `RateUnavailableException` (a `DomainException`) maps to 503, not 422, because the
  request is valid and the data is temporarily unavailable. This extends the 404/422 mapping in CLAUDE.md.

## Alternatives considered
- One batched Binance request: fewer calls, but one bad symbol fails the whole request.
- Fetch on request with a cache: puts the provider on the request path and ties call volume to caller traffic.

## Consequences
- Rates are lost on restart until the first poll; readiness does not wait for it.
- A second provider needs its own adapter and timeout budget check.
