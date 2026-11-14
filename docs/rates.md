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

## Adding a crypto pair
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

## Not supported yet
Metals, FX, equities and indices need a keyed provider and a new adapter: a new spec.
