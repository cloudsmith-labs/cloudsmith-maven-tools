package io.cloudsmith.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cloudsmith.credentials.CloudsmithCli;
import io.cloudsmith.credentials.CloudsmithCredentials;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.AuthenticationContext;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.spi.connector.transport.TransporterProvider;
import org.eclipse.aether.transfer.NoTransporterException;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CloudsmithTransporterFactoryTest {
  private static final String DOMAINS =
      "{\"version\": 1, \"domains\": [{\"host\": \"maven.cloudsmith.io\", \"format\": \"maven\","
          + " \"domain_type\": \"native_api\"},{\"host\": \"dl.cloudsmith.io\", \"format\": null,"
          + " \"domain_type\": \"download\"}]}";

  private final RepositorySystemSession session = new DefaultRepositorySystemSession();
  private final Transporter transporter =
      (Transporter)
          Proxy.newProxyInstance(
              Transporter.class.getClassLoader(),
              new Class<?>[] {Transporter.class},
              (proxy, method, arguments) -> null);
  private final List<RemoteRepository> delegated = new ArrayList<>();
  private final List<String> commands = new ArrayList<>();
  private String credentialStdout =
      "{\"version\": 1, \"username\": \"token\", \"password\": \"secret\"}";

  private final TransporterProvider transporterProvider =
      (providedSession, repository) -> {
        assertSame(session, providedSession);
        delegated.add(repository);
        return transporter;
      };

  private final CloudsmithTransporterFactory factory =
      new CloudsmithTransporterFactory(
          () -> transporterProvider,
          new CloudsmithCredentials(
              () ->
                  new CloudsmithCli(
                      arguments -> {
                        commands.add(arguments.get(0));
                        String stdout =
                            arguments.get(0).equals("domains") ? DOMAINS : credentialStdout;
                        return stdout == null ? null : new CloudsmithCli.Output(stdout, "");
                      }),
              Duration.ofNanos(1_000),
              new AtomicLong()::get));

  @Test
  void outranksMavensBuiltInTransports() {
    assertTrue(factory.getPriority() > 5.0f);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://dl.cloudsmith.io/basic/acme/releases/maven/",
        "https://maven.cloudsmith.io/acme/releases/",
        "HTTPS://MAVEN.cloudsmith.io:443/acme/releases/"
      })
  void attachesTheCliCredentialToACloudsmithRepository(String url) throws Exception {
    assertSame(transporter, factory.newInstance(session, repository(url)));

    assertEquals(1, delegated.size());
    RemoteRepository authenticated = delegated.get(0);
    assertEquals(url, authenticated.getUrl());
    try (AuthenticationContext context =
        AuthenticationContext.forRepository(session, authenticated)) {
      assertEquals("token", context.get(AuthenticationContext.USERNAME));
      assertEquals("secret", context.get(AuthenticationContext.PASSWORD));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://repo.maven.apache.org/maven2/",
        "https://maven.cloudsmith.io.example.com/acme/releases/",
        "http://maven.cloudsmith.io/acme/releases/",
        "file:///tmp/repository",
        "https://maven.cloudsmith.io/bad path with spaces"
      })
  void stepsAsideForARepositoryItShouldNotAuthenticate(String url) {
    assertThrows(NoTransporterException.class, () -> factory.newInstance(session, repository(url)));

    assertEquals(List.of(), delegated);
    assertFalse(commands.contains("credential-helper"));
  }

  @Test
  void stepsAsideWithoutRunningTheCliWhenTheRepositoryAlreadyHasCredentials() {
    RemoteRepository withCredentials =
        new RemoteRepository.Builder(repository("https://maven.cloudsmith.io/acme/releases/"))
            .setAuthentication(
                new AuthenticationBuilder().addUsername("explicit").addPassword("secret").build())
            .build();

    assertThrows(NoTransporterException.class, () -> factory.newInstance(session, withCredentials));

    assertEquals(List.of(), delegated);
    assertEquals(List.of(), commands);
  }

  @Test
  void stepsAsideWhenTheCliHasNoCredential() {
    credentialStdout = null;

    assertThrows(
        NoTransporterException.class,
        () ->
            factory.newInstance(session, repository("https://maven.cloudsmith.io/acme/releases/")));

    assertEquals(List.of(), delegated);
  }

  private static RemoteRepository repository(String url) {
    return new RemoteRepository.Builder("cloudsmith", "default", url).build();
  }
}
