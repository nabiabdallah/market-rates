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
  cannot fail the rest. Worst-case poll time is assets x (connect + read timeout, default 1 s + 2 s). Longer
  than the poll interval is a startup warning, not a failure: polls are fixed-delay, so a slow one delays the
  next and never overlaps it, and a startup failure capped the service at three assets, against criterion 2.
- **Rate limits.** After HTTP 429 or 418 the adapter sends nothing more in that poll; the rates already fetched
  are kept and the next poll tries again. `Retry-After` is not read yet.
- **No leaks.** Adapters and the service log provider name, asset id and HTTP status or exception class only.
  Never an exception message, URL, query string or body (client exceptions embed them); no cause is chained.
- **The adapter observes its own calls.** RestClient records its exception on its observation the moment a
  call fails, and a tracing handler exports it at once, before an `ObservationFilter` runs; that message is the
  full URL with the symbol. So the RestClient gets `ObservationRegistry.NOOP`, and each call is one
  `market.rates.provider.request` observation with the tags `provider`, `uri` (the template) and `outcome`, and
  a `ProviderCallError` (status or exception class only) as its error. The cost: no `http.client.requests`
  metric and no trace headers on provider calls.
- **503 for "no rate yet".** `RateUnavailableException` (a `DomainException`) maps to 503, not 422, because the
  request is valid and the data is temporarily unavailable. This extends the 404/422 mapping in CLAUDE.md.

## Alternatives considered
- One batched Binance request: fewer calls, but one bad symbol fails the whole request.
- Fetch on request with a cache: puts the provider on the request path and ties call volume to caller traffic.

## Consequences
- Rates are lost on restart until the first poll; readiness does not wait for it.
- A second provider needs its own adapter, its own timeout settings and its own budget warning.
- Review of PR #5 (2026-11-14): the first version failed startup on a fourth asset and sanitised span errors in
  an `ObservationFilter`, which runs too late. Both are changed above.

## Amendment (spec 0002): second Binance provider
- `binance-futures` is a second provider of the same Binance integration. It reuses `BinanceRateGateway`,
  parameterised by provider name, ticker path and whether the response carries a quote time, instead of getting a
  separate adapter class. It has its own base URL, timeouts and budget warning, and its own poll task.
- The poll interval stays global even with two providers (spec 0002).
- For futures, `asOf` is the response `time` (the provider's quote time); it falls back to the fetch time when absent.
