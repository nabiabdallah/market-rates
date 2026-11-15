package io.github.bot.marketrates.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bot.marketrates.domain.Asset;
import io.github.bot.marketrates.domain.AssetKind;
import io.github.bot.marketrates.testsupport.LogCapture;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Criteria 1 (shape) and 13, plus the timing validations. */
class AssetCatalogTest {

    private static final Set<String> PROVIDERS = Set.of("binance", "binance-futures");

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
        asset(m, i, id, name, "CRYPTO", provider, symbol, quote);
    }

    private static void asset(
            Map<String, String> m,
            int i,
            String id,
            String name,
            String kind,
            String provider,
            String symbol,
            String quote) {
        var p = "market-rates.assets[" + i + "].";
        if (id != null) m.put(p + "id", id);
        if (name != null) m.put(p + "name", name);
        m.put(p + "kind", kind);
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

    // Criterion 2: an asset more is a config entry, so a worst case longer than the interval is a warning.
    @Test
    void sequential_provider_calls_that_cannot_fit_in_the_poll_interval_warn_and_start() {
        var props = validProps();
        props.put("market-rates.binance.connect-timeout", "3s");
        props.put("market-rates.binance.read-timeout", "3s");

        try (var logs = LogCapture.start()) {
            assertThat(build(props)).hasSize(3);

            assertThat(logs.warnText()).contains("market-rates.poll-interval").contains("binance");
            assertThat(logs.text()).doesNotContain("BTCUSDT0");
        }
    }

    @Test
    void a_fourth_asset_with_the_default_timeouts_and_interval_starts() {
        var props = validProps();
        asset(props, 3, "sol-usdt", "SOL/USDT", "binance", "SOLUSDT3", "USDT");

        assertThat(build(props)).hasSize(4);
    }

    @Test
    void default_timeouts_fit_three_assets_in_ten_seconds() {
        assertThat(build(validProps())).hasSize(3);
    }

    // ---- Spec 0002 -----------------------------------------------------------------------------------------

    private static Map<String, String> eightAssets() {
        var m = validProps();
        asset(m, 3, "xau-usdt", "Gold", "METAL", "binance-futures", "XAUUSDT", "USDT");
        asset(m, 4, "xag-usdt", "Silver", "METAL", "binance-futures", "XAGUSDT", "USDT");
        asset(m, 5, "eur-usdt", "EUR/USDT", "FX", "binance", "EURUSDT", "USDT");
        asset(m, 6, "nvda-usdt", "Nvidia", "EQUITY", "binance-futures", "NVDAUSDT", "USDT");
        asset(m, 7, "spy-usdt", "S&P 500 (SPY)", "INDEX", "binance-futures", "SPYUSDT", "USDT");
        return m;
    }

    // Criterion 3
    @Test
    void default_scales_are_two_for_metal_equity_and_index_five_for_fx_and_eight_for_crypto() {
        var assets = build(eightAssets());

        assertThat(assets)
                .extracting(a -> a.kind() + ":" + a.scale())
                .containsExactly(
                        "CRYPTO:8", "CRYPTO:8", "CRYPTO:8", "METAL:2", "METAL:2", "FX:5", "EQUITY:2", "INDEX:2");
    }

    @Test
    void configured_scale_of_a_new_kind_reaches_its_assets() {
        var props = eightAssets();
        props.put("market-rates.scale.METAL", "3");
        props.put("market-rates.scale.FX", "6");

        var assets = build(props);

        assertThat(assets)
                .filteredOn(a -> a.kind() == AssetKind.METAL)
                .allSatisfy(a -> assertThat(a.scale()).isEqualTo(3));
        assertThat(assets)
                .filteredOn(a -> a.kind() == AssetKind.FX)
                .allSatisfy(a -> assertThat(a.scale()).isEqualTo(6));
    }

    // Criterion 12
    @ParameterizedTest
    @CsvSource({
        "METAL,binance", "FX,binance", "EQUITY,binance", "INDEX,binance",
        "METAL,binance-futures", "FX,binance-futures", "EQUITY,binance-futures", "INDEX,binance-futures"
    })
    void an_entry_of_each_new_kind_is_accepted_on_each_provider(String kind, String provider) {
        var props = validProps();
        asset(props, 3, "extra-one", "Extra", kind, provider, "EXTRAUSDT3", "USDT");

        var assets = build(props);

        assertThat(assets).hasSize(4);
        assertThat(assets.get(3).kind()).isEqualTo(AssetKind.valueOf(kind));
        assertThat(assets.get(3).provider()).isEqualTo(provider);
    }

    // Criterion 15
    @Test
    void the_futures_provider_is_accepted_only_when_it_is_in_the_known_provider_set() {
        var props = eightAssets();

        assertThat(AssetCatalog.build(bind(props), Set.of("binance", "binance-futures")))
                .hasSize(8);
        assertThatThrownBy(() -> AssetCatalog.build(bind(props), Set.of("binance")))
                .isInstanceOf(IllegalStateException.class)
                .message()
                .contains("market-rates.assets[3]")
                .doesNotContain("XAUUSDT");
    }

    @Test
    void unknown_kind_fails_binding_naming_the_entry_index() {
        var props = eightAssets();
        props.put("market-rates.assets[5].kind", "BOND");

        assertThatThrownBy(() -> build(props)).hasStackTraceContaining("assets[5]");
    }

    @Test
    void an_unknown_provider_on_a_new_kind_entry_fails_naming_the_index() {
        var props = eightAssets();
        props.put("market-rates.assets[6].provider", "nope");

        assertThatThrownBy(() -> build(props))
                .isInstanceOf(IllegalStateException.class)
                .message()
                .contains("market-rates.assets[6]")
                .doesNotContain("NVDAUSDT");
    }

    @Test
    void a_duplicate_id_among_the_new_entries_fails_naming_the_index() {
        var props = eightAssets();
        props.put("market-rates.assets[4].id", "xau-usdt");

        assertThatThrownBy(() -> build(props))
                .isInstanceOf(IllegalStateException.class)
                .message()
                .contains("market-rates.assets[4]")
                .doesNotContain("XAGUSDT");
    }

    @Test
    void a_missing_symbol_on_a_futures_entry_fails_naming_the_index() {
        var props = eightAssets();
        props.remove("market-rates.assets[7].symbol");

        assertThatThrownBy(() -> build(props))
                .isInstanceOf(IllegalStateException.class)
                .message()
                .contains("market-rates.assets[7]");
    }

    // Futures timing validation names market-rates.binance-futures.*
    @Test
    void futures_defaults_are_the_public_futures_host_one_second_connect_and_two_seconds_read() {
        var futures = bind(validProps()).binanceFutures();

        assertThat(futures.baseUrl()).isEqualTo("https://fapi.binance.com");
        assertThat(futures.connectTimeout()).isEqualTo(java.time.Duration.ofSeconds(1));
        assertThat(futures.readTimeout()).isEqualTo(java.time.Duration.ofSeconds(2));
    }

    @Test
    void a_blank_futures_base_url_fails_startup_naming_the_property() {
        var props = validProps();
        props.put("market-rates.binance-futures.base-url", "  ");

        assertThatThrownBy(() -> build(props))
                .isInstanceOf(IllegalStateException.class)
                .message()
                .contains("market-rates.binance-futures.base-url");
    }

    @Test
    void a_non_positive_futures_connect_timeout_fails_startup_naming_the_property() {
        var props = validProps();
        props.put("market-rates.binance-futures.connect-timeout", "0s");

        assertThatThrownBy(() -> build(props))
                .isInstanceOf(IllegalStateException.class)
                .message()
                .contains("market-rates.binance-futures.connect-timeout");
    }

    @Test
    void a_non_positive_futures_read_timeout_fails_startup_naming_the_property() {
        var props = validProps();
        props.put("market-rates.binance-futures.read-timeout", "-1s");

        assertThatThrownBy(() -> build(props))
                .isInstanceOf(IllegalStateException.class)
                .message()
                .contains("market-rates.binance-futures.read-timeout");
    }

    // Criterion 14: the call budget is checked per provider
    @Test
    void default_config_with_eight_assets_warns_for_both_providers_and_still_starts() {
        try (var logs = LogCapture.start()) {
            assertThat(build(eightAssets())).hasSize(8);

            assertThat(logs.warnText())
                    .contains("market-rates.poll-interval")
                    .contains("provider=binance-futures")
                    .containsPattern("provider=binance(?!-)");
            assertThat(logs.text()).doesNotContain("XAUUSDT").doesNotContain("EURUSDT");
        }
    }

    @Test
    void only_the_futures_provider_over_budget_warns_for_futures_alone() {
        var props = validProps();
        asset(props, 3, "xau-usdt", "Gold", "METAL", "binance-futures", "XAUUSDT", "USDT");
        asset(props, 4, "xag-usdt", "Silver", "METAL", "binance-futures", "XAGUSDT", "USDT");
        asset(props, 5, "nvda-usdt", "Nvidia", "EQUITY", "binance-futures", "NVDAUSDT", "USDT");
        asset(props, 6, "spy-usdt", "S&P", "INDEX", "binance-futures", "SPYUSDT", "USDT");

        try (var logs = LogCapture.start()) {
            assertThat(build(props)).hasSize(7);

            assertThat(logs.warnText())
                    .contains("provider=binance-futures")
                    .doesNotContainPattern("provider=binance(?!-)");
        }
    }

    @Test
    void only_the_spot_provider_over_budget_warns_for_spot_alone() {
        var props = validProps();
        props.put("market-rates.binance.read-timeout", "3s");
        asset(props, 3, "xau-usdt", "Gold", "METAL", "binance-futures", "XAUUSDT", "USDT");

        try (var logs = LogCapture.start()) {
            assertThat(build(props)).hasSize(4);

            assertThat(logs.warnText())
                    .containsPattern("provider=binance(?!-)")
                    .doesNotContain("provider=binance-futures");
        }
    }

    @Test
    void futures_timeouts_that_cannot_fit_in_the_poll_interval_warn_and_start() {
        var props = validProps();
        asset(props, 3, "xau-usdt", "Gold", "METAL", "binance-futures", "XAUUSDT", "USDT");
        props.put("market-rates.binance-futures.connect-timeout", "6s");
        props.put("market-rates.binance-futures.read-timeout", "6s");

        try (var logs = LogCapture.start()) {
            assertThat(build(props)).hasSize(4);

            assertThat(logs.warnText()).contains("market-rates.poll-interval").contains("provider=binance-futures");
        }
    }

    @Test
    void both_providers_within_budget_log_no_warning() {
        var props = validProps();
        asset(props, 3, "xau-usdt", "Gold", "METAL", "binance-futures", "XAUUSDT", "USDT");
        asset(props, 4, "xag-usdt", "Silver", "METAL", "binance-futures", "XAGUSDT", "USDT");

        try (var logs = LogCapture.start()) {
            assertThat(build(props)).hasSize(5);

            assertThat(logs.warnCount()).isZero();
        }
    }
}
