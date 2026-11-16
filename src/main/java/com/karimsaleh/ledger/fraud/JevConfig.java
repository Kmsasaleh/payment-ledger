package com.karimsaleh.ledger.fraud;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
public class JevConfig {

    @Bean
    public RestClient jevRestClient(FraudTriageProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.jev().connectTimeout());
        factory.setReadTimeout(props.jev().readTimeout());
        ClientHttpRequestFactory requestFactory = factory;
        return RestClient.builder()
                .baseUrl(props.jev().baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
