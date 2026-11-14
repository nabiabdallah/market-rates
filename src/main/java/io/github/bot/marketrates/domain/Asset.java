package io.github.bot.marketrates.domain;

import java.math.RoundingMode;

/**
 * A configured asset. {@code scale} and {@code rounding} say how its rate is normalised.
 *
 * @param id lowercase kebab identifier, the config key
 * @param provider name of the provider adapter that prices it
 * @param symbol the provider's own symbol
 */
public record Asset(
        String id,
        String name,
        AssetKind kind,
        String provider,
        String symbol,
        String quoteCurrency,
        int scale,
        RoundingMode rounding) {}
