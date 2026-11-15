package io.github.bot.marketrates;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Spec 0002 criterion 18: docs/rates.md warns about perpetual-contract prices and the HTTP 451 region block. */
class DocsRatesTest {

    private static String rates() throws Exception {
        return Files.readString(Path.of("docs", "rates.md")).toLowerCase();
    }

    @Test
    void rates_doc_says_the_prices_are_perpetual_contract_prices() throws Exception {
        assertThat(rates()).contains("perpetual");
    }

    @Test
    void rates_doc_says_the_prices_can_drift_from_the_underlying() throws Exception {
        assertThat(rates()).contains("drift");
    }

    @Test
    void rates_doc_mentions_the_http_451_region_constraint() throws Exception {
        assertThat(rates()).contains("451");
    }

    @Test
    void rates_doc_no_longer_claims_metals_and_equities_are_unsupported() throws Exception {
        assertThat(rates()).doesNotContain("metals, fx, equities and indices need a keyed provider");
    }
}
