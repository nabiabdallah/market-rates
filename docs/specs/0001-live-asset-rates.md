# Spec: Live rates for a configured list of assets

- Status: Draft
- Owner: repository owner (issue #3)
- Related ADRs: 0001 (hexagonal architecture)

## Problem
Consumers need one place to read the current market rate of a fixed set of assets (metals, FX, an equity, an
index and crypto pairs) without integrating each data provider themselves. The set of assets must be
changeable by configuration, without a code change, as long as the asset is of a kind the service supports.

## Scope
**In:**
- Read-only endpoints returning the last known rate for each configured asset, or for one asset.
- An asset list in a config file. Each entry: `id`, `name`, `kind` (`METAL`, `FX`, `EQUITY`, `INDEX`,
  `CRYPTO`), `provider`, the provider's `symbol`, and `quoteCurrency`.
- Initial assets: Gold, Silver (metals); EUR/USD (FX); Nvidia (equity); S&P 500 (index); BTC/USDT,
  ETH/USDT, QNT/USDT (crypto).
- Background polling of each provider at a configured interval; the last value is kept in memory with the
  time it was fetched.
- One outbound port for rates, one adapter per provider, under `adapter/out/<provider>`.
- Provider API keys read from the environment or a secret, never logged.

**Out (explicitly not doing):**
- Streaming (WebSocket/SSE) or push of rates.
- Historical rates, charts, OHLC, volume.
- Currency conversion between quote currencies.
- Adding assets at runtime through an API.
- Persistence: rates live in memory only; after a restart there is no rate until the first fetch.
- Support for a new `kind` or a new provider (needs code: a new adapter).

## Contract
`GET /api/v1/rates` -> 200
```json
{ "rates": [ { "id": "gold", "name": "Gold", "kind": "METAL", "rate": "2650.4000",
               "quoteCurrency": "USD", "asOf": "2026-11-14T10:15:30Z", "stale": false } ] }
```
`GET /api/v1/rates/{id}` -> 200 with one element of the above; 404 problem if `id` is not configured.

Fields: `id` string (config key, lowercase kebab, e.g. `btc-usdt`); `name` string; `kind` enum; `rate`
decimal as a JSON string (never a float number); `quoteCurrency` string (ISO 4217, or `USDT` for crypto
pairs); `asOf` ISO-8601 UTC instant at which the provider produced or the service fetched the value
(assumes Q6); `stale` boolean.

Rate: `BigDecimal` with explicit scale and `RoundingMode` (scale per asset kind, configured; default
rounding HALF_EVEN). Errors are RFC 9457 problem responses via the existing `ErrorHandler`.

Asset with no rate fetched yet: omitted from the list response? No: listed with `rate` and `asOf` null and
`stale` true (assumes Q3); single-asset lookup returns 503 problem (assumes Q3).

Config (`application` properties, `market-rates.assets[]`, `market-rates.poll-interval`,
`market-rates.stale-after`); secrets via environment, e.g. `MARKET_RATES_<PROVIDER>_API_KEY`.

## Authorization
The service has no authentication today. Assumes Q5: the endpoints are public read-only: any caller may read
every configured asset; the data is not per-caller. If Q5 is answered otherwise, this section and the
criteria change.

## Acceptance criteria
1. Given the default config, when the service starts, then all nine initial assets are configured with
   kind, provider, symbol and quote currency.
2. Given an asset entry for an existing kind and provider is added to the config file, when the service
   restarts, then it is polled and returned by `GET /api/v1/rates` with no code change.
3. Given rates were fetched for all assets, when `GET /api/v1/rates` is called, then 200 with one entry per
   configured asset, each with `rate`, `quoteCurrency`, `asOf` and `stale=false`.
4. Given a rate was fetched, when `GET /api/v1/rates/{id}` is called, then 200 with that asset's entry.
5. Given an unknown `id`, when `GET /api/v1/rates/{id}` is called, then 404 problem; the message does not
   contain the requested id.
6. Given the provider returns `2650.123456`, when mapped, then the rate is a `BigDecimal` rounded to the
   configured scale with the configured rounding mode, and serialised as a JSON string.
7. Given a provider response with a non-positive, missing or unparsable rate, when fetched, then the value is
   discarded, the previous rate is kept, and the failure is logged without the response body.
8. Given the last successful fetch is older than `stale-after`, when read, then the last rate is returned with
   `stale=true` and its original `asOf` (assumes Q3).
9. Given a provider fails (timeout, 5xx, 429), when a poll runs, then the last known rates of its assets are
   still served, other providers' assets are unaffected, and the next poll still happens at the interval.
10. Given no rate was ever fetched for an asset, when listed, then it appears with null `rate`/`asOf` and
    `stale=true`; when read by id, then 503 problem (assumes Q3).
11. Given a poll and a read happen concurrently, when the read runs, then it sees either the old or the new
    complete rate (rate and `asOf` never mixed).
12. Given a provider API key is configured, when the service logs (startup, errors, requests), then the key
    never appears in logs, exception messages or responses.
13. Given an asset entry names an unknown provider or kind, or a duplicate `id`, or a missing symbol, when the
    service starts, then startup fails with a message naming the entry's index, not secrets.
14. Given the application layer and domain, when the architecture test runs, then no provider class or
    framework type is referenced outside `adapter/out/<provider>` and `config`.
15. Given provider calls, when they run, then each has a connect and read timeout, and metrics/spans use no
    asset id or symbol as a tag beyond what ADR 0014 allows (assumes ADR 0014 as in CLAUDE.md).

## Failure modes
- Unknown asset id: 404. Never-fetched single asset: 503 (assumes Q3).
- Provider timeout/5xx/429/invalid payload: keep last value, log at WARN with provider name and status only,
  no body, no key.
- Provider key missing for a configured provider: startup fails (Q1 decides the provider).
- Misconfigured asset list: startup fails (criterion 13).
- Duplicate request / another caller's data: not applicable, read-only public data.

## Rollout
No schema change and no persistence. New endpoints and new config only, so old and new pods can coexist; a
new pod serves "no rate yet" until its first poll completes (see Q7 for readiness).

## Non-functional
- Reads are served from memory: no provider call on the request path; p99 under 50 ms.
- Provider call volume is bounded by assets x poll interval, independent of caller traffic; the interval must
  respect provider rate limits.
- Logs carry provider name and asset id, never keys; no PII exists in this feature.
- Retention: none, in-memory only.

## Open questions
- Q1. Which provider for each class, and does an API key exist? Recommended: a keyless public exchange API for the crypto pairs (e.g. Binance spot); one keyed provider (owner to name, with key) for metals, FX, equity and index. Without a name, only crypto can be built first.
- Q2. How fresh must "live" be? Recommended: poll every 10 s for crypto, 60 s for the others, configurable per provider; no streaming.
- Q3. When a provider is down or the rate is old? Recommended: serve the last rate with `asOf` and `stale=true` (stale after 3 poll intervals); error only when never fetched.
- Q4. Quote currency for metals, equity and index? Recommended: USD; crypto in USDT as named (no conversion to USD).
- Q5. Who may call it? Recommended: public read-only for now (no auth exists in the service); add scopes when authentication is introduced.
- Q6. What does `asOf` mean? Recommended: the provider's quote time when supplied, else the fetch time.
- Q7. Should readiness wait for the first successful poll? Recommended: no, readiness stays up; rates show as never-fetched until the first poll.
