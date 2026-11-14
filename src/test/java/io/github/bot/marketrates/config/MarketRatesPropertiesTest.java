package io.github.bot.marketrates.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bot.marketrates.application.RatesService;
import io.github.bot.marketrates.application.port.out.ProviderQuote;
import io.github.bot.marketrates.application.port.out.RateGateway;
import io.github.bot.marketrates.domain.Asset;
import io.github.bot.marketrates.domain.AssetKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Criteria 1 and 2 (binding the real application.properties), and unknown kind at binding time. */
class MarketRatesPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(MarketRatesProperties.class)
    static class Cfg {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Cfg.class);

    // Criterion 1
    @Test
    void default_config_defines_the_three_initial_crypto_assets_on_binance() {
        runner.run(ctx -> {
            var props = ctx.getBean(MarketRatesProperties.class);

            var assets = AssetCatalog.build(props, Set.of("binance"));

            assertThat(assets).extracting(Asset::id).containsExactly("btc-usdt", "eth-usdt", "qnt-usdt");
            assertThat(assets).extracting(Asset::symbol).containsExactly("BTCUSDT", "ETHUSDT", "QNTUSDT");
            assertThat(assets).allSatisfy(a -> {
                assertThat(a.kind()).isEqualTo(AssetKind.CRYPTO);
                assertThat(a.provider()).isEqualTo("binance");
                assertThat(a.quoteCurrency()).isEqualTo("USDT");
                assertThat(a.scale()).isEqualTo(8);
                assertThat(a.rounding()).isEqualTo(RoundingMode.HALF_EVEN);
            });
        });
    }

    @Test
    void default_timing_is_ten_second_poll_and_thirty_second_staleness() {
        runner.run(ctx -> {
            var props = ctx.getBean(MarketRatesProperties.class);

            assertThat(props.pollInterval()).isEqualTo(Duration.ofSeconds(10));
            assertThat(props.staleAfter()).isEqualTo(Duration.ofSeconds(30));
            assertThat(props.roundingMode()).isEqualTo(RoundingMode.HALF_EVEN);
            assertThat(props.scale()).containsEntry(AssetKind.CRYPTO, 8);
        });
    }

    // Criterion 2
    @Test
    void an_asset_added_in_config_is_polled_and_listed_without_code_change() {
        var withFourth = new String[] {
            "market-rates.assets[0].id=btc-usdt", "market-rates.assets[0].name=BTC/USDT",
            "market-rates.assets[0].kind=CRYPTO", "market-rates.assets[0].provider=binance",
            "market-rates.assets[0].symbol=BTCUSDT", "market-rates.assets[0].quote-currency=USDT",
            "market-rates.assets[1].id=sol-usdt", "market-rates.assets[1].name=SOL/USDT",
            "market-rates.assets[1].kind=CRYPTO", "market-rates.assets[1].provider=binance",
            "market-rates.assets[1].symbol=SOLUSDT", "market-rates.assets[1].quote-currency=USDT",
            "market-rates.assets[2].id=eth-usdt", "market-rates.assets[2].name=ETH/USDT",
            "market-rates.assets[2].kind=CRYPTO", "market-rates.assets[2].provider=binance",
            "market-rates.assets[2].symbol=ETHUSDT", "market-rates.assets[2].quote-currency=USDT",
            "market-rates.assets[3].id=qnt-usdt", "market-rates.assets[3].name=QNT/USDT",
            "market-rates.assets[3].kind=CRYPTO", "market-rates.assets[3].provider=binance",
            "market-rates.assets[3].symbol=QNTUSDT", "market-rates.assets[3].quote-currency=USDT",
            "market-rates.poll-interval=20s"
        };
        runner.withPropertyValues(withFourth).run(ctx -> {
            var props = ctx.getBean(MarketRatesProperties.class);
            var assets = AssetCatalog.build(props, Set.of("binance"));
            RateGateway gateway = new RateGateway() {
                @Override
                public String provider() {
                    return "binance";
                }

                @Override
                public Map<String, ProviderQuote> fetch(List<Asset> toFetch) {
                    var out = new HashMap<String, ProviderQuote>();
                    toFetch.forEach(a -> out.put(a.symbol(), new ProviderQuote(new BigDecimal("1.5"), null)));
                    return out;
                }
            };
            var service = new RatesService(assets, List.of(gateway), Clock.systemUTC(), props.staleAfter());

            service.poll("binance");

            assertThat(service.list())
                    .extracting(r -> r.asset().id())
                    .containsExactly("btc-usdt", "sol-usdt", "eth-usdt", "qnt-usdt");
            assertThat(service.get("sol-usdt").rate().value()).isEqualByComparingTo("1.5");
        });
    }

    // Criterion 13 (unknown kind fails during binding)
    @Test
    void unknown_kind_fails_context_startup_naming_the_entry_index() {
        runner.withPropertyValues(
                        "market-rates.assets[0].id=btc-usdt", "market-rates.assets[0].name=BTC/USDT",
                        "market-rates.assets[0].kind=CRYPTO", "market-rates.assets[0].provider=binance",
                        "market-rates.assets[0].symbol=BTCUSDT", "market-rates.assets[0].quote-currency=USDT",
                        "market-rates.assets[1].id=gold", "market-rates.assets[1].name=Gold",
                        "market-rates.assets[1].kind=METAL", "market-rates.assets[1].provider=binance",
                        "market-rates.assets[1].symbol=XAUSECRET", "market-rates.assets[1].quote-currency=USD")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    var chain = new StringBuilder();
                    for (Throwable t = ctx.getStartupFailure(); t != null; t = t.getCause()) {
                        chain.append(t).append('\n');
                    }
                    assertThat(chain.toString()).contains("assets[1]");
                });
    }
}
