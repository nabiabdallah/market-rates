package io.github.bot.marketrates.application;

import io.github.bot.marketrates.application.port.in.AssetRate;
import io.github.bot.marketrates.application.port.in.GetRatesUseCase;
import io.github.bot.marketrates.application.port.in.PollRatesUseCase;
import io.github.bot.marketrates.application.port.out.ProviderQuote;
import io.github.bot.marketrates.application.port.out.RateGateway;
import io.github.bot.marketrates.domain.Asset;
import io.github.bot.marketrates.domain.AssetNotFoundException;
import io.github.bot.marketrates.domain.InvalidRateException;
import io.github.bot.marketrates.domain.Rate;
import io.github.bot.marketrates.domain.RateUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the last rate of every configured asset in memory. Each asset's value is an immutable
 * {@link Snapshot} replaced atomically, so a reader sees a rate with its own {@code asOf}, never a mix.
 */
public class RatesService implements GetRatesUseCase, PollRatesUseCase {

    private static final Logger log = LoggerFactory.getLogger(RatesService.class);

    private record Snapshot(Rate rate, Instant asOf, Instant fetchedAt) {}

    private final Map<String, Asset> assetsById = new LinkedHashMap<>();
    private final Map<String, List<Asset>> assetsByProvider = new LinkedHashMap<>();
    private final Map<String, RateGateway> gateways = new LinkedHashMap<>();
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration staleAfter;

    public RatesService(List<Asset> assets, List<RateGateway> gateways, Clock clock, Duration staleAfter) {
        for (Asset asset : assets) {
            assetsById.put(asset.id(), asset);
            assetsByProvider
                    .computeIfAbsent(asset.provider(), p -> new java.util.ArrayList<>())
                    .add(asset);
        }
        gateways.forEach(g -> this.gateways.put(g.provider(), g));
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    @Override
    public List<AssetRate> list() {
        var now = clock.instant();
        return assetsById.values().stream().map(a -> view(a, now)).toList();
    }

    @Override
    public AssetRate get(String id) {
        var asset = assetsById.get(id);
        if (asset == null) {
            throw new AssetNotFoundException();
        }
        var view = view(asset, clock.instant());
        if (view.rate() == null) {
            throw new RateUnavailableException();
        }
        return view;
    }

    @Override
    public void poll(String provider) {
        var gateway = gateways.get(provider);
        var assets = assetsByProvider.get(provider);
        if (gateway == null || assets == null) {
            return;
        }
        Map<String, ProviderQuote> quotes;
        try {
            quotes = gateway.fetch(assets);
        } catch (RuntimeException e) {
            // Class name only: client exception messages can hold URLs and response bodies.
            log.warn(
                    "Rate poll failed provider={} error={}",
                    provider,
                    e.getClass().getSimpleName());
            return;
        }
        var fetchedAt = clock.instant();
        for (Asset asset : assets) {
            var quote = quotes.get(asset.symbol());
            if (quote == null) {
                log.warn("No rate returned provider={} asset={}", provider, asset.id());
                continue;
            }
            try {
                var rate = Rate.of(quote.price(), asset.scale(), asset.rounding());
                var asOf = quote.quoteTime() != null ? quote.quoteTime() : fetchedAt;
                store(asset.id(), new Snapshot(rate, asOf, fetchedAt));
            } catch (InvalidRateException e) {
                log.warn("Rate discarded provider={} asset={} reason={}", provider, asset.id(), e.getMessage());
            }
        }
    }

    private void store(String id, Snapshot next) {
        // Never let an older fetch overwrite a newer one.
        snapshots.merge(
                id, next, (old, candidate) -> candidate.fetchedAt().isBefore(old.fetchedAt()) ? old : candidate);
    }

    private AssetRate view(Asset asset, Instant now) {
        var snapshot = snapshots.get(asset.id());
        if (snapshot == null) {
            return new AssetRate(asset, null, null, true);
        }
        var stale = Duration.between(snapshot.fetchedAt(), now).compareTo(staleAfter) > 0;
        return new AssetRate(asset, snapshot.rate(), snapshot.asOf(), stale);
    }
}
