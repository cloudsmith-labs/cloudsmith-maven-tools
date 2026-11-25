package io.cloudsmith.gradle;

import io.cloudsmith.credentials.CloudsmithCredentials;
import io.cloudsmith.credentials.Credential;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.DependencyResolutionListener;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.repositories.PasswordCredentials;
import org.gradle.api.initialization.Settings;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.util.GradleVersion;

/**
 * Authenticates Gradle to Cloudsmith with the credential of the {@code cloudsmith} CLI.
 *
 * <p>Apply it in {@code settings.gradle(.kts)} to cover the plugin and dependency repositories of
 * the settings and the repositories of every project. Apply it to one project to cover only that
 * project. For an HTTPS Maven repository on a host that the CLI lists, it sets the CLI credential.
 * It leaves every other repository, and a repository that already has credentials, as it is.
 */
public abstract class CloudsmithPlugin implements Plugin<Object> {
  private static final Logger LOGGER = Logging.getLogger(CloudsmithPlugin.class);

  /** Gradle before 8.1 reports each listener on the build as a configuration cache problem. */
  private static final boolean BUILD_LISTENER_SUPPORTED =
      GradleVersion.current().getBaseVersion().compareTo(GradleVersion.version("8.1")) >= 0;

  private static final Set<Gradle> REFRESHED =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

  private final Map<MavenArtifactRepository, Installed> authenticated = new ConcurrentHashMap<>();

  /** Gradle calls each build listener for each resolution, so a project adds its listener late. */
  private Runnable startRenewal = () -> {};

  @Inject
  protected abstract ProviderFactory getProviders();

  @Override
  public void apply(Object target) {
    if (target instanceof Settings settings) {
      askTheCliAgainToRefreshDependencies(settings.getGradle());
      settings.getPluginManagement().getRepositories().all(this::authenticate);
      settings.getDependencyResolutionManagement().getRepositories().all(this::authenticate);
      renewBeforeEachResolution(settings.getGradle(), resolving -> true);
      settings
          .getGradle()
          .beforeProject(
              project -> {
                project.getPluginManager().apply(CloudsmithPlugin.class);
                renewBeforeEachResolution(project);
              });
    } else if (target instanceof Project project) {
      askTheCliAgainToRefreshDependencies(project.getGradle());
      String projectPath = project.getPath();
      startRenewal =
          () ->
              renewBeforeEachResolution(
                  project.getGradle(), resolving -> isIn(resolving, projectPath));
      project.getRepositories().all(this::authenticate);
      renewBeforeEachResolution(project);
      project
          .getPluginManager()
          .withPlugin(
              "maven-publish",
              applied ->
                  project
                      .getExtensions()
                      .getByType(PublishingExtension.class)
                      .getRepositories()
                      .all(this::authenticate));
    } else {
      throw new IllegalArgumentException(
          "Apply io.cloudsmith.gradle-plugin to settings or to a project, not to " + target);
    }
  }

  /** An included build shares the cached answers of the build that includes it. */
  private static void askTheCliAgainToRefreshDependencies(Gradle gradle) {
    Gradle root = gradle;
    while (root.getParent() != null) {
      root = root.getParent();
    }
    if (root.getStartParameter().isRefreshDependencies() && REFRESHED.add(root)) {
      CliCredential.forgetAnswers();
    }
  }

  private void authenticate(ArtifactRepository repository) {
    if (!(repository instanceof MavenArtifactRepository maven) || maven.getUrl() == null) {
      return;
    }
    String url = maven.getUrl().toString();
    List<String> usernameAndPassword = cliCredential(url);
    if (usernameAndPassword == null || hasCredentials(maven)) {
      return;
    }
    LOGGER.info("Using the cloudsmith CLI credential for {}", url);
    PasswordCredentials credentials = maven.getCredentials();
    setCredential(credentials, usernameAndPassword);
    authenticated.put(maven, new Installed(credentials, usernameAndPassword));
    startRenewal.run();
    startRenewal = () -> {};
  }

  /**
   * Gradle reads the credential of a repository each time it starts a resolution, and the CLI can
   * renew a short-lived token during a build. A listener on the build also covers detached
   * configurations.
   */
  private void renewBeforeEachResolution(
      Gradle gradle, Predicate<ResolvableDependencies> renewsFor) {
    if (!BUILD_LISTENER_SUPPORTED) {
      return;
    }
    gradle.addListener(
        new DependencyResolutionListener() {
          @Override
          public void beforeResolve(ResolvableDependencies dependencies) {
            if (renewsFor.test(dependencies)) {
              renew();
            }
          }

          @Override
          public void afterResolve(ResolvableDependencies dependencies) {}
        });
  }

  /**
   * Returns whether the resolution can belong to the project. In an included build, the path of a
   * resolution starts with the path of that build. The check therefore compares the end of the
   * owner path. A false match only renews the credential more often.
   */
  private static boolean isIn(ResolvableDependencies resolving, String projectPath) {
    String path = resolving.getPath();
    String owner = path.substring(0, Math.max(0, path.lastIndexOf(':')));
    return owner.endsWith(":".equals(projectPath) ? "" : projectPath);
  }

  /** Without the build listener, detached configurations keep the last credential. */
  private void renewBeforeEachResolution(Project project) {
    if (BUILD_LISTENER_SUPPORTED) {
      return;
    }
    project
        .getConfigurations()
        .configureEach(
            configuration -> configuration.getIncoming().beforeResolve(resolving -> renew()));
  }

  /**
   * Sets a new CLI credential on the credentials object of each repository. A call to the
   * repository discards its descriptor, which a worker of a parallel build can read at that moment.
   *
   * <p>Gradle records each value source that a build reads at configuration time, so a repository
   * that has the cached credential reads none. The CLI gets the URL that the repository has now, so
   * a repository that the build moved to another host gets no new credential.
   */
  private synchronized void renew() {
    Credential cached = CliCredential.cached();
    List<String> current = cached == null ? null : List.of(cached.username(), cached.password());
    authenticated.forEach(
        (maven, installed) -> {
          if (installed.usernameAndPassword().equals(current)) {
            return;
          }
          URI url = maven.getUrl();
          List<String> usernameAndPassword = url == null ? null : cliCredential(url.toString());
          if (usernameAndPassword == null
              || usernameAndPassword.equals(installed.usernameAndPassword())) {
            return;
          }
          if (buildReplacedTheCredential(installed)) {
            authenticated.remove(maven);
            return;
          }
          setCredential(installed.credentials(), usernameAndPassword);
          authenticated.put(maven, new Installed(installed.credentials(), usernameAndPassword));
        });
  }

  private static boolean buildReplacedTheCredential(Installed installed) {
    PasswordCredentials credentials = installed.credentials();
    return !installed
        .usernameAndPassword()
        .equals(Arrays.asList(credentials.getUsername(), credentials.getPassword()));
  }

  private record Installed(PasswordCredentials credentials, List<String> usernameAndPassword) {}

  private List<String> cliCredential(String url) {
    return getProviders()
        .of(CliCredential.class, spec -> spec.getParameters().getUrl().set(url))
        .getOrNull();
  }

  private static void setCredential(
      PasswordCredentials credentials, List<String> usernameAndPassword) {
    credentials.setUsername(usernameAndPassword.get(0));
    credentials.setPassword(usernameAndPassword.get(1));
  }

  /**
   * Returns whether the build already set credentials, which then win over the CLI credential.
   * Gradle reports credentials of another type, such as HTTP header credentials, and lazy
   * credentials without a value, with an {@link IllegalStateException}.
   */
  private static boolean hasCredentials(MavenArtifactRepository repository) {
    try {
      var existing = repository.getCredentials();
      return existing.getUsername() != null || existing.getPassword() != null;
    } catch (IllegalStateException otherCredentials) {
      return true;
    }
  }

  /**
   * Returns the username and password of the CLI credential for a repository URL, or no value.
   *
   * <p>The configuration cache runs a value source again before it reuses a cached configuration,
   * so a rotated token or a changed host list makes Gradle configure the build again. The value is
   * a list, not a {@link Credential}, because Gradle 8.0 and earlier cannot cache a record.
   */
  public abstract static class CliCredential
      implements ValueSource<List<String>, CliCredential.Parameters> {
    /** The Gradle daemon outlives a build, so each set of CLI settings gets its own cache. */
    private static final Map<Map<String, String>, CloudsmithCredentials> BY_ENVIRONMENT =
        new ConcurrentHashMap<>();

    private static volatile CloudsmithCredentials lastRead;

    /** The parameters of the value source. */
    public interface Parameters extends ValueSourceParameters {
      Property<String> getUrl();
    }

    static void forgetAnswers() {
      BY_ENVIRONMENT.clear();
      lastRead = null;
    }

    static Credential cached() {
      CloudsmithCredentials last = lastRead;
      return last == null ? null : last.cachedCredential();
    }

    @Override
    public List<String> obtain() {
      CloudsmithCredentials credentials =
          BY_ENVIRONMENT.computeIfAbsent(
              cliEnvironment(), environment -> CloudsmithCredentials.fromEnvironment());
      lastRead = credentials;
      Credential credential = credentials.credentialFor(getParameters().getUrl().get());
      // A plain ArrayList, because the configuration cache cannot restore the List.of classes.
      return credential == null
          ? null
          : new ArrayList<>(List.of(credential.username(), credential.password()));
    }

    private static Map<String, String> cliEnvironment() {
      Map<String, String> environment = new TreeMap<>(System.getenv());
      environment.keySet().removeIf(name -> !affectsCli(name));
      return environment;
    }

    /** The TLS and proxy variables of the HTTP client of the CLI. */
    private static final Set<String> NETWORK_VARIABLES =
        Set.of(
            "REQUESTS_CA_BUNDLE",
            "CURL_CA_BUNDLE",
            "SSL_CERT_FILE",
            "SSL_CERT_DIR",
            "HTTPS_PROXY",
            "HTTP_PROXY",
            "ALL_PROXY",
            "NO_PROXY");

    /** Whether the variable changes which CLI runs or what it answers. */
    private static boolean affectsCli(String name) {
      return name.startsWith("CLOUDSMITH_")
          || name.equals("PATH")
          || name.equals("HOME")
          || NETWORK_VARIABLES.contains(name.toUpperCase(Locale.ROOT));
    }
  }
}
