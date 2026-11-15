package io.github.bot.marketrates.config;

import io.github.bot.marketrates.domain.AssetKind;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The {@code market-rates.*} configuration. {@code staleAfter} defaults to three poll intervals.
 * Provider poll intervals are global for now (one {@code pollInterval}).
 */
@ConfigurationProperties("market-rates")
public record MarketRatesProperties(
        @DefaultValue("10s") Duration pollInterval,
        Duration staleAfter,
        @DefaultValue("HALF_EVEN") RoundingMode roundingMode,
        Map<AssetKind, Integer> scale,
        List<AssetEntry> assets,
        @DefaultValue Binance binance,
        @DefaultValue BinanceFutures binanceFutures,
        @DefaultValue Polling polling) {

    public MarketRatesProperties {
        if (staleAfter == null) {
            staleAfter = pollInterval.multipliedBy(3);
        }
        var scales = new EnumMap<AssetKind, Integer>(AssetKind.class);
        scales.put(AssetKind.CRYPTO, 8);
        scales.put(AssetKind.METAL, 2);
        scales.put(AssetKind.FX, 5);
        scales.put(AssetKind.EQUITY, 2);
        scales.put(AssetKind.INDEX, 2);
        if (scale != null) {
            scales.putAll(scale);
        }
        scale = Map.copyOf(scales);
        assets = assets == null ? List.of() : List.copyOf(assets);
    }

    /** One entry of {@code market-rates.assets[]}; every field is validated by {@link AssetCatalog}. */
    public record AssetEntry(
            String id, String name, AssetKind kind, String provider, String symbol, String quoteCurrency) {}

    public record Binance(
            @DefaultValue("https://api.binance.com") String baseUrl,
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("2s") Duration readTimeout) {}

    public record BinanceFutures(
            @DefaultValue("https://fapi.binance.com") String baseUrl,
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("2s") Duration readTimeout) {}

    public record Polling(@DefaultValue("true") boolean enabled) {}
}
