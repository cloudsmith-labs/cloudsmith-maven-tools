package io.cloudsmith.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
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
  private static final Instant START = Instant.ofEpochSecond(1_800_000_000L);

  private final AtomicLong clock = new AtomicLong();
  private final List<String> commands = new ArrayList<>();
  private String domainsStdout = DOMAINS;
  private String credentialStdout = CREDENTIAL;
  private Duration credentialHelperDuration = Duration.ZERO;
  private Duration asleep = Duration.ZERO;

  private final CloudsmithCredentials credentials =
      new CloudsmithCredentials(
          () ->
              new CloudsmithCli(
                  arguments -> {
                    commands.add(arguments.get(0));
                    if (arguments.get(0).equals("credential-helper")) {
                      clock.addAndGet(credentialHelperDuration.toNanos());
                    }
                    String stdout =
                        arguments.get(0).equals("domains") ? domainsStdout : credentialStdout;
                    return stdout == null ? null : new CloudsmithCli.Output(stdout, "");
                  }),
          TTL,
          Duration.ofHours(24),
          clock::get,
          () -> START.plusNanos(clock.get()).plus(asleep));

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

    assertEquals(List.of("domains", "credential-helper", "credential-helper"), commands);
  }

  @Test
  void keepsTheHostsForADay() {
    String url = "https://maven.cloudsmith.io/acme/repo/";
    credentials.credentialFor(url);
    clock.set(Duration.ofHours(23).toNanos());
    credentials.credentialFor(url);
    assertEquals(1, commands.stream().filter("domains"::equals).count());

    clock.set(Duration.ofHours(24).toNanos());
    credentials.credentialFor(url);
    assertEquals(2, commands.stream().filter("domains"::equals).count());
  }

  @Test
  void asksAgainForTheHostsWhenTheTtlOfAFailureElapses() {
    String url = "https://maven.cloudsmith.io/acme/repo/";
    domainsStdout = null;
    assertNull(credentials.credentialFor(url));
    domainsStdout = DOMAINS;

    clock.set(100L);
    assertEquals(EXPECTED, credentials.credentialFor(url));
  }

  @Test
  void asksAgainForTheHostsAfterAnEmptyList() {
    String url = "https://maven.cloudsmith.io/acme/repo/";
    domainsStdout = "{\"version\": 1, \"domains\": []}";
    assertNull(credentials.credentialFor(url));
    domainsStdout = DOMAINS;

    assertEquals(EXPECTED, credentials.credentialFor(url));
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
  void startsTheLifetimeOfACredentialWhenTheCliAnswers() {
    String url = "https://maven.cloudsmith.io/acme/repo/";
    credentialHelperDuration = TTL;

    credentials.credentialFor(url);
    credentials.credentialFor(url);

    assertEquals(1, commands.stream().filter("credential-helper"::equals).count());
  }

  @Test
  void asksAgainWhenACredentialPassesItsTtlWhileTheMachineSleeps() {
    assertEquals(1, credentialCallsAfter(Duration.ZERO));

    asleep = Duration.ofHours(8);
    assertEquals(2, credentialCallsAfter(Duration.ZERO));
  }

  @Test
  void keepsTheHostsForTheGivenLifetime() {
    String url = "https://maven.cloudsmith.io/acme/repo/";
    List<String> asked = new ArrayList<>();
    var hourly =
        new CloudsmithCredentials(
            () ->
                new CloudsmithCli(
                    arguments -> {
                      asked.add(arguments.get(0));
                      return new CloudsmithCli.Output(
                          arguments.get(0).equals("domains") ? DOMAINS : CREDENTIAL, "");
                    }),
            TTL,
            Duration.ofHours(1),
            clock::get,
            () -> START.plusNanos(clock.get()));

    hourly.credentialFor(url);
    clock.set(Duration.ofMinutes(59).toNanos());
    hourly.credentialFor(url);
    assertEquals(1, asked.stream().filter("domains"::equals).count());

    clock.set(Duration.ofHours(1).toNanos());
    hourly.credentialFor(url);
    assertEquals(2, asked.stream().filter("domains"::equals).count());
  }

  @Test
  void returnsTheCachedCredentialWithoutACallToTheCli() {
    assertNull(credentials.cachedCredential());

    credentials.credentialFor("https://maven.cloudsmith.io/acme/repo/");
    assertEquals(EXPECTED, credentials.cachedCredential());

    clock.set(TTL.toNanos());
    assertNull(credentials.cachedCredential());
    assertEquals(List.of("domains", "credential-helper"), commands);
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

  @Test
  void keepsACredentialForATtlThatIsTooLongToCountInNanoseconds() {
    Duration ttl = CloudsmithCredentials.seconds(Map.of("NAME", "1e16"), "NAME", TTL);
    var longLived =
        new CloudsmithCredentials(
            () ->
                new CloudsmithCli(
                    arguments ->
                        new CloudsmithCli.Output(
                            arguments.get(0).equals("domains") ? DOMAINS : CREDENTIAL, "")),
            ttl,
            clock::get);

    assertEquals(EXPECTED, longLived.credentialFor("https://maven.cloudsmith.io/acme/repo/"));
  }

  /** Asks for the credential when the clock reads {@code elapsed}, and counts the CLI calls. */
  private long credentialCallsAfter(Duration elapsed) {
    clock.set(elapsed.toNanos());
    credentials.credentialFor("https://maven.cloudsmith.io/acme/repo/");
    return commands.stream().filter("credential-helper"::equals).count();
  }
}
