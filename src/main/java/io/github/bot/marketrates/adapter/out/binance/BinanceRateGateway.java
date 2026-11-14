package io.github.bot.marketrates.adapter.out.binance;

import io.github.bot.marketrates.application.port.out.ProviderQuote;
import io.github.bot.marketrates.application.port.out.RateGateway;
import io.github.bot.marketrates.application.port.out.RateProviderException;
import io.github.bot.marketrates.domain.Asset;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Binance spot public REST API (no key). One sequential request per symbol, so a symbol Binance does not list
 * (HTTP 400) cannot break the others. Worst-case poll time is assets x (connect + read timeout).
 *
 * <p>Logs carry the provider name and the HTTP status or exception class only: never the URL (query string),
 * the response body or an exception message, which for client exceptions embed both.
 *
 * <p>The same holds for telemetry. RestClient records its own exception on its observation the moment a call
 * fails, and that message is the full URL with the symbol; a tracing handler exports it at once, before any
 * filter runs. So the RestClient observes nothing, and each call is one observation made here, with the
 * provider, the URI template and an outcome as tags and a {@link ProviderCallError} as its error.
 *
 * <p>A provider that rate-limits (HTTP 429) or bans (418) is left alone for the rest of the poll.
 */
public class BinanceRateGateway implements RateGateway {

    static final String PROVIDER = "binance";
    static final String OBSERVATION = "market.rates.provider.request";
    private static final String TICKER = "/api/v3/ticker/price";
    private static final Logger log = LoggerFactory.getLogger(BinanceRateGateway.class);

    /** Plain decimals only: exponent forms such as 1E+999999999 would make rounding expensive. */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("\\d{1,20}(\\.\\d{1,20})?");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient client;
    private final ObservationRegistry observations;

    BinanceRateGateway(RestClient client, ObservationRegistry observations) {
        this.client = client;
        this.observations = observations;
    }

    public static BinanceRateGateway create(
            String baseUrl, Duration connectTimeout, Duration readTimeout, ObservationRegistry observations) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        var client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .observationRegistry(ObservationRegistry.NOOP)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw new ProviderHttpException(response.getStatusCode().value());
                })
                .build();
        return new BinanceRateGateway(client, observations);
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Map<String, ProviderQuote> fetch(List<Asset> assets) {
        var quotes = new HashMap<String, ProviderQuote>();
        for (Asset asset : assets) {
            var fetched = fetchOne(asset.symbol());
            if (fetched.price() != null) {
                quotes.put(asset.symbol(), new ProviderQuote(fetched.price(), null));
            }
            if (fetched.rateLimited()) {
                log.warn("Provider is rate limiting, the rest of this poll is skipped provider={}", PROVIDER);
                break;
            }
        }
        if (quotes.isEmpty() && !assets.isEmpty()) {
            throw new RateProviderException("Binance returned no usable rate");
        }
        return quotes;
    }

    private record Fetched(BigDecimal price, boolean rateLimited) {
        static final Fetched NOTHING = new Fetched(null, false);
    }

    /** The price text of a single-ticker object; null when absent or when the answer is not an object. */
    private static String priceOf(String json) {
        if (json == null) {
            return null;
        }
        var node = MAPPER.readTree(json);
        var price = node.isObject() ? node.get("price") : null;
        var text = price == null || !price.isString() ? null : price.asString();
        return text != null && PLAIN_DECIMAL.matcher(text).matches() ? text : null;
    }

    private Fetched fetchOne(String symbol) {
        var observation = Observation.createNotStarted(OBSERVATION, observations)
                .lowCardinalityKeyValue("provider", PROVIDER)
                .lowCardinalityKeyValue("uri", TICKER)
                .start();
        var outcome = "SUCCESS";
        try {
            var json = client.get()
                    .uri(TICKER + "?symbol={symbol}", symbol)
                    .retrieve()
                    .body(String.class);
            var price = priceOf(json);
            if (price == null) {
                outcome = "NO_PRICE";
                log.warn("Provider returned no price provider={}", PROVIDER);
                return Fetched.NOTHING;
            }
            return new Fetched(new BigDecimal(price), false);
        } catch (ProviderHttpException e) {
            outcome = "HTTP_ERROR";
            observation.error(new ProviderCallError("HTTP " + e.status()));
            log.warn("Provider request failed provider={} status={}", PROVIDER, e.status());
            return new Fetched(null, e.status() == 429 || e.status() == 418);
        } catch (RuntimeException e) {
            outcome = "IO_ERROR";
            observation.error(new ProviderCallError(e.getClass().getSimpleName()));
            log.warn(
                    "Provider request failed provider={} error={}",
                    PROVIDER,
                    e.getClass().getSimpleName());
            return Fetched.NOTHING;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome).stop();
        }
    }
}
