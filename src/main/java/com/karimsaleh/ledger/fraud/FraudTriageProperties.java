package com.karimsaleh.ledger.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * All triage settings, bound from application.properties keys starting with "fraud.".
 * Example: fraud.triage.min-confidence=0.80
 *
 * <p>Defaults are untuned guesses, not calibrated values. Tune them only on labeled data.
 */
@ConfigurationProperties(prefix = "fraud")
public record FraudTriageProperties(
        @DefaultValue Triage triage,
        @DefaultValue Jev jev
) {

    /**
     * @param minConfidence      below this, HOLD regardless of risk level
     * @param flagThreshold      a reason flag counts when its probability is at least this
     * @param skipSystemSources  if true, transfers from SYSTEM accounts (deposits) skip triage
     */
    public record Triage(
            @DefaultValue("0.80") double minConfidence,
            @DefaultValue("0.5") double flagThreshold,
            @DefaultValue("true") boolean skipSystemSources
    ) {
    }

    /**
     * @param baseUrl         gateway base URL; "/v1/systemone" is appended
     * @param model           model id sent in the request
     * @param apiKey          from the AI_GATEWAY_API_KEY env var only; never commit it
     * @param connectTimeout  HTTP connect timeout
     * @param readTimeout     HTTP read timeout; exceeding it means HOLD
     * @param maxCallsPerDay  in-memory daily cap; over the cap means HOLD
     */
    public record Jev(
            @DefaultValue("https://ai-gateway.vercel.sh/typesafe") String baseUrl,
            @DefaultValue("typesafe-ai/jev") String model,
            @DefaultValue("") String apiKey,
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("2s") Duration readTimeout,
            @DefaultValue("500") int maxCallsPerDay
    ) {
    }
}
