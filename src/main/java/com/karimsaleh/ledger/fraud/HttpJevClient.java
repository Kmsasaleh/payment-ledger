package com.karimsaleh.ledger.fraud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Calls Jev (TypeSafe AI) through the AI gateway. One attempt, no retries; any problem means unavailable. */
@Component
public class HttpJevClient implements JevClient {

    private static final String QUESTION_FRAUD_RISK = "fraud_risk";

    // snake_case keys for the "state" JSON string sent to Jev.
    private static final ObjectMapper STATE_MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private static final ObjectMapper RESPONSE_MAPPER = JsonMapper.builder().build();

    private final RestClient jevRestClient;
    private final FraudTriageProperties props;
    private final Clock clock;

    private LocalDate counterDay;
    private int callsToday;

    public HttpJevClient(RestClient jevRestClient, FraudTriageProperties props, Clock clock) {
        this.jevRestClient = jevRestClient;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public JevAssessment assess(TransferFeatures features) throws JevUnavailableException {
        FraudTriageProperties.Jev jev = props.jev();
        if (jev.apiKey() == null || jev.apiKey().isBlank()) {
            throw new JevUnavailableException("Jev API key not configured");
        }
        reserveCall(jev.maxCallsPerDay());

        long startNanos = System.nanoTime();
        String raw;
        try {
            String state = STATE_MAPPER.writeValueAsString(features);
            raw = jevRestClient.post()
                    .uri("/v1/systemone")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jev.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(buildRequest(jev.model(), state))
                    .retrieve()   // non-2xx throws a RestClientException subclass
                    .body(String.class);
        } catch (RestClientException | tools.jackson.core.JacksonException e) {
            throw new JevUnavailableException("Jev request failed: " + e.getClass().getSimpleName(), e);
        }
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        return parse(raw, latencyMs);
    }

    /** In-memory daily cap (UTC day). Counts every attempted call. */
    private synchronized void reserveCall(int max) throws JevUnavailableException {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(counterDay)) {
            counterDay = today;
            callsToday = 0;
        }
        if (callsToday >= max) {
            throw new JevUnavailableException("Jev daily call cap reached");
        }
        callsToday++;
    }

    private static Map<String, Object> buildRequest(String model, String state) {
        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put(QUESTION_FRAUD_RISK, Map.of(
                "type", "choice",
                "instructions", "How likely is this transfer to be fraud, given the sender's own history?",
                "criteria", Map.of(
                        "low", "Consistent with this sender's normal activity",
                        "medium", "Some unusual signs but plausibly legitimate",
                        "high", "Strong signs of fraud or account takeover")));
        questions.put("unusual_amount", noul("Is the amount unusually large compared with the sender's median outgoing amount?"));
        questions.put("new_recipient", noul("Is the recipient someone the sender has never paid before?"));
        questions.put("velocity_spike", noul("Has the sender made an unusually high number of outgoing transfers in the last 10 minutes?"));
        questions.put("odd_time", noul("Is the transfer happening at an unusual hour (UTC)?"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("state", state);
        body.put("questions", questions);
        return body;
    }

    private static Map<String, Object> noul(String instructions) {
        return Map.of("type", "noul", "instructions", instructions);
    }

    private JevAssessment parse(String raw, long latencyMs) throws JevUnavailableException {
        if (raw == null || raw.isBlank()) {
            throw new JevUnavailableException("Empty Jev response");
        }
        JevResponse resp;
        try {
            resp = RESPONSE_MAPPER.readValue(raw, JevResponse.class);
        } catch (Exception e) {
            throw new JevUnavailableException("Unparseable Jev response", e);
        }
        if (resp == null || resp.answers() == null) {
            throw new JevUnavailableException("Jev response has no answers");
        }

        ChoiceAnswer risk = resp.answers().get(QUESTION_FRAUD_RISK) == null
                ? null : resp.answers().get(QUESTION_FRAUD_RISK).asChoice();
        if (risk == null || risk.choice() == null) {
            throw new JevUnavailableException("Jev response missing fraud_risk");
        }
        RiskLevel level;
        try {
            level = RiskLevel.valueOf(risk.choice().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new JevUnavailableException("Unknown fraud_risk choice: " + risk.choice(), e);
        }
        if (risk.confidence() == null) {
            throw new JevUnavailableException("Jev response missing confidence");
        }
        double confidence = risk.confidence();
        requireUnitInterval(confidence, "confidence");

        Map<ReasonFlag, Double> flags = new EnumMap<>(ReasonFlag.class);
        for (ReasonFlag flag : ReasonFlag.values()) {
            Answer a = resp.answers().get(flag.questionId());
            if (a == null || a.noul() == null) {
                throw new JevUnavailableException("Jev response missing flag " + flag.questionId());
            }
            requireUnitInterval(a.noul(), flag.questionId());
            flags.put(flag, a.noul());
        }

        int tokens = (resp.usage() != null && resp.usage().inputTokens() != null)
                ? resp.usage().inputTokens() : -1;
        return new JevAssessment(level, confidence, flags, resp.model(), latencyMs, tokens);
    }

    private static void requireUnitInterval(double v, String what) throws JevUnavailableException {
        if (Double.isNaN(v) || v < 0.0 || v > 1.0) {
            throw new JevUnavailableException("Jev value out of range for " + what + ": " + v);
        }
    }

    // Small private shapes for the response; unknown fields (provider_metadata, probabilities, ...) are ignored.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JevResponse(String model, Map<String, Answer> answers, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Answer(String choice, Double confidence, Double noul) {
        ChoiceAnswer asChoice() {
            return new ChoiceAnswer(choice, confidence);
        }
    }

    private record ChoiceAnswer(String choice, Double confidence) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Usage(@JsonProperty("input_tokens") Integer inputTokens) {
    }
}
