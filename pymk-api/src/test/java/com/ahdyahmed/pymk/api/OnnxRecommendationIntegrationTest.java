package com.ahdyahmed.pymk.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahdyahmed.pymk.api.support.LightRankerTestSupport;
import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import com.ahdyahmed.pymk.featurestore.PymkPairFeatureRepository;
import com.ahdyahmed.pymk.lightranker.LightRankerModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** HTTP -> L0 -> materialized PostgreSQL features -> real ONNX -> Redis, with no model mock. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfig.class)
class OnnxRecommendationIntegrationTest {

    @DynamicPropertySource
    static void modelPaths(DynamicPropertyRegistry registry) throws Exception {
        ModelFixture.configureModelPaths(registry);
    }

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired ConnectionRepository connections;
    @Autowired PymkPairFeatureRepository features;
    @Autowired StringRedisTemplate redis;

    @AfterEach
    void cleanFixture() {
        flushRedis();
        features.deleteAll();
        connections.deleteAll();
        members.deleteAll();
    }

    @Test
    void realModelServesProbabilityRankedHttpResponsesAndReusableCachedResults() throws Exception {
        flushRedis();
        Instant joined = Instant.parse("2026-01-01T00:00:00Z");
        List<Member> fixture = List.of(
                new Member(830001L, "Requester", null, "Acme", "School", "Cairo", joined),
                new Member(830002L, "Coworker", null, "Acme", null, null, joined),
                new Member(830003L, "Classmate", null, null, "School", "Cairo", joined));
        members.saveAllAndFlush(fixture);
        LightRankerTestSupport.materializeAllPairs(fixture, connections, features);

        String response = request(2);
        var recommendations = mapper.readTree(response).path("recommendations");
        assertThat(recommendations.size()).isEqualTo(2);
        assertThat(List.of(recommendations.get(0).path("candidateId").asLong(),
                recommendations.get(1).path("candidateId").asLong()))
                .containsExactlyInAnyOrder(830002L, 830003L);
        double first = recommendations.get(0).path("score").asDouble();
        double second = recommendations.get(1).path("score").asDouble();
        assertThat(first).isBetween(0.0, 1.0).isGreaterThanOrEqualTo(second);
        assertThat(second).isBetween(0.0, 1.0);
        assertThat(request(2)).isEqualTo(response);
        assertThat(mapper.readTree(request(1)).path("recommendations").get(0))
                .isEqualTo(recommendations.get(0));
    }

    private String request(int limit) throws Exception {
        var response = http.getForEntity("/api/v1/pymk/830001?limit=" + limit, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void flushRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    static class ModelFixture {
        // Scaler + logistic regression exported by the Day 19 Python trainer; same graph as the L1 adapter test.
        private static final String MODEL = "CAgSCHNrbDJvbm54GgYxLjIwLjAiB2FpLm9ubngoADIAOvcDCoUBCghmZWF0dXJlcxIIdmFyaWFibGUaBlNjYWxlciIGU2NhbGVyKikKBm9mZnNldD0AAGBAPQAAAD89AAAAPz0AAAA/PZqZGb49AABAQKABBiooCgVzY2FsZT3VAA4/PQAAAEA9AAAAQD0AAABAPabPPkA9LvnkPqABBjoKYWkub25ueC5tbAqIAgoIdmFyaWFibGUSBWxhYmVsEg1wcm9iYWJpbGl0aWVzGhBMaW5lYXJDbGFzc2lmaWVyIhBMaW5lYXJDbGFzc2lmaWVyKhkKEGNsYXNzbGFiZWxzX2ludHNAAEABoAEHKk0KDGNvZWZmaWNpZW50cz16vYi9Pfa6oz8947cXMT32uqO/PbgfQL49tR9Avj16vYg9Pfa6o78947cXsT32uqM/PbgfQD49tR9APqABBioZCgppbnRlcmNlcHRzPZ6KAzI9nooDsqABBioSCgttdWx0aV9jbGFzcxgAoAECKh0KDnBvc3RfdHJhbnNmb3JtIghMT0dJU1RJQ6ABAzoKYWkub25ueC5tbBIWbGlnaHQtbG9ncmVnLWphdmEtdGVzdFoYCghmZWF0dXJlcxIMCgoIARIGCgAKAggGYhEKBWxhYmVsEggKBggHEgIKAGIdCg1wcm9iYWJpbGl0aWVzEgwKCggBEgYKAAoCCAJCDgoKYWkub25ueC5tbBABQgQKABARQgQKABAR";

        static void configureModelPaths(DynamicPropertyRegistry registry) throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            Path directory = Files.createTempDirectory("pymk-api-onnx-test-");
            Path modelPath = directory.resolve("model.onnx");
            Path metadataPath = directory.resolve("model.metadata.json");
            directory.toFile().deleteOnExit();
            modelPath.toFile().deleteOnExit();
            metadataPath.toFile().deleteOnExit();
            byte[] bytes = Base64.getDecoder().decode(MODEL);
            Files.write(modelPath, bytes);
            var metadata = mapper.createObjectNode();
            metadata.put("schemaVersion", 1);
            metadata.put("modelVersion", "light-logreg-v1");
            metadata.set("features", mapper.valueToTree(LightRankerModel.FEATURE_NAMES));
            var input = metadata.putObject("inputTensor");
            input.put("name", "features");
            input.put("dtype", "float32");
            input.putArray("shape").addNull().add(6);
            metadata.put("positiveClass", 1);
            metadata.putObject("onnx").put("sha256",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            Files.writeString(metadataPath, mapper.writeValueAsString(metadata));
            registry.add("pymk.light-ranker.model-path", () -> modelPath.toString());
            registry.add("pymk.light-ranker.metadata-path", () -> metadataPath.toString());
        }
    }
}
