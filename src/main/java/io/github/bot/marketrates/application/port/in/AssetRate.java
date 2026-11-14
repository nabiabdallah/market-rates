package io.github.bot.marketrates.application.port.in;

import io.github.bot.marketrates.domain.Asset;
import io.github.bot.marketrates.domain.Rate;
import java.time.Instant;

/**
 * An asset with its last known rate.
 *
 * @param rate null when never fetched
 * @param asOf null when never fetched
 * @param stale true when never fetched, or the last successful fetch is older than the staleness limit
 */
public record AssetRate(Asset asset, Rate rate, Instant asOf, boolean stale) {}
