package io.github.bot.marketrates.application.port.in;

import java.util.List;

/** Reads the last known rates; never calls a provider. */
public interface GetRatesUseCase {

    /** One entry per configured asset, in configuration order. */
    List<AssetRate> list();

    /**
     * @throws io.github.bot.marketrates.domain.AssetNotFoundException if the id is not configured
     * @throws io.github.bot.marketrates.domain.RateUnavailableException if no rate was ever fetched
     */
    AssetRate get(String id);
}
