package com.karimsaleh.ledger.fraud;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** No network: every request goes to a {@link MockRestServiceServer}. */
class HttpJevClientTest {

    private static final String URL = "https://test.invalid/typesafe/v1/systemone";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T14:00:00Z"), ZoneOffset.UTC);
    private static final TransferFeatures FEATURES = SyntheticTransfers.ACCOUNT_TAKEOVER.features();

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("https://test.invalid/typesafe");
        server = MockRestServiceServer.bindTo(builder).build();
    }

    // ---- success ----

    @Test
    void parsesFullResponse() throws Exception {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(resource("response-high-risk-full.json"), MediaType.APPLICATION_JSON));

        JevAssessment assessment = client(props("test-key", 500)).assess(FEATURES);

        assertThat(assessment.riskLevel()).isEqualTo(RiskLevel.HIGH);
        // The response has probabilities.high = 0.89 but confidence = 0.83. We must use confidence.
        assertThat(assessment.confidence()).isEqualTo(0.83);
        assertThat(assessment.flags()).containsOnlyKeys(ReasonFlag.values());
        assertThat(assessment.flags().get(ReasonFlag.UNUSUAL_AMOUNT)).isEqualTo(0.97);
        assertThat(assessment.flags().get(ReasonFlag.NEW_RECIPIENT)).isEqualTo(0.94);
        assertThat(assessment.flags().get(ReasonFlag.VELOCITY_SPIKE)).isEqualTo(0.12);
        assertThat(assessment.flags().get(ReasonFlag.ODD_TIME)).isEqualTo(0.80);
        assertThat(assessment.inputTokens()).isEqualTo(392);
        assertThat(assessment.model()).isEqualTo("typesafe-ai/jev");
        assertThat(assessment.latencyMs()).isGreaterThanOrEqualTo(0);
        server.verify();
    }

    @Test
    void parsesLowRiskResponse() throws Exception {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(resource("response-low-risk-full.json"), MediaType.APPLICATION_JSON));

        JevAssessment assessment = client(props("test-key", 500)).assess(FEATURES);

        assertThat(assessment.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(assessment.confidence()).isEqualTo(0.95);
    }

    @Test
    void sendsExpectedRequest() throws Exception {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("typesafe-ai/jev"))
                .andExpect(content().string(containsString("fraud_risk")))
                .andExpect(content().string(containsString("unusual_amount")))
                .andExpect(content().string(containsString("new_recipient")))
                .andExpect(content().string(containsString("velocity_spike")))
                .andExpect(content().string(containsString("odd_time")))
                .andExpect(content().string(not(containsString("providerOptions"))))
                .andRespond(withSuccess(resource("response-high-risk-full.json"), MediaType.APPLICATION_JSON));

        client(props("test-key", 500)).assess(FEATURES);

        server.verify();
    }

    // ---- strict parsing ----

    @Test
    void realCapturedResponseWithOnlyTwoAnswersIsRejected() {
        // The captured response answered fraud_risk and new_recipient only. Missing flags
        // mean we can't build a full assessment, so we fail closed (HOLD), never guess.
        server.expect(requestTo(URL))
                .andRespond(withSuccess(resource("real-response-high-risk.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(props("test-key", 500)).assess(FEATURES))
                .isInstanceOf(JevUnavailableException.class);
    }

    @Test
    void malformedJsonIsRejected() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"answers\": {not json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(props("test-key", 500)).assess(FEATURES))
                .isInstanceOf(JevUnavailableException.class);
    }

    @Test
    void emptyBodyIsRejected() {
        server.expect(requestTo(URL)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(props("test-key", 500)).assess(FEATURES))
                .isInstanceOf(JevUnavailableException.class);
    }

    @Test
    void unknownChoiceIsRejected() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(resource("response-unknown-choice.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(props("test-key", 500)).assess(FEATURES))
                .isInstanceOf(JevUnavailableException.class);
    }

    @Test
    void confidenceAboveOneIsRejected() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(resource("response-confidence-out-of-range.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(props("test-key", 500)).assess(FEATURES))
                .isInstanceOf(JevUnavailableException.class);
    }

    // ---- transport failures ----

    @Test
    void serverErrorIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client(props("test-key", 500)).assess(FEATURES))
                .isInstanceOf(JevUnavailableException.class);
    }

    @Test
    void rateLimitIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client(props("test-key", 500)).assess(FEATURES))
                .isInstanceOf(JevUnavailableException.class);
    }

    @Test
    void forbiddenIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client(props("test-key", 500)).assess(FEATURES))
                .isInstanceOf(JevUnavailableException.class);
    }

    @Test
    void timeoutIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client(props("test-key", 500)).assess(FEATURES))
                .isInstanceOf(JevUnavailableException.class);
    }

    @Test
    void ioErrorIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withException(new IOException("connection reset")));

        assertThatThrownBy(() -> client(props("test-key", 500)).assess(FEATURES))
                .isInstanceOf(JevUnavailableException.class);
    }

    // ---- no retries ----

    @Test
    void failedCallIsNotRetried() {
        // Exactly one request is allowed. A retry would hit "no further requests expected".
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client(props("test-key", 500)).assess(FEATURES))
                .isInstanceOf(JevUnavailableException.class);

        server.verify();
    }

    @Test
    void successfulCallMakesExactlyOneRequest() throws Exception {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess(resource("response-low-risk-full.json"), MediaType.APPLICATION_JSON));

        client(props("test-key", 500)).assess(FEATURES);

        server.verify();
    }

    // ---- guards before the network ----

    @Test
    void blankApiKeyFailsWithoutAnyRequest() {
        assertThatThrownBy(() -> client(props("", 500)).assess(FEATURES))
                .isInstanceOf(JevUnavailableException.class);

        server.verify(); // no expectations were set, so any request would already have failed
    }

    @Test
    void whitespaceApiKeyFailsWithoutAnyRequest() {
        assertThatThrownBy(() -> client(props("   ", 500)).assess(FEATURES))
                .isInstanceOf(JevUnavailableException.class);

        server.verify();
    }

    @Test
    void dailyCapStopsCallsWithoutRequest() throws Exception {
        server.expect(ExpectedCount.times(2), requestTo(URL))
                .andRespond(withSuccess(resource("response-low-risk-full.json"), MediaType.APPLICATION_JSON));
        HttpJevClient client = client(props("test-key", 2));

        client.assess(FEATURES);
        client.assess(FEATURES);
        assertThatThrownBy(() -> client.assess(FEATURES)).isInstanceOf(JevUnavailableException.class);

        server.verify(); // exactly two requests were made; the third never left the client
    }

    // ---- helpers ----

    private HttpJevClient client(FraudTriageProperties props) {
        return new HttpJevClient(builder.build(), props, CLOCK);
    }

    private static FraudTriageProperties props(String apiKey, int maxCallsPerDay) {
        return new FraudTriageProperties(
                new FraudTriageProperties.Triage(0.80, 0.5, true),
                new FraudTriageProperties.Jev("https://test.invalid/typesafe", "typesafe-ai/jev", apiKey,
                        Duration.ofSeconds(1), Duration.ofSeconds(2), maxCallsPerDay));
    }

    private static String resource(String name) {
        try {
            return new ClassPathResource("jev/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing test resource " + name, e);
        }
    }
}
