package io.cloudsmith.credentials;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
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
 * <p>The hosts almost never change, so the cache keeps a host list for a day. It keeps a failed
 * answer and the credential for a short TTL. The credential contract carries no expiry, and the
 * token can be a short-lived OIDC or SAML token or an API key that the user can revoke at any time.
 */
public final class CloudsmithCredentials {
  private static final Logger LOGGER = LoggerFactory.getLogger(CloudsmithCredentials.class);

  private static final Duration HOSTS_LIFETIME = Duration.ofHours(24);

  private final Supplier<CloudsmithCli> cliLocator;
  private final Cached<Set<String>> hosts;
  private final Cached<Credential> credential;

  public CloudsmithCredentials(
      Supplier<CloudsmithCli> cliLocator, Duration ttl, LongSupplier nanoClock) {
    this(cliLocator, ttl, HOSTS_LIFETIME, nanoClock, Instant::now);
  }

  CloudsmithCredentials(
      Supplier<CloudsmithCli> cliLocator,
      Duration ttl,
      Duration hostsLifetime,
      LongSupplier nanoClock,
      Supplier<Instant> wallClock) {
    this.cliLocator = cliLocator;
    this.hosts =
        new Cached<>(loaded -> lifetimeOfHosts(loaded, ttl, hostsLifetime), nanoClock, wallClock);
    this.credential = new Cached<>(loaded -> ttl, nanoClock, wallClock);
  }

  private static Duration lifetimeOfHosts(
      Set<String> hosts, Duration failureLifetime, Duration hostsLifetime) {
    if (hosts == null) {
      return failureLifetime;
    }
    return hosts.isEmpty() ? Duration.ZERO : hostsLifetime;
  }

  public static CloudsmithCredentials fromEnvironment() {
    Map<String, String> environment = System.getenv();
    Duration timeout =
        seconds(environment, "CLOUDSMITH_MAVEN_TOOLS_TIMEOUT", Duration.ofSeconds(30));
    Duration ttl = seconds(environment, "CLOUDSMITH_MAVEN_TOOLS_TTL", Duration.ofMinutes(5));
    Duration hostsLifetime =
        seconds(environment, "CLOUDSMITH_MAVEN_TOOLS_HOSTS_TTL", HOSTS_LIFETIME);
    boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    return new CloudsmithCredentials(
        () -> CloudsmithCli.locate(System.getenv(), windows, timeout),
        ttl,
        hostsLifetime,
        System::nanoTime,
        Instant::now);
  }

  /** Returns the credential in the cache, or {@code null} if the cache must ask the CLI again. */
  public Credential cachedCredential() {
    return credential.peek();
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
   * Keeps the result of one load, including a {@code null} result, for the lifetime of that result.
   *
   * <p>A failed load is kept too. The build asks again for each repository or batch of transfers,
   * so a CLI that hangs until its timeout would otherwise stall each of them. Callers wait while
   * another thread loads, so concurrent transfers start one CLI process, not one each.
   */
  private static final class Cached<T> {
    private final Function<T, Duration> lifetime;
    private final LongSupplier nanoClock;
    private final Supplier<Instant> wallClock;
    private boolean loaded;
    private long loadedAt;
    private Instant loadedAtWallClock;
    private Duration valueLifetime;
    private T value;

    Cached(Function<T, Duration> lifetime, LongSupplier nanoClock, Supplier<Instant> wallClock) {
      this.lifetime = lifetime;
      this.nanoClock = nanoClock;
      this.wallClock = wallClock;
    }

    synchronized T get(Supplier<T> loader) {
      if (!loaded || lifetimeIsOver()) {
        value = loader.get();
        loadedAt = nanoClock.getAsLong();
        loadedAtWallClock = wallClock.get();
        valueLifetime = lifetime.apply(value);
        loaded = true;
      }
      return value;
    }

    synchronized T peek() {
      return loaded && !lifetimeIsOver() ? value : null;
    }

    /** The monotonic clock stops while the machine sleeps, so either clock can end the lifetime. */
    private boolean lifetimeIsOver() {
      Duration awake = Duration.ofNanos(nanoClock.getAsLong() - loadedAt);
      Duration elapsed = Duration.between(loadedAtWallClock, wallClock.get());
      return awake.compareTo(valueLifetime) >= 0 || elapsed.compareTo(valueLifetime) >= 0;
    }
  }
}
