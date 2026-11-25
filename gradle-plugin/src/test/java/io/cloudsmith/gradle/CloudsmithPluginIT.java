package io.cloudsmith.gradle;

import static io.cloudsmith.testing.HttpsRepository.basic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.cloudsmith.testing.FakeCloudsmithCli;
import io.cloudsmith.testing.HttpsRepository;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs real Gradle builds with the packaged plugin against a local HTTPS repository.
 *
 * <p>A fake {@code cloudsmith} script stands in for the CLI, so the test needs no network and no
 * Cloudsmith account. The Gradle under test is {@code it.gradle.home} if it is set, else the Gradle
 * that runs this build, so a CI matrix over Gradle versions covers each of them.
 */
@DisabledOnOs(OS.WINDOWS)
class CloudsmithPluginIT {
  private static final String LIBRARY = "com/example/it-library/1.0/it-library-1.0";
  private static final String LIBRARY_POM =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>it-library</artifactId>
        <version>1.0</version>
      </project>
      """;
  private static final String RESOLVE_BUILD =
      """
      configurations { fetched }
      dependencies { fetched 'com.example:it-library:1.0' }
      tasks.register('resolve') {
        def files = configurations.fetched
        doLast { println "Resolved ${files.files*.name}" }
      }
      """;

  @TempDir Path workDirectory;

  private HttpsRepository repository;
  private Path project;

  @BeforeEach
  void startServer() throws Exception {
    repository = new HttpsRepository(workDirectory);
    repository.addFile(LIBRARY + ".pom", LIBRARY_POM);
    repository.addFile(LIBRARY + ".jar", "not a real jar");
    project = Files.createDirectories(workDirectory.resolve("project"));
  }

  @AfterEach
  void stopServer() {
    repository.close();
  }

  @Test
  void authenticatesADependencyRepositoryDeclaredInSettings() throws Exception {
    writeSettings(applyPlugin() + dependencyRepository(""));
    writeBuild(RESOLVE_BUILD);

    BuildResult result = runner(HttpsRepository.HOST, "resolve").build();

    assertTrue(result.getOutput().contains("Resolved [it-library-1.0.jar]"), result.getOutput());
    assertTrue(repository.receivedAuthorizations().contains(cliCredential()), result.getOutput());
  }

  @Test
  void authenticatesAPluginRepositoryDeclaredInSettings() throws Exception {
    repository.addFile(
        "com/example/it/com.example.it.gradle.plugin/1.0/com.example.it.gradle.plugin-1.0.pom",
        """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example.it</groupId>
          <artifactId>com.example.it.gradle.plugin</artifactId>
          <version>1.0</version>
          <packaging>pom</packaging>
        </project>
        """);
    writeSettings(
        "pluginManagement { repositories { maven { url = '%s' } } }\n".formatted(repository.url())
            + applyPlugin());
    writeBuild("plugins { id 'com.example.it' version '1.0' }\n");

    // The marker has no plugin jar, so the build fails after it downloads the marker.
    BuildResult result = runner(HttpsRepository.HOST, "help").buildAndFail();

    assertTrue(repository.receivedAuthorizations().contains(cliCredential()), result.getOutput());
  }

  @Test
  void authenticatesAProjectRepositoryWhenAppliedToTheProject() throws Exception {
    writeSettings("");
    writeBuild(
        applyPlugin()
            + "repositories { maven { url = '"
            + repository.url()
            + "' } }\n"
            + RESOLVE_BUILD);

    BuildResult result = runner(HttpsRepository.HOST, "resolve").build();

    assertTrue(result.getOutput().contains("Resolved [it-library-1.0.jar]"), result.getOutput());
    assertTrue(repository.receivedAuthorizations().contains(cliCredential()), result.getOutput());
  }

  @Test
  void authenticatesAPublishRepository() throws Exception {
    writeSettings(applyPlugin() + "rootProject.name = 'it-project'\n");
    writeBuild(
        """
        plugins {
          id 'java'
          id 'maven-publish'
        }
        group = 'com.example'
        version = '1.0'
        publishing {
          publications { library(MavenPublication) { from components.java } }
          repositories { maven { name = 'cloudsmith'; url = '%s' } }
        }
        """
            .formatted(repository.url()));

    BuildResult result = runner(HttpsRepository.HOST, "publish").build();

    assertNotNull(
        repository.file("com/example/it-project/1.0/it-project-1.0.pom"), result.getOutput());
    assertTrue(repository.receivedAuthorizations().contains(cliCredential()), result.getOutput());
  }

  @Test
  void sendsNoCredentialToAHostTheCliDoesNotList() throws Exception {
    writeSettings(applyPlugin() + dependencyRepository(""));
    writeBuild(RESOLVE_BUILD);

    BuildResult result = runner("dl.cloudsmith.io", "resolve").buildAndFail();

    assertTrue(result.getOutput().contains("401"), result.getOutput());
    assertEquals(List.of(), repository.receivedAuthorizations(), result.getOutput());
  }

  @Test
  void leavesAnExplicitCredentialInPlace() throws Exception {
    writeSettings(
        applyPlugin()
            + dependencyRepository(
                "credentials { username = 'explicit-user'; password = 'explicit-secret' }"));
    writeBuild(RESOLVE_BUILD);

    BuildResult result = runner(HttpsRepository.HOST, "resolve").build();

    assertTrue(
        repository.receivedAuthorizations().contains(basic("explicit-user", "explicit-secret")),
        result.getOutput());
    assertFalse(repository.receivedAuthorizations().contains(cliCredential()), result.getOutput());
  }

  @Test
  void warnsWithTheErrorOfTheCliWhenTheCliFails() throws Exception {
    Path cli = FakeCloudsmithCli.writeBehindProxy(proxyDirectory(), HttpsRepository.HOST);
    writeSettings(applyPlugin() + dependencyRepository(""));
    writeBuild(RESOLVE_BUILD);

    BuildResult result =
        runner(Map.of("CLOUDSMITH_CLI_PATH", cli.toString()), HttpsRepository.HOST, "resolve")
            .buildAndFail();

    assertTrue(
        result.getOutput().contains("`cloudsmith domains list` exited with status 1"),
        result.getOutput());
    assertTrue(result.getOutput().contains(FakeCloudsmithCli.PROXY_HINT), result.getOutput());
  }

  @Test
  void asksTheCliAgainWhenItsTlsSettingsChange() throws Exception {
    Path cli = FakeCloudsmithCli.writeBehindProxy(proxyDirectory(), HttpsRepository.HOST);
    String caBundle = workDirectory.resolve("proxy-ca.pem").toString();
    writeSettings(applyPlugin() + dependencyRepository(""));
    writeBuild(RESOLVE_BUILD);

    runner(Map.of("CLOUDSMITH_CLI_PATH", cli.toString()), HttpsRepository.HOST, "resolve")
        .buildAndFail();
    BuildResult fixed =
        runner(
                Map.of("CLOUDSMITH_CLI_PATH", cli.toString(), "REQUESTS_CA_BUNDLE", caBundle),
                HttpsRepository.HOST,
                "resolve")
            .build();

    assertTrue(fixed.getOutput().contains("Resolved [it-library-1.0.jar]"), fixed.getOutput());
  }

  @Test
  void reusesTheConfigurationCache() throws Exception {
    writeSettings(applyPlugin() + dependencyRepository(""));
    writeBuild(RESOLVE_BUILD);

    BuildResult first = runner(HttpsRepository.HOST, "resolve", "--configuration-cache").build();
    BuildResult second = runner(HttpsRepository.HOST, "resolve", "--configuration-cache").build();

    assertTrue(first.getOutput().contains("Resolved [it-library-1.0.jar]"), first.getOutput());
    assertTrue(second.getOutput().contains("Reusing configuration cache"), second.getOutput());
    assertTrue(second.getOutput().contains("Resolved [it-library-1.0.jar]"), second.getOutput());
  }

  @Test
  void sendsTheRenewedCredentialToARepositoryDeclaredInSettings() throws Exception {
    writeSettings(applyPlugin() + dependencyRepository(""));

    BuildResult result = resolveWhileTheCliRenewsTheCredential("", "configurations.after");

    assertTrue(
        result.getOutput().contains("Resolved [it-other-1.0.jar] after renewal"),
        result.getOutput());
  }

  @Test
  void sendsTheRenewedCredentialToAProjectRepository() throws Exception {
    writeSettings("");

    BuildResult result =
        resolveWhileTheCliRenewsTheCredential(
            applyPlugin() + "repositories { maven { url = '" + repository.url() + "' } }\n",
            "configurations.after");

    assertTrue(
        result.getOutput().contains("Resolved [it-other-1.0.jar] after renewal"),
        result.getOutput());
  }

  @Test
  void sendsTheRenewedCredentialForADetachedConfiguration() throws Exception {
    assumeTrue(
        gradleUnderTestIsAtLeast("8.1"),
        "Gradle before 8.1 has no build listener that works with the configuration cache");
    writeSettings(applyPlugin() + dependencyRepository(""));

    BuildResult result =
        resolveWhileTheCliRenewsTheCredential(
            "",
            "configurations.detachedConfiguration(dependencies.create('com.example:it-other:1.0'))");

    assertTrue(
        result.getOutput().contains("Resolved [it-other-1.0.jar] after renewal"),
        result.getOutput());
  }

  @Test
  void sendsTheRenewedCredentialForADetachedConfigurationOfASubproject() throws Exception {
    assumeTrue(
        gradleUnderTestIsAtLeast("8.1"),
        "Gradle before 8.1 has no build listener that works with the configuration cache");
    writeSettings(applyPlugin() + "include 'sub'\n");
    Path subproject = Files.createDirectories(project.resolve("sub"));

    BuildResult result =
        resolveWhileTheCliRenewsTheCredential(
            subproject,
            "repositories { maven { url = '" + repository.url() + "' } }\n",
            "configurations.detachedConfiguration(dependencies.create('com.example:it-other:1.0'))");

    assertTrue(
        result.getOutput().contains("Resolved [it-other-1.0.jar] after renewal"),
        result.getOutput());
  }

  @Test
  void keepsACredentialThatTheBuildSetsLater() throws Exception {
    writeSettings(
        applyPlugin()
            + dependencyRepository("name = 'cloudsmith'")
            + """
            dependencyResolutionManagement.repositories.named('cloudsmith') {
              credentials { username = 'explicit-user'; password = 'explicit-secret' }
            }
            """);

    BuildResult result = buildWhileTheCliRenewsTheCredential(project, "", "configurations.after");

    assertEquals(
        Set.of(basic("explicit-user", "explicit-secret")),
        Set.copyOf(repository.receivedAuthorizations()),
        result.getOutput());
  }

  /**
   * Gradle discards the internal descriptor of a repository each time a plugin reads or sets its
   * credentials. Another worker of a parallel build can read the descriptor at that moment.
   */
  @Test
  void leavesARepositoryUnchangedWhenItRenewsTheCredential() throws Exception {
    Path passwordFile = workDirectory.resolve("password");
    Files.writeString(passwordFile, "first-secret");
    Path cli =
        FakeCloudsmithCli.writeRenewing(
            Files.createDirectories(workDirectory.resolve("renewing")),
            HttpsRepository.HOST,
            passwordFile);
    writeSettings("");
    writeBuild(
        applyPlugin()
            + "repositories { maven { url = '"
            + repository.url()
            + "' } }\n"
            + """
            configurations { fetched }
            dependencies { fetched 'com.example:it-library:1.0' }
            tasks.register('resolve') {
              def files = configurations.fetched
              def repository = repositories.first()
              def passwordFile = file('%s')
              doLast {
                def descriptor = repository.descriptor
                passwordFile.text = 'renewed-secret'
                println "Resolved ${files.files*.name}"
                println "Repository unchanged: ${repository.descriptor.is(descriptor)}"
              }
            }
            """
                .formatted(passwordFile.toString().replace("\\", "/")));

    BuildResult result =
        runner(
                Map.of(
                    "CLOUDSMITH_CLI_PATH", cli.toString(), "CLOUDSMITH_MAVEN_TOOLS_TTL", "0.001"),
                HttpsRepository.HOST,
                "resolve")
            .build();

    assertTrue(result.getOutput().contains("Repository unchanged: true"), result.getOutput());
    assertTrue(
        repository
            .receivedAuthorizations()
            .contains(basic(FakeCloudsmithCli.USERNAME, "renewed-secret")),
        result.getOutput());
  }

  @Test
  void asksTheCliAgainWhenTheBuildRefreshesDependencies() throws Exception {
    writeSettings(applyPlugin() + dependencyRepository(""));
    writeBuild(RESOLVE_BUILD);

    runner("dl.cloudsmith.io", "resolve").buildAndFail();
    BuildResult refreshed =
        runner(HttpsRepository.HOST, "resolve", "--refresh-dependencies").build();

    assertTrue(
        refreshed.getOutput().contains("Resolved [it-library-1.0.jar]"), refreshed.getOutput());
  }

  @Test
  void sendsNoRenewedCredentialToARepositoryWhoseUrlChanged() throws Exception {
    Path passwordFile = workDirectory.resolve("password");
    Files.writeString(passwordFile, "first-secret");
    Path cli =
        FakeCloudsmithCli.writeRenewing(
            Files.createDirectories(workDirectory.resolve("renewing")),
            HttpsRepository.HOST,
            passwordFile);
    writeSettings("");
    writeBuild(
        applyPlugin()
            + "repositories { maven { url = '"
            + repository.url()
            + "' } }\n"
            + """
            repositories.first().url = 'https://other.example/repository'
            configurations { unused }
            tasks.register('resolve') {
              def files = configurations.unused
              def repository = repositories.first()
              def passwordFile = file('%s')
              doLast {
                passwordFile.text = 'renewed-secret'
                files.files
                println "Password: ${repository.credentials.password}"
              }
            }
            """
                .formatted(passwordFile.toString().replace("\\", "/")));

    BuildResult result =
        runner(
                Map.of(
                    "CLOUDSMITH_CLI_PATH", cli.toString(), "CLOUDSMITH_MAVEN_TOOLS_TTL", "0.001"),
                HttpsRepository.HOST,
                "resolve")
            .build();

    assertTrue(result.getOutput().contains("Password: first-secret"), result.getOutput());
  }

  /**
   * Gradle records each value source that a build reads at configuration time, and reads each of
   * them again before it reuses the configuration cache.
   */
  @Test
  void addsNoBuildInputForACheckThatRenewsNothing() throws Exception {
    writeSettings(applyPlugin() + dependencyRepository(""));

    long few = configurationCacheFingerprintSize(2);
    long many = configurationCacheFingerprintSize(300);

    assertTrue(few > 0, "no configuration cache fingerprint found");
    assertTrue(many - few < 2_000, "fingerprint grew from " + few + " to " + many + " bytes");
  }

  private long configurationCacheFingerprintSize(int resolutions) throws IOException {
    writeBuild(
        """
        (1..%d).each { index ->
          def fetched = configurations.create("fetched$index")
          dependencies.add(fetched.name, 'com.example:it-library:1.0')
          fetched.files
        }
        tasks.register('resolve')
        """
            .formatted(resolutions));
    Path cache = project.resolve(".gradle/configuration-cache");
    if (Files.exists(cache)) {
      try (Stream<Path> stored = Files.walk(cache)) {
        stored.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
      }
    }
    runner(HttpsRepository.HOST, "resolve", "--configuration-cache").build();
    try (Stream<Path> stored = Files.walk(cache)) {
      return stored
          .filter(file -> file.getFileName().toString().contains("fingerprint"))
          .mapToLong(file -> file.toFile().length())
          .sum();
    }
  }

  /**
   * Resolves one configuration, renews the CLI credential, and resolves {@code afterRenewal}. The
   * repository accepts only the current credential, like Cloudsmith after a token expires.
   */
  private BuildResult resolveWhileTheCliRenewsTheCredential(String buildPrefix, String afterRenewal)
      throws Exception {
    return resolveWhileTheCliRenewsTheCredential(project, buildPrefix, afterRenewal);
  }

  private BuildResult resolveWhileTheCliRenewsTheCredential(
      Path buildDirectory, String buildPrefix, String afterRenewal) throws Exception {
    Path passwordFile = workDirectory.resolve("password");
    repository.acceptOnly(() -> basic(FakeCloudsmithCli.USERNAME, readPassword(passwordFile)));

    BuildResult result =
        buildWhileTheCliRenewsTheCredential(buildDirectory, buildPrefix, afterRenewal);

    assertTrue(
        repository
            .receivedAuthorizations()
            .contains(basic(FakeCloudsmithCli.USERNAME, "renewed-secret")),
        result.getOutput());
    return result;
  }

  private BuildResult buildWhileTheCliRenewsTheCredential(
      Path buildDirectory, String buildPrefix, String afterRenewal) throws Exception {
    String other = "com/example/it-other/1.0/it-other-1.0";
    repository.addFile(other + ".pom", LIBRARY_POM.replace("it-library", "it-other"));
    repository.addFile(other + ".jar", "not a real jar");
    Path passwordFile = workDirectory.resolve("password");
    Files.writeString(passwordFile, "first-secret");
    Path cli =
        FakeCloudsmithCli.writeRenewing(
            Files.createDirectories(workDirectory.resolve("renewing")),
            HttpsRepository.HOST,
            passwordFile);
    Files.writeString(
        buildDirectory.resolve("build.gradle"),
        buildPrefix
            + """
            configurations { before; after }
            dependencies {
              before 'com.example:it-library:1.0'
              after 'com.example:it-other:1.0'
            }
            tasks.register('resolveAcrossRenewal') {
              def beforeFiles = configurations.before
              def afterFiles = %s
              def passwordFile = file('%s')
              doLast {
                println "Resolved ${beforeFiles.files*.name}"
                passwordFile.text = 'renewed-secret'
                println "Resolved ${afterFiles.files*.name} after renewal"
              }
            }
            """
                .formatted(afterRenewal, passwordFile.toString().replace("\\", "/")));

    return runner(
            Map.of("CLOUDSMITH_CLI_PATH", cli.toString(), "CLOUDSMITH_MAVEN_TOOLS_TTL", "0.001"),
            HttpsRepository.HOST,
            "resolveAcrossRenewal")
        .build();
  }

  private boolean gradleUnderTestIsAtLeast(String version) throws IOException {
    String output = runner(HttpsRepository.HOST, "--version").build().getOutput();
    Matcher gradle = Pattern.compile("(?m)^Gradle (\\S+)").matcher(output);
    assertTrue(gradle.find(), output);
    return GradleVersion.version(gradle.group(1))
            .getBaseVersion()
            .compareTo(GradleVersion.version(version))
        >= 0;
  }

  private static String readPassword(Path passwordFile) {
    try {
      return Files.readString(passwordFile).strip();
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  private String applyPlugin() {
    String jar = System.getProperty("it.plugin.jar").replace("\\", "/");
    return """
    buildscript { dependencies { classpath files('%s') } }
    apply plugin: 'io.cloudsmith.gradle-plugin'
    """
        .formatted(jar);
  }

  private String dependencyRepository(String configuration) {
    return """
    dependencyResolutionManagement {
      repositories { maven { url = '%s'; %s } }
    }
    """
        .formatted(repository.url(), configuration);
  }

  private void writeSettings(String content) throws IOException {
    Files.writeString(project.resolve("settings.gradle"), content);
  }

  private void writeBuild(String content) throws IOException {
    Files.writeString(project.resolve("build.gradle"), content);
  }

  private Path proxyDirectory() throws IOException {
    return Files.createDirectories(workDirectory.resolve("proxy"));
  }

  private GradleRunner runner(String listedHost, String... arguments) throws IOException {
    return runner(Map.of(), listedHost, arguments);
  }

  private GradleRunner runner(
      Map<String, String> cliEnvironment, String listedHost, String... arguments)
      throws IOException {
    Path fakeCli = FakeCloudsmithCli.write(workDirectory, listedHost);
    List<String> properties = new ArrayList<>();
    properties.add("org.gradle.jvmargs=" + repository.trustStoreOptions());
    String javaHome = System.getProperty("it.java.home");
    if (javaHome != null) {
      properties.add("org.gradle.java.home=" + javaHome);
    }
    Files.write(project.resolve("gradle.properties"), properties);

    Map<String, String> environment = new HashMap<>();
    environment.put("PATH", System.getenv("PATH"));
    environment.put("HOME", workDirectory.toString());
    environment.put("CLOUDSMITH_CLI_PATH", fakeCli.toString());
    environment.putAll(cliEnvironment);

    List<String> allArguments = new ArrayList<>(List.of(arguments));
    allArguments.addAll(List.of("--stacktrace", "--warning-mode", "fail"));
    GradleRunner runner =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withEnvironment(environment)
            .withArguments(allArguments);
    String gradleHome = System.getProperty("it.gradle.home");
    return gradleHome == null ? runner : runner.withGradleInstallation(new File(gradleHome));
  }

  private static String cliCredential() {
    return basic(FakeCloudsmithCli.USERNAME, FakeCloudsmithCli.PASSWORD);
  }
}
