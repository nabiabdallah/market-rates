package io.github.bot.marketrates.config;

import io.github.bot.marketrates.domain.Asset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the configured entries into {@link Asset}s and rejects a bad configuration at startup. Messages name
 * the entry by index ({@code market-rates.assets[1]}) and the property, never a symbol or other value.
 */
final class AssetCatalog {

    private static final Logger log = LoggerFactory.getLogger(AssetCatalog.class);
    private static final Pattern KEBAB = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private AssetCatalog() {}

    static List<Asset> build(MarketRatesProperties props, Set<String> knownProviders) {
        validateTiming(props);
        var assets = new ArrayList<Asset>();
        var ids = new HashSet<String>();
        var entries = props.assets();
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            var at = "market-rates.assets[" + i + "]";
            require(e.id() != null && KEBAB.matcher(e.id()).matches(), at, "id must be lowercase kebab-case");
            require(ids.add(e.id()), at, "id is a duplicate");
            require(notBlank(e.name()), at, "name is required");
            require(e.kind() != null, at, "kind is required");
            require(notBlank(e.provider()) && knownProviders.contains(e.provider()), at, "provider is unknown");
            require(notBlank(e.symbol()), at, "symbol is required");
            require(notBlank(e.quoteCurrency()), at, "quote-currency is required");
            assets.add(new Asset(
                    e.id(),
                    e.name(),
                    e.kind(),
                    e.provider(),
                    e.symbol(),
                    e.quoteCurrency(),
                    props.scale().get(e.kind()),
                    props.roundingMode()));
        }
        validateBudget(props, assets);
        return List.copyOf(assets);
    }

    private static void validateTiming(MarketRatesProperties props) {
        var binance = props.binance();
        if (!notBlank(binance.baseUrl())) {
            throw new IllegalStateException("market-rates.binance.base-url is required");
        }
        if (binance.connectTimeout().isNegative() || binance.connectTimeout().isZero()) {
            throw new IllegalStateException("market-rates.binance.connect-timeout must be positive");
        }
        if (binance.readTimeout().isNegative() || binance.readTimeout().isZero()) {
            throw new IllegalStateException("market-rates.binance.read-timeout must be positive");
        }
        if (props.scale().values().stream().anyMatch(s -> s < 0)) {
            throw new IllegalStateException("market-rates.scale must not be negative");
        }
        if (props.pollInterval().isZero() || props.pollInterval().isNegative()) {
            throw new IllegalStateException("market-rates.poll-interval must be positive");
        }
        if (props.staleAfter().compareTo(props.pollInterval()) < 0) {
            throw new IllegalStateException("market-rates.stale-after must be at least market-rates.poll-interval");
        }
    }

    /**
     * A provider's calls are sequential, so its worst-case poll is assets x (connect + read timeout). Longer than
     * the poll interval is a warning, not a failure: polls are fixed-delay, so a slow one delays the next and never
     * overlaps it, and adding an asset must stay a config change (spec 0001, criterion 2).
     */
    private static void validateBudget(MarketRatesProperties props, List<Asset> assets) {
        long binanceAssets =
                assets.stream().filter(a -> "binance".equals(a.provider())).count();
        Duration perCall = props.binance().connectTimeout().plus(props.binance().readTimeout());
        if (perCall.multipliedBy(binanceAssets).compareTo(props.pollInterval()) > 0) {
            log.warn(
                    "A slow provider can make a poll longer than market-rates.poll-interval; rates may go stale"
                            + " provider=binance assets={}",
                    binanceAssets);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void require(boolean ok, String at, String problem) {
        if (!ok) {
            throw new IllegalStateException(at + ": " + problem);
        }
    }
}
