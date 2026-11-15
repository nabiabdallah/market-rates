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
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Binance public REST API (no key), spot or USDⓈ-M futures: the two differ only in provider name, ticker path
 * and whether the answer carries a quote time.  One sequential request per symbol, so a symbol Binance does not list
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
    static final String FUTURES_PROVIDER = "binance-futures";
    static final String OBSERVATION = "market.rates.provider.request";
    private static final String SPOT_TICKER = "/api/v3/ticker/price";
    private static final String FUTURES_TICKER = "/fapi/v1/ticker/price";
    private static final Logger log = LoggerFactory.getLogger(BinanceRateGateway.class);

    /** Plain decimals only: exponent forms such as 1E+999999999 would make rounding expensive. */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("\\d{1,20}(\\.\\d{1,20})?");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient client;
    private final ObservationRegistry observations;
    private final String provider;
    private final String ticker;
    private final boolean readsQuoteTime;

    BinanceRateGateway(
            RestClient client,
            ObservationRegistry observations,
            String provider,
            String ticker,
            boolean readsQuoteTime) {
        this.client = client;
        this.observations = observations;
        this.provider = provider;
        this.ticker = ticker;
        this.readsQuoteTime = readsQuoteTime;
    }

    /** The spot API: provider {@code binance}, no quote time. */
    public static BinanceRateGateway create(
            String baseUrl, Duration connectTimeout, Duration readTimeout, ObservationRegistry observations) {
        return build(baseUrl, connectTimeout, readTimeout, observations, PROVIDER, SPOT_TICKER, false);
    }

    /** The USDⓈ-M futures API: provider {@code binance-futures}; the response {@code time} is the quote time. */
    public static BinanceRateGateway createFutures(
            String baseUrl, Duration connectTimeout, Duration readTimeout, ObservationRegistry observations) {
        return build(baseUrl, connectTimeout, readTimeout, observations, FUTURES_PROVIDER, FUTURES_TICKER, true);
    }

    private static BinanceRateGateway build(
            String baseUrl,
            Duration connectTimeout,
            Duration readTimeout,
            ObservationRegistry observations,
            String provider,
            String ticker,
            boolean readsQuoteTime) {
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
        return new BinanceRateGateway(client, observations, provider, ticker, readsQuoteTime);
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public Map<String, ProviderQuote> fetch(List<Asset> assets) {
        var quotes = new HashMap<String, ProviderQuote>();
        for (Asset asset : assets) {
            var fetched = fetchOne(asset.symbol());
            if (fetched.price() != null) {
                quotes.put(asset.symbol(), new ProviderQuote(fetched.price(), fetched.quoteTime()));
            }
            if (fetched.rateLimited()) {
                log.warn("Provider is rate limiting, the rest of this poll is skipped provider={}", provider);
                break;
            }
        }
        if (quotes.isEmpty() && !assets.isEmpty()) {
            throw new RateProviderException("Provider returned no usable rate");
        }
        return quotes;
    }

    private record Fetched(BigDecimal price, Instant quoteTime, boolean rateLimited) {
        static final Fetched NOTHING = new Fetched(null, null, false);
    }

    /** The single-ticker object, or null when the answer is not an object. */
    private static JsonNode objectOf(String json) {
        if (json == null) {
            return null;
        }
        var node = MAPPER.readTree(json);
        return node.isObject() ? node : null;
    }

    /** A positive plain-decimal price; null when absent, not a string, unparsable or not positive. */
    private static BigDecimal priceOf(JsonNode object) {
        var price = object == null ? null : object.get("price");
        var text = price == null || !price.isString() ? null : price.asString();
        if (text == null || !PLAIN_DECIMAL.matcher(text).matches()) {
            return null;
        }
        var value = new BigDecimal(text);
        return value.signum() > 0 ? value : null;
    }

    /** The provider's quote time (integral epoch milliseconds, positive); null when absent or anything else. */
    private Instant quoteTimeOf(JsonNode object) {
        var time = readsQuoteTime && object != null ? object.get("time") : null;
        if (time == null || !time.isIntegralNumber() || !time.canConvertToLong() || time.longValue() <= 0) {
            return null;
        }
        return Instant.ofEpochMilli(time.longValue());
    }

    private Fetched fetchOne(String symbol) {
        var observation = Observation.createNotStarted(OBSERVATION, observations)
                .lowCardinalityKeyValue("provider", provider)
                .lowCardinalityKeyValue("uri", ticker)
                .start();
        var outcome = "SUCCESS";
        try {
            var json = client.get()
                    .uri(ticker + "?symbol={symbol}", symbol)
                    .retrieve()
                    .body(String.class);
            var object = objectOf(json);
            var price = priceOf(object);
            if (price == null) {
                outcome = "NO_PRICE";
                log.warn("Provider returned no price provider={}", provider);
                return Fetched.NOTHING;
            }
            return new Fetched(price, quoteTimeOf(object), false);
        } catch (ProviderHttpException e) {
            outcome = "HTTP_ERROR";
            observation.error(new ProviderCallError("HTTP " + e.status()));
            log.warn("Provider request failed provider={} status={}", provider, e.status());
            return new Fetched(null, null, e.status() == 429 || e.status() == 418);
        } catch (RuntimeException e) {
            outcome = "IO_ERROR";
            observation.error(new ProviderCallError(e.getClass().getSimpleName()));
            log.warn(
                    "Provider request failed provider={} error={}",
                    provider,
                    e.getClass().getSimpleName());
            return Fetched.NOTHING;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome).stop();
        }
    }
}
