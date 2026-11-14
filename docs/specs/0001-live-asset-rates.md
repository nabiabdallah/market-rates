# Spec: Live rates for a configured list of assets

- Status: Approved
- Owner: repository owner (issue #3)
- Related ADRs: 0001 (hexagonal architecture)

## Problem
Consumers need one place to read the current market rate of a fixed set of assets (metals, FX, an equity, an
index and crypto pairs) without integrating each data provider themselves. The set of assets must be
changeable by configuration, without a code change, as long as the asset is of a kind the service supports.

This first build covers the crypto pairs only (owner, 2026-11-14). Metals, FX, the equity and the index need a
keyed provider the owner has not named yet; they get their own spec.

## Scope
**In:**
- Read-only endpoints returning the last known rate for each configured asset, or for one asset.
- An asset list in a config file. Each entry: `id`, `name`, `kind` (`CRYPTO` in this build), `provider`, the
  provider's `symbol`, and `quoteCurrency`.
- Initial assets: BTC/USDT, ETH/USDT, QNT/USDT.
- One provider: the Binance spot public REST API, which needs no API key (`provider: binance`).
- Background polling of each provider at a configured interval (default 10 s); the last value is kept in
  memory with the time it was fetched. A rate is stale after 3 poll intervals (default 30 s).
- One outbound port for rates, one adapter per provider, under `adapter/out/<provider>`.

**Decided (issue #3, 2026-11-14).** The owner chose crypto first. The other answers are the draft's
recommendations, which the owner accepts by merging this spec:
- Provider: Binance spot public API for the crypto pairs; no key.
- Freshness: poll every 10 s, configurable; no streaming. One interval for all providers until a second
  provider exists (ADR 0002).
- Provider down or rate old: the last rate with `asOf` and `stale=true`; an error only when never fetched.
- Quote currency: USDT as named; no conversion to USD.
- Callers: public read-only; scopes come when the service gets authentication.
- `asOf`: the provider's quote time when supplied, else the fetch time.
- Readiness does not wait for the first poll.

**Out (explicitly not doing):**
- Streaming (WebSocket/SSE) or push of rates.
- Historical rates, charts, OHLC, volume.
- Currency conversion between quote currencies.
- Adding assets at runtime through an API.
- Persistence: rates live in memory only; after a restart there is no rate until the first fetch.
- Support for a new `kind` or a new provider (needs code: a new adapter).
- Gold, Silver, EUR/USD, Nvidia and the S&P 500, and with them the kinds `METAL`, `FX`, `EQUITY`, `INDEX` and
  any provider API key: a later spec, once the owner names the keyed provider.

## Contract
`GET /api/v1/rates` -> 200
```json
{ "rates": [ { "id": "btc-usdt", "name": "BTC/USDT", "kind": "CRYPTO", "rate": "96250.41000000",
               "quoteCurrency": "USDT", "asOf": "2026-11-14T10:15:30Z", "stale": false } ] }
```
`GET /api/v1/rates/{id}` -> 200 with one element of the above; 404 problem if `id` is not configured.

Fields: `id` string (config key, lowercase kebab, e.g. `btc-usdt`); `name` string; `kind` enum; `rate`
decimal as a JSON string (never a float number); `quoteCurrency` string (`USDT` for the
initial pairs); `asOf` ISO-8601 UTC instant: the provider's quote time when supplied, else the time the
service fetched the value; `stale` boolean.

Rate: `BigDecimal` with explicit scale and `RoundingMode` (scale per asset kind, configured; default
rounding HALF_EVEN). Errors are RFC 9457 problem responses via the existing `ErrorHandler`.

Asset with no rate fetched yet: omitted from the list response? No: listed with `rate` and `asOf` null and
`stale` true; single-asset lookup returns 503 problem.

Config (`application` properties, `market-rates.assets[]`, `market-rates.poll-interval`,
`market-rates.stale-after`). This build reads no secret.

## Authorization
The service has no authentication today. The endpoints are public read-only: any caller may read every
configured asset; the data is not per-caller.

## Acceptance criteria
1. Given the default config, when the service starts, then the three initial assets (BTC/USDT, ETH/USDT,
   QNT/USDT) are configured with kind `CRYPTO`, provider `binance`, symbol and quote currency `USDT`.
2. Given an asset entry for an existing kind and provider is added to the config file, when the service
   restarts, then it is polled and returned by `GET /api/v1/rates` with no code change.
3. Given rates were fetched for all assets, when `GET /api/v1/rates` is called, then 200 with one entry per
   configured asset, each with `rate`, `quoteCurrency`, `asOf` and `stale=false`.
4. Given a rate was fetched, when `GET /api/v1/rates/{id}` is called, then 200 with that asset's entry.
5. Given an unknown `id`, when `GET /api/v1/rates/{id}` is called, then 404 problem; the message does not
   contain the requested id.
6. Given the provider returns `96250.123456789`, when mapped, then the rate is a `BigDecimal` rounded to the
   configured scale with the configured rounding mode, and serialised as a JSON string.
7. Given a provider response with a non-positive, missing or unparsable rate, when fetched, then the value is
   discarded, the previous rate is kept, and the failure is logged without the response body.
8. Given the last successful fetch is older than `stale-after`, when read, then the last rate is returned with
   `stale=true` and its original `asOf`.
9. Given a provider fails (timeout, 5xx, 429), when a poll runs, then the last known rates of its assets are
   still served, other providers' assets are unaffected, and the next poll still happens at the interval.
10. Given no rate was ever fetched for an asset, when listed, then it appears with null `rate`/`asOf` and
    `stale=true`; when read by id, then 503 problem.
11. Given a poll and a read happen concurrently, when the read runs, then it sees either the old or the new
    complete rate (rate and `asOf` never mixed).
12. Given the default config, when the service starts and polls, then no API key or other secret is required
    or read, and provider request URLs are logged without query strings.
13. Given an asset entry names an unknown provider or kind, or a duplicate `id`, or a missing symbol, when the
    service starts, then startup fails with a message naming the entry's index, not secrets.
14. Given the application layer and domain, when the architecture test runs, then no provider class or
    framework type is referenced outside `adapter/out/<provider>` and `config`.
15. Given provider calls, when they run, then each has a connect and read timeout, and metrics/spans use no
    asset id or symbol as a tag beyond what ADR 0014 allows (assumes ADR 0014 as in CLAUDE.md).

## Failure modes
- Unknown asset id: 404. Never-fetched single asset: 503.
- Provider timeout/5xx/429/invalid payload: keep last value, log at WARN with provider name and status only,
  no body.
- A symbol the provider does not list: that asset stays never-fetched (criterion 10); the others are unaffected.
- Misconfigured asset list: startup fails (criterion 13).
- Duplicate request / another caller's data: not applicable, read-only public data.

## Rollout
No schema change and no persistence. New endpoints and new config only, so old and new pods can coexist; a
new pod serves "no rate yet" until its first poll completes; readiness does not wait for it.

## Non-functional
- Reads are served from memory: no provider call on the request path; p99 under 50 ms.
- Provider call volume is bounded by assets x poll interval, independent of caller traffic; the interval must
  respect provider rate limits.
- Logs carry provider name and asset id; no secret and no PII exists in this feature.
- Retention: none, in-memory only.

## Open questions
- none
