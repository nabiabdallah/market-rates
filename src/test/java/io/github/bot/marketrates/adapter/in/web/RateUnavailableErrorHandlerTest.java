package io.github.bot.marketrates.adapter.in.web;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.bot.marketrates.domain.RateUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Criterion 10: the error handler maps "rate not available" to 503, not 422. */
class RateUnavailableErrorHandlerTest {

    @RestController
    static class Probe {
        @GetMapping("/probe")
        String probe() {
            throw new RateUnavailableException();
        }
    }

    @Test
    void rate_unavailable_maps_to_503_problem_with_urn_instance() throws Exception {
        MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new ErrorHandler())
                .build()
                .perform(get("/probe"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Rate not available yet"))
                .andExpect(jsonPath("$.instance").value(startsWith("urn:uuid:")));
    }
}
