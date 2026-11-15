package io.github.bot.marketrates.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.bot.marketrates.application.port.in.AssetRate;
import io.github.bot.marketrates.application.port.in.GetRatesUseCase;
import io.github.bot.marketrates.domain.Asset;
import io.github.bot.marketrates.domain.AssetKind;
import io.github.bot.marketrates.domain.AssetNotFoundException;
import io.github.bot.marketrates.domain.Rate;
import io.github.bot.marketrates.domain.RateUnavailableException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web adapter tests (criteria 3, 4, 5, 6, 10) with a fake use case. */
class RatesControllerTest {

    private static final Instant AS_OF = Instant.parse("2026-11-14T10:15:30Z");

    private static Asset asset(String id, String name) {
        return new Asset(id, name, AssetKind.CRYPTO, "binance", id.toUpperCase(), "USDT", 8, RoundingMode.HALF_EVEN);
    }

    private static AssetRate fresh(Asset asset, String price) {
        return new AssetRate(asset, Rate.of(new BigDecimal(price), 8, RoundingMode.HALF_EVEN), AS_OF, false);
    }

    private static final class FakeUseCase implements GetRatesUseCase {
        final Map<String, AssetRate> byId;
        final List<AssetRate> all;

        FakeUseCase(List<AssetRate> all) {
            this.all = all;
            this.byId = new java.util.LinkedHashMap<>();
            all.forEach(r -> byId.put(r.asset().id(), r));
        }

        @Override
        public List<AssetRate> list() {
            return all;
        }

        @Override
        public AssetRate get(String id) {
            var r = byId.get(id);
            if (r == null) {
                throw new AssetNotFoundException();
            }
            if (r.rate() == null) {
                throw new RateUnavailableException();
            }
            return r;
        }
    }

    private MockMvc mvc(AssetRate... rates) {
        return MockMvcBuilders.standaloneSetup(new RatesController(new FakeUseCase(List.of(rates))))
                .setControllerAdvice(new ErrorHandler())
                .build();
    }

    // Criterion 3 and 6
    @Test
    void list_returns_200_with_one_entry_per_asset_and_the_contract_fields() throws Exception {
        var mvc = mvc(fresh(asset("btc-usdt", "BTC/USDT"), "96250.41"), fresh(asset("eth-usdt", "ETH/USDT"), "3400.5"));

        mvc.perform(get("/api/v1/rates"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.rates.length()").value(2))
                .andExpect(jsonPath("$.rates[0].id").value("btc-usdt"))
                .andExpect(jsonPath("$.rates[0].name").value("BTC/USDT"))
                .andExpect(jsonPath("$.rates[0].kind").value("CRYPTO"))
                .andExpect(jsonPath("$.rates[0].rate").value("96250.41000000"))
                .andExpect(jsonPath("$.rates[0].quoteCurrency").value("USDT"))
                .andExpect(jsonPath("$.rates[0].asOf").value("2026-11-14T10:15:30Z"))
                .andExpect(jsonPath("$.rates[0].stale").value(false))
                .andExpect(jsonPath("$.rates[1].id").value("eth-usdt"));
    }

    @Test
    void rate_is_serialised_as_a_json_string_never_a_number() throws Exception {
        var mvc = mvc(fresh(asset("btc-usdt", "BTC/USDT"), "96250.123456789"));

        var body = mvc.perform(get("/api/v1/rates")).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"rate\":\"96250.12345679\"");
        Object rate = JsonPath.read(body, "$.rates[0].rate");
        assertThat(rate).isInstanceOf(String.class);
    }

    // Spec 0002 criteria 4, 5, 6: new kinds, their scales and the string rate
    @Test
    void new_kinds_are_listed_with_their_scale_and_the_rate_as_a_json_string() throws Exception {
        var gold = new Asset(
                "xau-usdt", "Gold", AssetKind.METAL, "binance-futures", "XAUUSDT", "USDT", 2, RoundingMode.HALF_EVEN);
        var eur = new Asset(
                "eur-usdt", "EUR/USDT", AssetKind.FX, "binance", "EURUSDT", "USDT", 5, RoundingMode.HALF_EVEN);
        var nvda = new Asset(
                "nvda-usdt",
                "Nvidia",
                AssetKind.EQUITY,
                "binance-futures",
                "NVDAUSDT",
                "USDT",
                2,
                RoundingMode.HALF_EVEN);
        var spy = new Asset(
                "spy-usdt",
                "S&P 500 (SPY)",
                AssetKind.INDEX,
                "binance-futures",
                "SPYUSDT",
                "USDT",
                2,
                RoundingMode.HALF_EVEN);
        var mvc = mvc(
                new AssetRate(gold, Rate.of(new BigDecimal("3843.85"), 2, RoundingMode.HALF_EVEN), AS_OF, false),
                new AssetRate(eur, Rate.of(new BigDecimal("1.09890000"), 5, RoundingMode.HALF_EVEN), AS_OF, false),
                new AssetRate(nvda, Rate.of(new BigDecimal("161.33000"), 2, RoundingMode.HALF_EVEN), AS_OF, false),
                new AssetRate(spy, Rate.of(new BigDecimal("512.3"), 2, RoundingMode.HALF_EVEN), AS_OF, false));

        var body = mvc.perform(get("/api/v1/rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates[0].kind").value("METAL"))
                .andExpect(jsonPath("$.rates[0].rate").value("3843.85"))
                .andExpect(jsonPath("$.rates[0].quoteCurrency").value("USDT"))
                .andExpect(jsonPath("$.rates[1].kind").value("FX"))
                .andExpect(jsonPath("$.rates[1].rate").value("1.09890"))
                .andExpect(jsonPath("$.rates[2].kind").value("EQUITY"))
                .andExpect(jsonPath("$.rates[2].rate").value("161.33"))
                .andExpect(jsonPath("$.rates[3].kind").value("INDEX"))
                .andExpect(jsonPath("$.rates[3].rate").value("512.30"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        for (int i = 0; i < 4; i++) {
            assertThat((Object) JsonPath.read(body, "$.rates[" + i + "].rate")).isInstanceOf(String.class);
        }
    }

    @Test
    void get_by_id_of_a_metal_returns_the_provider_time_as_as_of() throws Exception {
        var gold = new Asset(
                "xau-usdt", "Gold", AssetKind.METAL, "binance-futures", "XAUUSDT", "USDT", 2, RoundingMode.HALF_EVEN);
        var asOf = Instant.parse("2026-11-15T10:15:30Z");

        mvc(new AssetRate(gold, Rate.of(new BigDecimal("3843.85"), 2, RoundingMode.HALF_EVEN), asOf, false))
                .perform(get("/api/v1/rates/{id}", "xau-usdt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("xau-usdt"))
                .andExpect(jsonPath("$.name").value("Gold"))
                .andExpect(jsonPath("$.rate").value("3843.85"))
                .andExpect(jsonPath("$.asOf").value("2026-11-15T10:15:30Z"))
                .andExpect(jsonPath("$.stale").value(false));
    }

    @Test
    void tiny_rate_is_plain_decimal_not_scientific_notation() throws Exception {
        var mvc = mvc(fresh(asset("btc-usdt", "BTC/USDT"), "0.00000001"));

        mvc.perform(get("/api/v1/rates")).andExpect(jsonPath("$.rates[0].rate").value("0.00000001"));
    }

    // Criterion 10
    @Test
    void never_fetched_asset_is_listed_with_json_null_rate_and_as_of_and_stale_true() throws Exception {
        var mvc = mvc(new AssetRate(asset("btc-usdt", "BTC/USDT"), null, null, true));

        var body = mvc.perform(get("/api/v1/rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates[0].stale").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Map<String, Object> entry = JsonPath.read(body, "$.rates[0]");
        assertThat(entry).containsKeys("rate", "asOf");
        assertThat(entry.get("rate")).isNull();
        assertThat(entry.get("asOf")).isNull();
    }

    @Test
    void stale_rate_keeps_its_value_and_original_as_of() throws Exception {
        var stale = new AssetRate(
                asset("btc-usdt", "BTC/USDT"), Rate.of(new BigDecimal("1.5"), 8, RoundingMode.HALF_EVEN), AS_OF, true);

        mvc(stale)
                .perform(get("/api/v1/rates"))
                .andExpect(jsonPath("$.rates[0].stale").value(true))
                .andExpect(jsonPath("$.rates[0].asOf").value("2026-11-14T10:15:30Z"))
                .andExpect(jsonPath("$.rates[0].rate").value("1.50000000"));
    }

    // Criterion 4
    @Test
    void get_by_id_returns_200_with_that_asset() throws Exception {
        var mvc = mvc(fresh(asset("btc-usdt", "BTC/USDT"), "96250.41"), fresh(asset("eth-usdt", "ETH/USDT"), "3400.5"));

        mvc.perform(get("/api/v1/rates/{id}", "eth-usdt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("eth-usdt"))
                .andExpect(jsonPath("$.rate").value("3400.50000000"))
                .andExpect(jsonPath("$.quoteCurrency").value("USDT"))
                .andExpect(jsonPath("$.asOf").value("2026-11-14T10:15:30Z"))
                .andExpect(jsonPath("$.stale").value(false));
    }

    // Criterion 5
    @Test
    void get_unknown_id_returns_404_problem_without_the_requested_id() throws Exception {
        var body = mvc(fresh(asset("btc-usdt", "BTC/USDT"), "1"))
                .perform(get("/api/v1/rates/{id}", "doge-secret-id"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.instance").value(startsWith("urn:uuid:")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("doge-secret-id");
    }

    // Criterion 10
    @Test
    void get_never_fetched_asset_returns_503_problem() throws Exception {
        mvc(new AssetRate(asset("btc-usdt", "BTC/USDT"), null, null, true))
                .perform(get("/api/v1/rates/{id}", "btc-usdt"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Rate not available yet"))
                .andExpect(jsonPath("$.instance").value(startsWith("urn:uuid:")));
    }
}
