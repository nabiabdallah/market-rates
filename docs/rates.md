# Rates: endpoints and adding an asset

Spec: [0001](specs/0001-live-asset-rates.md). Design: [ADR 0002](adr/0002-in-memory-polled-rates.md).

## Endpoints
- `GET /api/v1/rates`: every configured asset. An asset never fetched yet is listed with `rate` and `asOf` null
  and `stale: true`.
- `GET /api/v1/rates/{id}`: one asset. 404 if the id isn't configured, 503 if it was never fetched.

```json
{ "id": "btc-usdt", "name": "BTC/USDT", "kind": "CRYPTO", "rate": "96250.41000000",
  "quoteCurrency": "USDT", "asOf": "2026-11-14T10:15:30Z", "stale": false }
```

- `rate` is a decimal string, never a JSON number: parse it as a decimal type, not a float.
- `stale` turns true when the last successful fetch is older than `market-rates.stale-after` (default 3 polls,
  30 s). The last rate is still served with its original `asOf`.

## Adding an asset (crypto shown)
Add an entry to `src/main/resources/application.properties` with the next index, then restart. No code change.

```properties
market-rates.assets[3].id=sol-usdt
market-rates.assets[3].name=SOL/USDT
market-rates.assets[3].kind=CRYPTO
market-rates.assets[3].provider=binance
market-rates.assets[3].symbol=SOLUSDT
market-rates.assets[3].quote-currency=USDT
```

- `id`: lowercase kebab-case and unique; it is the path segment of `GET /api/v1/rates/{id}`.
- `symbol`: Binance's spot symbol. A symbol Binance doesn't list leaves that asset never fetched; the others
  still work.
- A bad entry (unknown provider or kind, duplicate id, missing field) fails startup with a message naming the
  entry's index.
- Calls to Binance are sequential, 1 s connect and 2 s read timeout each. With more than 3 pairs a slow Binance can
  make a poll longer than the 10 s interval: startup logs a warning, and rates may go stale until it recovers.

## Gold, silver, EUR, Nvidia and the S&P 500
Spec: [0002](specs/0002-binance-metals-fx-equity-index.md). Kinds `METAL`, `FX`, `EQUITY` and `INDEX` (scales
2, 5, 2, 2 by default; `market-rates.scale.<KIND>`).

| id | kind | Binance market | symbol |
|---|---|---|---|
| `xau-usdt` (Gold) | `METAL` | USDⓈ-M futures, provider `binance-futures` | `XAUUSDT` |
| `xag-usdt` (Silver) | `METAL` | USDⓈ-M futures, provider `binance-futures` | `XAGUSDT` |
| `eur-usdt` (EUR/USDT) | `FX` | spot, provider `binance` | `EURUSDT` |
| `nvda-usdt` (Nvidia) | `EQUITY` | USDⓈ-M futures, provider `binance-futures` | `NVDAUSDT` |
| `spy-usdt` (S&P 500, SPY ETF) | `INDEX` | USDⓈ-M futures, provider `binance-futures` | `SPYUSDT` |

- The four futures prices are **perpetual-contract prices quoted in USDT**, not spot USD, an official index or an
  exchange close. They can drift from the underlying, most visibly outside market hours, and are shown as they are.
  `asOf` is the provider's quote time, so it can be well before the fetch; `stale` means "not refreshed by the
  service", not "market closed".
- `SPXUSDT` is not used: on Binance it is the SPX6900 meme coin. The S&P 500 is tracked through SPY.
- Another futures or spot symbol of these kinds is a config entry (`provider=binance-futures` or `binance`), as above.
- `kind` gained the values `METAL`, `FX`, `EQUITY`, `INDEX`: tell consumers that reject unknown values before deploying.
- Futures settings: `market-rates.binance-futures.{base-url,connect-timeout,read-timeout}` (defaults
  `https://fapi.binance.com`, 1 s, 2 s). Each provider is polled on its own thread.
- With the default config each provider has 4 assets x 3 s worst case, above the 10 s interval, so startup logs the
  call-budget warning for both providers. That is expected; polls never overlap.

## Deployment
Binance answers HTTP 451 in some regions. That is treated like any provider failure: all assets of that provider
stay never fetched or stale. Deploy to a region where `api.binance.com` and `fapi.binance.com` answer. Egress on
TCP 443 is already allowed by the network policy.
