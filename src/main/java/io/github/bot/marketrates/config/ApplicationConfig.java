package io.github.bot.marketrates.config;

import io.github.bot.marketrates.adapter.out.binance.BinanceRateGateway;
import io.github.bot.marketrates.application.RatesService;
import io.github.bot.marketrates.application.port.out.RateGateway;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only place that knows which adapter implements which port. Declare a {@code @Bean} per use case
 * and per outbound adapter here; application and domain classes stay free of Spring annotations.
 */
@Configuration
@EnableConfigurationProperties(MarketRatesProperties.class)
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RateGateway binanceRateGateway(MarketRatesProperties props, ObservationRegistry observations) {
        var binance = props.binance();
        return BinanceRateGateway.create(
                binance.baseUrl(), binance.connectTimeout(), binance.readTimeout(), observations);
    }

    @Bean
    RateGateway binanceFuturesRateGateway(MarketRatesProperties props, ObservationRegistry observations) {
        var futures = props.binanceFutures();
        return BinanceRateGateway.createFutures(
                futures.baseUrl(), futures.connectTimeout(), futures.readTimeout(), observations);
    }

    @Bean
    RatesService ratesService(MarketRatesProperties props, List<RateGateway> gateways, Clock clock) {
        Set<String> providers = gateways.stream().map(RateGateway::provider).collect(Collectors.toSet());
        var assets = AssetCatalog.build(props, providers);
        return new RatesService(assets, gateways, clock, props.staleAfter());
    }
}
