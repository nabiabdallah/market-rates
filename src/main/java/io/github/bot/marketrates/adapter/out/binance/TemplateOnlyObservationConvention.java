package io.github.bot.marketrates.adapter.out.binance;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/** Default RestClient observations minus {@code http.url}: the full URL carries the symbol (ADR 0014). */
final class TemplateOnlyObservationConvention extends DefaultClientRequestObservationConvention {

    private static final String RAW_URL = "http.url";

    @Override
    public KeyValues getHighCardinalityKeyValues(ClientRequestObservationContext context) {
        var kept = new java.util.ArrayList<KeyValue>();
        for (KeyValue kv : super.getHighCardinalityKeyValues(context)) {
            if (!RAW_URL.equals(kv.getKey())) {
                kept.add(kv);
            }
        }
        return KeyValues.of(kept);
    }
}
