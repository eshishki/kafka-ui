package io.kafbat.ui.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.kafbat.ui.mapper.KafkaSrMapper;
import io.kafbat.ui.model.CompatibilityLevelDTO;
import io.kafbat.ui.model.KafkaCluster;
import io.kafbat.ui.sr.ApiClient;
import io.kafbat.ui.sr.api.KafkaSrClientApi;
import io.kafbat.ui.sr.model.Compatibility;
import io.kafbat.ui.util.ReactiveFailover;
import io.kafbat.ui.util.WebClientConfigurator;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;

class SchemaRegistryIcebergCompatibilityTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private final MockWebServer registry = new MockWebServer();
  private final KafkaSrMapper mapper = Mappers.getMapper(KafkaSrMapper.class);
  private SchemaRegistryService service;
  private KafkaCluster cluster;

  @BeforeEach
  void setUp() throws IOException {
    registry.start();
    var webClient = new WebClientConfigurator()
        .configureAdditionalDecoderMediaTypes(
            MediaType.parseMediaType("application/vnd.schemaregistry.v1+json"))
        .build();
    var client = new ApiClient(webClient).setBasePath(registry.url("/").toString());
    cluster = mock(KafkaCluster.class);
    when(cluster.getSchemaRegistryClient())
        .thenReturn(ReactiveFailover.createNoop(new KafkaSrClientApi(client)));
    service = new SchemaRegistryService(mock(StatisticsCache.class));
  }

  @AfterEach
  void tearDown() throws IOException {
    registry.close();
  }

  private void respond(String json) {
    registry.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/vnd.schemaregistry.v1+json")
        .setBody(json));
  }

  @Test
  void readsIcebergGlobalCompatibilityAndMapsToUi() {
    respond("{\"compatibilityLevel\":\"ICEBERG\"}");
    var level = service.getGlobalSchemaCompatibilityLevel(cluster).block(TIMEOUT);
    assertThat(level).isEqualTo(Compatibility.ICEBERG);
    assertThat(mapper.toDto(level)).isEqualTo(CompatibilityLevelDTO.CompatibilityEnum.ICEBERG);
  }

  @Test
  void readsIcebergSubjectCompatibilityWithoutDiscardingIt() {
    respond("{\"compatibilityLevel\":\"ICEBERG\"}");
    // An unknown enum used to be swallowed here, triggering the global BACKWARD fallback.
    assertThat(service.getSchemaCompatibilityLevel(cluster, "orders-value").block(TIMEOUT))
        .isEqualTo(Compatibility.ICEBERG);
    assertThat(registry.getRequestCount()).isEqualTo(1);
  }

  @Test
  void sendsIcebergSubjectUpdateFromUiDto() throws Exception {
    respond("{\"compatibility\":\"ICEBERG\"}");
    var dto = new JsonMapper().readValue("{\"compatibility\":\"ICEBERG\"}", CompatibilityLevelDTO.class);
    service.updateSchemaCompatibility(cluster, "orders-value", mapper.fromDto(dto.getCompatibility()))
        .block(TIMEOUT);
    var request = registry.takeRequest(5, TimeUnit.SECONDS);
    assertThat(request).isNotNull();
    assertThat(request.getMethod()).isEqualTo("PUT");
    assertThat(request.getPath()).endsWith("/config/orders-value");
    assertThat(new JsonMapper().readTree(request.getBody().readUtf8()).get("compatibility").asText())
        .isEqualTo("ICEBERG");
  }

  @Test
  void sendsIcebergGlobalUpdate() throws Exception {
    respond("{\"compatibility\":\"ICEBERG\"}");
    service.updateGlobalSchemaCompatibility(cluster, Compatibility.ICEBERG).block(TIMEOUT);
    var request = registry.takeRequest(5, TimeUnit.SECONDS);
    assertThat(request).isNotNull();
    assertThat(request.getMethod()).isEqualTo("PUT");
    assertThat(request.getPath()).endsWith("/config");
    assertThat(new JsonMapper().readTree(request.getBody().readUtf8()).get("compatibility").asText())
        .isEqualTo("ICEBERG");
  }

  @Test
  void standardModesStillRoundTrip() {
    for (var mode : Compatibility.values()) {
      assertThat(mapper.fromDto(mapper.toDto(mode))).isEqualTo(mode);
    }
    respond("{\"compatibilityLevel\":\"BACKWARD\"}");
    assertThat(service.getGlobalSchemaCompatibilityLevel(cluster).block(TIMEOUT))
        .isEqualTo(Compatibility.BACKWARD);
  }
}
