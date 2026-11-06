package io.cloudsmith.gradle;

import io.cloudsmith.credentials.CloudsmithCredentials;
import io.cloudsmith.credentials.Credential;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.initialization.Settings;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;
import org.gradle.api.publish.PublishingExtension;

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

  @Inject
  protected abstract ProviderFactory getProviders();

  @Override
  public void apply(Object target) {
    if (target instanceof Settings settings) {
      settings.getPluginManagement().getRepositories().all(this::authenticate);
      settings.getDependencyResolutionManagement().getRepositories().all(this::authenticate);
      settings
          .getGradle()
          .beforeProject(project -> project.getPluginManager().apply(CloudsmithPlugin.class));
    } else if (target instanceof Project project) {
      project.getRepositories().all(this::authenticate);
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

  private void authenticate(ArtifactRepository repository) {
    if (!(repository instanceof MavenArtifactRepository maven) || maven.getUrl() == null) {
      return;
    }
    String url = maven.getUrl().toString();
    List<String> usernameAndPassword =
        getProviders()
            .of(CliCredential.class, spec -> spec.getParameters().getUrl().set(url))
            .getOrNull();
    if (usernameAndPassword == null || hasCredentials(maven)) {
      return;
    }
    LOGGER.info("Using the cloudsmith CLI credential for {}", url);
    maven.credentials(
        credentials -> {
          credentials.setUsername(usernameAndPassword.get(0));
          credentials.setPassword(usernameAndPassword.get(1));
        });
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

    /** The parameters of the value source. */
    public interface Parameters extends ValueSourceParameters {
      Property<String> getUrl();
    }

    @Override
    public List<String> obtain() {
      Credential credential =
          BY_ENVIRONMENT
              .computeIfAbsent(
                  cliEnvironment(), environment -> CloudsmithCredentials.fromEnvironment())
              .credentialFor(getParameters().getUrl().get());
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
