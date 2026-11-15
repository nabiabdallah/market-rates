# Spec: Gold, Silver, EUR/USDT, Nvidia and S&P 500 rates from Binance

- Status: Approved
- Owner: repository owner (issue #9)
- Related ADRs: 0001 (hexagonal architecture), 0002 (in-memory polled rates). Extends spec 0001 (Approved, not edited).

## Problem
Consumers of the rates service can read only the three crypto pairs. Spec 0001 left out Gold, Silver, EUR/USD,
Nvidia and the S&P 500 until a provider was named. The owner chose Binance's public, keyless API for all five,
so consumers get them from the same endpoints with no new provider account or API key.

## Scope
**In:**
- Five new assets, all quoted in USDT (owner decision, 2026-11-15):

| id | name | kind | Binance market | symbol |
|---|---|---|---|---|
| `xau-usdt` | Gold | `METAL` | USDⓈ-M futures (TradFi perpetual) | `XAUUSDT` |
| `xag-usdt` | Silver | `METAL` | USDⓈ-M futures (TradFi perpetual) | `XAGUSDT` |
| `eur-usdt` | EUR/USDT | `FX` | spot | `EURUSDT` |
| `nvda-usdt` | Nvidia | `EQUITY` | USDⓈ-M futures (TradFi perpetual) | `NVDAUSDT` |
| `spy-usdt` | S&P 500 (SPY) | `INDEX` | USDⓈ-M futures (TradFi perpetual) | `SPYUSDT` |

- New asset kinds `METAL`, `FX`, `EQUITY`, `INDEX`, each with a configured scale (default METAL 2, FX 5,
  EQUITY 2, INDEX 2; CRYPTO stays 8).
- A second provider `binance-futures`: `GET https://fapi.binance.com/fapi/v1/ticker/price?symbol=<symbol>`,
  response `{"symbol","price","time"}`. `time` (epoch ms) is the provider's quote time and becomes `asOf`.
  Same connect/read timeouts, 429/418 handling, per-call observation (uri tag `/fapi/v1/ticker/price`, no
  symbol tag) and no-body/no-query logging as the spot adapter (ADR 0002).
- `EURUSDT` is one more entry on the existing `binance` (spot) provider, kind `FX`.
- Default config lists all eight assets. Another futures or spot symbol of a supported kind is a config change.
- The startup call-budget check (assets x timeouts vs poll interval) covers both providers.
- `docs/rates.md` states that these prices are perpetual-contract prices in USDT, and the deploy docs note the
  HTTP 451 region constraint.

**Not in (a reader might assume it is):**
- `SPXUSDT` is never used: on Binance it is the SPX6900 meme coin. The S&P 500 is tracked through the SPY ETF
  perpetual.
- The prices are perpetual-contract prices quoted in USDT, not spot USD. They can drift from the underlying,
  most visibly outside market hours. They are shown as they are: no conversion, no adjustment.
- Streaming, history, USDT to USD conversion, an official index or exchange close price (would need a keyed
  provider such as Twelve Data; a later spec if ever wanted).
- A market-hours or "market closed" flag.
- Per-provider poll intervals (the interval stays global, ADR 0002).

## Contract
Endpoints and response shape are unchanged from spec 0001 (`GET /api/v1/rates`, `GET /api/v1/rates/{id}`).
`kind` gains the values `METAL`, `FX`, `EQUITY`, `INDEX`. Example:
```json
{ "id": "xau-usdt", "name": "Gold", "kind": "METAL", "rate": "3843.85", "quoteCurrency": "USDT",
  "asOf": "2026-11-15T10:15:30Z", "stale": false }
```
`rate` stays a JSON string, rounded to the kind's scale with the configured rounding mode (default HALF_EVEN).
The provider's text price is parsed as `BigDecimal`, never `double`.

Config: `market-rates.assets[]` entries with `provider: binance-futures` (new) or `binance`; `kind` accepts the
new values; `market-rates.scale.<KIND>` sets scales; `market-rates.binance-futures.{base-url,connect-timeout,
read-timeout}` (defaults `https://fapi.binance.com`, 1 s, 2 s). No secret is read.

Provider payload (futures): `price` decimal string, positive; `time` epoch milliseconds. A missing/zero
`time` falls back to the fetch time (spec 0001).

## Authorization
Unchanged: the service has no authentication; the endpoints are public read-only. Any caller may read every
configured asset; the data is not per-caller.

## Acceptance criteria
1. Given the default config, when the service starts, then eight assets are configured: the three crypto pairs
   and the five in the table, with the listed kind, provider (`binance-futures` for XAU, XAG, NVDA, SPY;
   `binance` for EURUSDT), symbol and quote currency `USDT`.
2. Given the default config, when it is inspected, then no asset uses the symbol `SPXUSDT`.
3. Given default scales, when rates of each kind are mapped, then METAL and EQUITY and INDEX use scale 2, FX
   scale 5 and CRYPTO scale 8.
4. Given the futures provider returns `{"symbol":"XAUUSDT","price":"3843.85","time":1794737730000}`, when
   polled, then the asset's rate is `3843.85` and `asOf` is `2026-11-15T10:15:30Z` (the provider time).
5. Given the futures provider returns `price` `161.33000` for NVDAUSDT, when mapped, then the rate is `161.33`
   (scale 2, HALF_EVEN) and is serialised as a JSON string.
6. Given the spot provider returns `1.09890000` for EURUSDT, when mapped, then the rate is `1.09890` (scale 5).
7. Given a futures response with a non-positive, missing or unparsable `price`, when fetched, then the value is
   discarded, the previous rate is kept, and the failure is logged without the body.
8. Given the futures provider answers 429 or 418, when a poll runs, then no further futures call is sent in
   that poll, the rates already fetched are kept, and the next poll tries again.
9. Given the futures provider times out, answers 5xx or 400 (unlisted symbol), when a poll runs, then the
   last known futures rates are served, the spot assets are unaffected, and for 400 only that asset stays
   unfetched.
10. Given a futures call, when it runs, then it has a connect and a read timeout, and its observation carries
    the tags `provider`, `uri` = `/fapi/v1/ticker/price` and `outcome`, never a symbol or asset id.
11. Given a futures call fails, when logged or recorded on a span, then the message holds only provider name,
    asset id and HTTP status or exception class: no URL, query string, symbol-bearing exception message or body.
12. Given an asset entry with kind `METAL`, `FX`, `EQUITY` or `INDEX` and an existing provider, when added to
    the config and the service restarts, then it is polled and returned with no code change.
13. Given a futures-provider asset last fetched longer than `stale-after` ago, when read, then it is returned
    with `stale=true` and its original `asOf`.
14. Given both providers are configured, when the service starts and the worst-case poll time of either
    (assets x (connect + read timeout)) exceeds the poll interval, then a warning is logged and startup does
    not fail.
15. Given an asset entry names an unknown provider or kind, a duplicate `id` or a missing symbol, when the
    service starts, then startup fails with a message naming the entry's index (spec 0001, criterion 13).
16. Given the application layer and domain, when the architecture test runs, then no provider class is
    referenced outside `adapter/out/<provider>` and `config`, and the ArchUnit rules are unchanged.
17. Given the default config, when the service starts and polls, then no API key or other secret is required
    or read, and provider request URLs are logged without query strings.
18. Given `docs/rates.md`, when read, then it says the five prices are perpetual-contract (USDT) prices that
    can drift from the underlying, and that Binance answers HTTP 451 in some regions.

## Failure modes
- Provider timeout/5xx/429/418/invalid payload: keep last value, WARN with provider name and status only.
- HTTP 451 (region blocked): treated like any other provider failure; all assets of that provider stay
  never-fetched or stale. Same behaviour as the crypto pairs today.
- Symbol not listed (HTTP 400): that asset stays never-fetched; others unaffected.
- Never-fetched asset: listed with null `rate`/`asOf` and `stale=true`; by id 503 (spec 0001).
- Misconfigured asset: startup fails (criterion 15).
- Duplicate request / another caller's data: not applicable, read-only public data.

## Rollout
No schema change, no persistence. New config and new enum values in the response: consumers that reject an
unknown `kind` value must be told before deploy. Old and new pods can coexist; a new pod serves "no rate yet"
until its first poll. Deploy to a region where Binance does not answer 451.

## Non-functional
- Reads from memory, no provider call on the request path; p99 under 50 ms.
- Call volume: 4 futures calls + 4 spot calls per poll interval (10 s), independent of caller traffic; within
  Binance's public weight limits (ticker price costs a small fixed weight per call).
- Logs: provider name, asset id, status only. No secret, no PII.
- Retention: none.

**Decided (issue #9, 2026-11-15).** The owner chose Binance for all five assets. The answers below are the
draft's recommendations, which the owner accepts by merging this spec:
- `stale-after` stays one global value (30 s). `stale` means "not refreshed by the service", not "market
  closed"; there is no market-hours handling (criterion 13).
- `asOf` for futures is the response `time`, even when it is much older than the fetch, so consumers see the
  real quote age (spec 0001).
- The euro's id is `eur-usdt`: the quote is USDT, and a USD name would mislead.

## Open questions
- none
