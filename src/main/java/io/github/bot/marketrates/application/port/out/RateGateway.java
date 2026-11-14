package io.github.bot.marketrates.application.port.out;

import io.github.bot.marketrates.domain.Asset;
import java.util.List;
import java.util.Map;

/** Fetches current prices from one provider. One implementation per provider, in {@code adapter/out/<provider>}. */
public interface RateGateway {

    /** The provider name assets refer to in configuration. */
    String provider();

    /**
     * Prices keyed by the provider's symbol. Symbols the provider does not list, or answers with an unusable
     * payload, are absent.
     *
     * @throws RateProviderException when no asset could be priced
     */
    Map<String, ProviderQuote> fetch(List<Asset> assets);
}
