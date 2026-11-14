package io.github.bot.marketrates.adapter.in.web;

import io.github.bot.marketrates.application.port.in.GetRatesUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public read-only endpoints for the last known rates. */
@RestController
@RequestMapping("/api/v1/rates")
class RatesController {

    private final GetRatesUseCase rates;

    RatesController(GetRatesUseCase rates) {
        this.rates = rates;
    }

    @GetMapping
    RatesResponse list() {
        return new RatesResponse(rates.list().stream().map(RateResponse::from).toList());
    }

    @GetMapping("/{id}")
    RateResponse get(@PathVariable String id) {
        return RateResponse.from(rates.get(id));
    }
}
