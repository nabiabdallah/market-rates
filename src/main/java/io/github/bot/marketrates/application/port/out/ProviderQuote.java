package io.github.bot.marketrates.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A price as supplied by a provider.
 *
 * @param quoteTime the provider's quote time, or null when it supplies none
 */
public record ProviderQuote(BigDecimal price, Instant quoteTime) {}
