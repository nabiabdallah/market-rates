package io.github.bot.marketrates;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bot.marketrates.application.port.in.GetRatesUseCase;
import io.github.bot.marketrates.application.port.out.RateGateway;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Boots the full Spring context: catches wiring mistakes in config/ before they reach a deployment. */
@SpringBootTest
class ApplicationContextTest {

    @Autowired
    List<RateGateway> gateways;

    @Autowired
    GetRatesUseCase rates;

    @Test
    void context_starts() {}

    // Spec 0002 criteria 1, 12: both providers are wired and the eight default assets load. The call budget
    // warning (criterion 14) is covered in AssetCatalogTest.
    @Test
    void both_binance_providers_are_wired_and_the_eight_default_assets_are_listed() {
        assertThat(gateways).extracting(RateGateway::provider).containsExactlyInAnyOrder("binance", "binance-futures");
        assertThat(rates.list())
                .extracting(r -> r.asset().id())
                .containsExactly(
                        "btc-usdt",
                        "eth-usdt",
                        "qnt-usdt",
                        "xau-usdt",
                        "xag-usdt",
                        "eur-usdt",
                        "nvda-usdt",
                        "spy-usdt");
    }
}
