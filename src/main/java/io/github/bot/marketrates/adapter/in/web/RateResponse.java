package io.github.bot.marketrates.adapter.in.web;

import io.github.bot.marketrates.application.port.in.AssetRate;

/** One rate on the wire. {@code rate} is a decimal string, never a JSON number. */
record RateResponse(
        String id, String name, String kind, String rate, String quoteCurrency, String asOf, boolean stale) {

    static RateResponse from(AssetRate r) {
        var asset = r.asset();
        return new RateResponse(
                asset.id(),
                asset.name(),
                asset.kind().name(),
                r.rate() == null ? null : r.rate().value().toPlainString(),
                asset.quoteCurrency(),
                r.asOf() == null ? null : r.asOf().toString(),
                r.stale());
    }
}
