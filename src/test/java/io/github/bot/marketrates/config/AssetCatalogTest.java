package io.github.bot.marketrates.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bot.marketrates.domain.Asset;
import io.github.bot.marketrates.domain.AssetKind;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Criteria 1 (shape) and 13, plus the timing validations. */
class AssetCatalogTest {

    private static final Set<String> PROVIDERS = Set.of("binance");

    private static Map<String, String> validProps() {
        var m = new LinkedHashMap<String, String>();
        m.put("market-rates.poll-interval", "10s");
        asset(m, 0, "btc-usdt", "BTC/USDT", "binance", "BTCUSDT0", "USDT");
        asset(m, 1, "eth-usdt", "ETH/USDT", "binance", "ETHUSDT1", "USDT");
        asset(m, 2, "qnt-usdt", "QNT/USDT", "binance", "QNTUSDT2", "USDT");
        return m;
    }

    private static void asset(
            Map<String, String> m, int i, String id, String name, String provider, String symbol, String quote) {
        var p = "market-rates.assets[" + i + "].";
        if (id != null) m.put(p + "id", id);
        if (name != null) m.put(p + "name", name);
        m.put(p + "kind", "CRYPTO");
        if (provider != null) m.put(p + "provider", provider);
        if (symbol != null) m.put(p + "symbol", symbol);
        if (quote != null) m.put(p + "quote-currency", quote);
    }

    private static MarketRatesProperties bind(Map<String, String> map) {
        return new Binder(new MapConfigurationPropertySource(map))
                .bindOrCreate("market-rates", MarketRatesProperties.class);
    }

    private static List<Asset> build(Map<String, String> map) {
        return AssetCatalog.build(bind(map), PROVIDERS);
    }

    @Test
    void valid_config_builds_assets_in_order_with_default_scale_and_rounding() {
        var assets = build(validProps());

        assertThat(assets).extracting(Asset::id).containsExactly("btc-usdt", "eth-usdt", "qnt-usdt");
        assertThat(assets.get(0).name()).isEqualTo("BTC/USDT");
        assertThat(assets.get(0).kind()).isEqualTo(AssetKind.CRYPTO);
        assertThat(assets.get(0).provider()).isEqualTo("binance");
        assertThat(assets.get(0).symbol()).isEqualTo("BTCUSDT0");
        assertThat(assets.get(0).quoteCurrency()).isEqualTo("USDT");
        assertThat(assets).allSatisfy(a -> {
            assertThat(a.scale()).isEqualTo(8);
            assertThat(a.rounding()).isEqualTo(RoundingMode.HALF_EVEN);
        });
    }

    @Test
    void configured_scale_and_rounding_mode_reach_the_assets() {
        var props = validProps();
        props.put("market-rates.scale.CRYPTO", "4");
        props.put("market-rates.rounding-mode", "HALF_UP");

        var assets = build(props);

        assertThat(assets).allSatisfy(a -> {
            assertThat(a.scale()).isEqualTo(4);
            assertThat(a.rounding()).isEqualTo(RoundingMode.HALF_UP);
        });
    }

    @Test
    void unknown_provider_fails_startup_naming_the_entry_index_but_not_the_symbol() {
        var props = validProps();
        props.put("market-rates.assets[1].provider", "nope");

        assertFailsNamingIndex1(props);
    }

    @Test
    void duplicate_id_fails_startup_naming_the_entry_index_but_not_the_symbol() {
        var props = validProps();
        props.put("market-rates.assets[1].id", "btc-usdt");

        assertFailsNamingIndex1(props);
    }

    @Test
    void missing_symbol_fails_startup_naming_the_entry_index() {
        var props = validProps();
        props.remove("market-rates.assets[1].symbol");

        assertFailsNamingIndex1(props);
    }

    @Test
    void blank_symbol_fails_startup_naming_the_entry_index() {
        var props = validProps();
        props.put("market-rates.assets[1].symbol", "  ");

        assertFailsNamingIndex1(props);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BTC-USDT", "btc_usdt", "-btc", "btc-", "btc usdt", "btc--usdt"})
    void id_that_is_not_lowercase_kebab_fails_startup_naming_the_entry_index(String badId) {
        var props = validProps();
        props.put("market-rates.assets[1].id", badId);

        assertFailsNamingIndex1(props);
    }

    @Test
    void missing_name_fails_startup_naming_the_entry_index() {
        var props = validProps();
        props.remove("market-rates.assets[1].name");

        assertFailsNamingIndex1(props);
    }

    @Test
    void missing_quote_currency_fails_startup_naming_the_entry_index() {
        var props = validProps();
        props.remove("market-rates.assets[1].quote-currency");

        assertFailsNamingIndex1(props);
    }

    private static void assertFailsNamingIndex1(Map<String, String> props) {
        assertThatThrownBy(() -> build(props))
                .isInstanceOf(IllegalStateException.class)
                .message()
                .contains("market-rates.assets[1]")
                .doesNotContain("ETHUSDT1");
    }

    @Test
    void non_positive_poll_interval_fails_startup_naming_the_property() {
        var props = validProps();
        props.put("market-rates.poll-interval", "0s");

        assertThatThrownBy(() -> build(props))
                .isInstanceOf(IllegalStateException.class)
                .message()
                .contains("market-rates.poll-interval");
    }

    @Test
    void stale_after_shorter_than_poll_interval_fails_startup_naming_the_property() {
        var props = validProps();
        props.put("market-rates.stale-after", "5s");

        assertThatThrownBy(() -> build(props))
                .isInstanceOf(IllegalStateException.class)
                .message()
                .contains("market-rates.stale-after");
    }

    @Test
    void stale_after_defaults_to_three_poll_intervals() {
        var props = validProps();
        props.put("market-rates.poll-interval", "20s");

        assertThat(bind(props).staleAfter().toSeconds()).isEqualTo(60);
    }

    @Test
    void sequential_provider_calls_that_cannot_fit_in_the_poll_interval_fail_startup() {
        var props = validProps();
        props.put("market-rates.binance.connect-timeout", "3s");
        props.put("market-rates.binance.read-timeout", "3s");

        assertThatThrownBy(() -> build(props))
                .isInstanceOf(IllegalStateException.class)
                .message()
                .containsAnyOf("market-rates.poll-interval", "market-rates.binance");
    }

    @Test
    void default_timeouts_fit_three_assets_in_ten_seconds() {
        assertThat(build(validProps())).hasSize(3);
    }
}
