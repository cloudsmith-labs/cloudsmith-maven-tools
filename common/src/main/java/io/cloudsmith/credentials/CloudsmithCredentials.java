package io.cloudsmith.credentials;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides, through the CLI, which repository URLs get the CLI credential.
 *
 * <p>Both answers of the CLI are cached for a short TTL. The credential contract carries no expiry,
 * and the token can be a short-lived OIDC or SAML token or an API key that the user can revoke at
 * any time.
 */
public final class CloudsmithCredentials {
  private static final Logger LOGGER = LoggerFactory.getLogger(CloudsmithCredentials.class);

  private final Supplier<CloudsmithCli> cliLocator;
  private final Cached<Set<String>> hosts;
  private final Cached<Credential> credential;

  public CloudsmithCredentials(
      Supplier<CloudsmithCli> cliLocator, Duration ttl, LongSupplier nanoClock) {
    this.cliLocator = cliLocator;
    this.hosts = new Cached<>(ttl, nanoClock);
    this.credential = new Cached<>(ttl, nanoClock);
  }

  public static CloudsmithCredentials fromEnvironment() {
    Map<String, String> environment = System.getenv();
    Duration timeout =
        seconds(environment, "CLOUDSMITH_MAVEN_TOOLS_TIMEOUT", Duration.ofSeconds(30));
    Duration ttl = seconds(environment, "CLOUDSMITH_MAVEN_TOOLS_TTL", Duration.ofMinutes(5));
    boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    return new CloudsmithCredentials(
        () -> CloudsmithCli.locate(System.getenv(), windows, timeout), ttl, System::nanoTime);
  }

  /**
   * Returns the CLI credential for an HTTPS URL on a host that the CLI lists, else {@code null}.
   */
  public Credential credentialFor(String url) {
    String host = httpsHost(url);
    if (host == null) {
      return null;
    }
    Set<String> mavenHosts = hosts.get(() -> withCli(CloudsmithCli::mavenHosts));
    if (mavenHosts == null || !mavenHosts.contains(host)) {
      return null;
    }
    return credential.get(() -> withCli(CloudsmithCli::credential));
  }

  /**
   * Returns the variable as a duration in seconds, or the default if it is not a positive, finite
   * number. A zero timeout or TTL is never what the user meant, and a silent fallback is safer than
   * a build that fails far from the cause.
   */
  static Duration seconds(Map<String, String> environment, String name, Duration defaultValue) {
    String value = environment.get(name);
    if (value == null || value.isEmpty()) {
      return defaultValue;
    }
    double seconds;
    try {
      seconds = Double.parseDouble(value);
    } catch (NumberFormatException notANumber) {
      seconds = Double.NaN;
    }
    if (seconds > 0 && Double.isFinite(seconds)) {
      return Duration.ofMillis((long) Math.ceil(seconds * 1000));
    }
    LOGGER.debug("{} is not a positive number of seconds; using {}", name, defaultValue);
    return defaultValue;
  }

  private static String httpsHost(String url) {
    try {
      URI uri = new URI(url);
      return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
          ? uri.getHost().toLowerCase(Locale.ROOT)
          : null;
    } catch (URISyntaxException malformed) {
      return null;
    }
  }

  private <T> T withCli(Function<CloudsmithCli, T> command) {
    CloudsmithCli cli = cliLocator.get();
    return cli == null ? null : command.apply(cli);
  }

  /**
   * Keeps the result of one load, including a {@code null} result, for the TTL.
   *
   * <p>A failed load is kept too. The build asks again for each repository or batch of transfers,
   * so a CLI that hangs until its timeout would otherwise stall each of them. Callers wait while
   * another thread loads, so concurrent transfers start one CLI process, not one each.
   */
  private static final class Cached<T> {
    private final long ttlNanos;
    private final LongSupplier nanoClock;
    private boolean loaded;
    private long loadedAt;
    private T value;

    Cached(Duration ttl, LongSupplier nanoClock) {
      this.ttlNanos = ttl.toNanos();
      this.nanoClock = nanoClock;
    }

    synchronized T get(Supplier<T> loader) {
      long now = nanoClock.getAsLong();
      if (!loaded || now - loadedAt >= ttlNanos) {
        value = loader.get();
        loadedAt = now;
        loaded = true;
      }
      return value;
    }
  }
}
