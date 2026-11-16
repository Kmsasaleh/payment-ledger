package com.karimsaleh.ledger;

import com.karimsaleh.ledger.fraud.FakeJevClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
	}

	/** Replaces the real Jev client in every Spring test, so no test can spend money or need an API key. */
	@Bean
	@Primary
	FakeJevClient fakeJevClient() {
		return new FakeJevClient();
	}

}
