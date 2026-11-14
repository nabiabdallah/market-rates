package io.github.bot.marketrates.adapter.out.binance;

import io.github.bot.marketrates.application.port.out.ProviderQuote;
import io.github.bot.marketrates.application.port.out.RateGateway;
import io.github.bot.marketrates.application.port.out.RateProviderException;
import io.github.bot.marketrates.domain.Asset;
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
 */
public class BinanceRateGateway implements RateGateway {

    static final String PROVIDER = "binance";
    private static final Logger log = LoggerFactory.getLogger(BinanceRateGateway.class);

    /** Plain decimals only: exponent forms such as 1E+999999999 would make rounding expensive. */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("\\d{1,20}(\\.\\d{1,20})?");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient client;

    BinanceRateGateway(RestClient client) {
        this.client = client;
    }

    public static BinanceRateGateway create(
            String baseUrl, Duration connectTimeout, Duration readTimeout, RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        var client = builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .observationConvention(new TemplateOnlyObservationConvention())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw new ProviderHttpException(response.getStatusCode().value());
                })
                .build();
        return new BinanceRateGateway(client);
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Map<String, ProviderQuote> fetch(List<Asset> assets) {
        var quotes = new HashMap<String, ProviderQuote>();
        for (Asset asset : assets) {
            var price = fetchOne(asset.symbol());
            if (price != null) {
                quotes.put(asset.symbol(), new ProviderQuote(price, null));
            }
        }
        if (quotes.isEmpty() && !assets.isEmpty()) {
            throw new RateProviderException("Binance returned no usable rate");
        }
        return quotes;
    }

    /** The price text of a single-ticker object, or of the first element of an array; null when absent. */
    private static String priceOf(String json) {
        if (json == null) {
            return null;
        }
        var node = MAPPER.readTree(json);
        if (node.isArray()) {
            node = node.isEmpty() ? null : node.get(0);
        }
        var price = node == null ? null : node.get("price");
        var text = price == null || !price.isString() ? null : price.asString();
        return text != null && PLAIN_DECIMAL.matcher(text).matches() ? text : null;
    }

    private BigDecimal fetchOne(String symbol) {
        try {
            var json = client.get()
                    .uri("/api/v3/ticker/price?symbol={symbol}", symbol)
                    .retrieve()
                    .body(String.class);
            var price = priceOf(json);
            if (price == null) {
                log.warn("Provider returned no price provider={}", PROVIDER);
                return null;
            }
            return new BigDecimal(price);
        } catch (NumberFormatException e) {
            log.warn("Provider returned an unparsable price provider={}", PROVIDER);
        } catch (ProviderHttpException e) {
            log.warn("Provider request failed provider={} status={}", PROVIDER, e.status());
        } catch (RuntimeException e) {
            log.warn(
                    "Provider request failed provider={} error={}",
                    PROVIDER,
                    e.getClass().getSimpleName());
        }
        return null;
    }
}
