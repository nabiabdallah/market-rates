package io.github.bot.marketrates.adapter.out.binance;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.http.client.observation.ClientRequestObservationContext;

/**
 * Replaces the error on provider-call observations with one carrying only the exception class name. RestClient
 * records its own exception, whose message holds the full URL (with the symbol) or the response body, and
 * tracing would export it on the span.
 */
public final class ErrorSanitizingObservationFilter implements ObservationFilter {

    @Override
    public Observation.Context map(Observation.Context context) {
        if (context instanceof ClientRequestObservationContext && context.getError() != null) {
            context.setError(new SanitizedError(context.getError().getClass().getSimpleName()));
        }
        return context;
    }

    private static final class SanitizedError extends RuntimeException {
        SanitizedError(String className) {
            super(className, null, false, false);
        }
    }
}
