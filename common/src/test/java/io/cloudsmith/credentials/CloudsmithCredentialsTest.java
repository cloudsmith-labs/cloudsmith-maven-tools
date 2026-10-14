package io.cloudsmith.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CloudsmithCredentialsTest {
  private static final String DOMAINS =
      "{\"version\": 1, \"domains\": [{\"host\": \"maven.cloudsmith.io\", \"format\": \"maven\","
          + " \"domain_type\": \"native_api\"}]}";
  private static final String CREDENTIAL =
      "{\"version\": 1, \"username\": \"token\", \"password\": \"secret\"}";
  private static final Credential EXPECTED = new Credential("token", "secret");
  private static final Duration TTL = Duration.ofNanos(100);

  private final AtomicLong clock = new AtomicLong();
  private final List<String> commands = new ArrayList<>();
  private String credentialStdout = CREDENTIAL;

  private final CloudsmithCredentials credentials =
      new CloudsmithCredentials(
          () ->
              new CloudsmithCli(
                  arguments -> {
                    commands.add(arguments.get(0));
                    return arguments.get(0).equals("domains") ? DOMAINS : credentialStdout;
                  }),
          TTL,
          clock::get);

  @Test
  void returnsTheCredentialForAnHttpsUrlOnAHostTheCliLists() {
    assertEquals(EXPECTED, credentials.credentialFor("https://MAVEN.cloudsmith.io/acme/repo/"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://maven.cloudsmith.io/acme/repo/",
        "https://repo.maven.apache.org/maven2/",
        "file:///tmp/repository",
        "not a url"
      })
  void returnsNoCredentialForAnyOtherUrl(String url) {
    assertNull(credentials.credentialFor(url));
  }

  @Test
  void asksTheCliForTheHostsOnceWithinTheTtl() {
    credentials.credentialFor("https://repo.maven.apache.org/maven2/");
    credentials.credentialFor("https://maven.cloudsmith.io/acme/repo/");

    assertEquals(List.of("domains", "credential-helper"), commands);
  }

  @Test
  void refreshesTheCredentialWhenTheTtlElapses() {
    String url = "https://maven.cloudsmith.io/acme/repo/";
    credentials.credentialFor(url);
    clock.set(99L);
    credentials.credentialFor(url);
    clock.set(100L);
    credentials.credentialFor(url);

    assertEquals(List.of("domains", "credential-helper", "domains", "credential-helper"), commands);
  }

  @Test
  void reusesAFailedAnswerUntilTheTtlElapses() {
    String url = "https://maven.cloudsmith.io/acme/repo/";
    credentialStdout = null;
    assertNull(credentials.credentialFor(url));
    credentialStdout = CREDENTIAL;
    assertNull(credentials.credentialFor(url));

    clock.set(100L);
    assertEquals(EXPECTED, credentials.credentialFor(url));
  }

  @Test
  void returnsNoCredentialWithoutACli() {
    var withoutCli = new CloudsmithCredentials(() -> null, TTL, clock::get);

    assertNull(withoutCli.credentialFor("https://maven.cloudsmith.io/acme/repo/"));
  }

  @Test
  void readsADurationInSeconds() {
    assertEquals(
        Duration.ofMillis(2_500),
        CloudsmithCredentials.seconds(Map.of("NAME", "2.5"), "NAME", Duration.ZERO));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "abc", "0", "-1", "NaN", "Infinity", "-Infinity", "1e400"})
  void usesTheDefaultForAValueThatIsNotAPositiveFiniteNumber(String value) {
    Duration fallback = Duration.ofSeconds(30);

    assertEquals(fallback, CloudsmithCredentials.seconds(Map.of("NAME", value), "NAME", fallback));
    assertEquals(fallback, CloudsmithCredentials.seconds(Map.of(), "NAME", fallback));
  }
}
