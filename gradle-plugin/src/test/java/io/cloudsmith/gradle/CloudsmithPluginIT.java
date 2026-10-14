package io.cloudsmith.gradle;

import static io.cloudsmith.testing.HttpsRepository.basic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cloudsmith.testing.FakeCloudsmithCli;
import io.cloudsmith.testing.HttpsRepository;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
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
  void reusesTheConfigurationCache() throws Exception {
    writeSettings(applyPlugin() + dependencyRepository(""));
    writeBuild(RESOLVE_BUILD);

    BuildResult first = runner(HttpsRepository.HOST, "resolve", "--configuration-cache").build();
    BuildResult second = runner(HttpsRepository.HOST, "resolve", "--configuration-cache").build();

    assertTrue(first.getOutput().contains("Resolved [it-library-1.0.jar]"), first.getOutput());
    assertTrue(second.getOutput().contains("Reusing configuration cache"), second.getOutput());
    assertTrue(second.getOutput().contains("Resolved [it-library-1.0.jar]"), second.getOutput());
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

  private GradleRunner runner(String listedHost, String... arguments) throws IOException {
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
