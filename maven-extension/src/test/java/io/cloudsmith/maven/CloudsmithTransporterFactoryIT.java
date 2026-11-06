package io.cloudsmith.maven;

import static io.cloudsmith.testing.HttpsRepository.basic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cloudsmith.testing.FakeCloudsmithCli;
import io.cloudsmith.testing.HttpsRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs a real Maven build with the packaged extension loaded from {@code .mvn/extensions.xml}.
 *
 * <p>The build resolves its parent POM from a local HTTPS server that demands Basic auth. A fake
 * {@code cloudsmith} script stands in for the CLI, so the test needs no network and no Cloudsmith
 * account. The Maven under test is the one running this build, so a CI matrix over Maven versions
 * covers each of them.
 */
@DisabledOnOs(OS.WINDOWS)
class CloudsmithTransporterFactoryIT {
  private static final String ARTIFACT_ID = "cloudsmith-maven-extension";
  private static final String PARENT_POM_PATH = "com/example/it-parent/1.0/it-parent-1.0.pom";
  private static final String PARENT_POM =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>it-parent</artifactId>
        <version>1.0</version>
        <packaging>pom</packaging>
      </project>
      """;

  @TempDir Path workDirectory;

  private HttpsRepository repository;

  @BeforeEach
  void startServer() throws Exception {
    repository = new HttpsRepository(workDirectory);
    repository.addFile(PARENT_POM_PATH, PARENT_POM);
  }

  @AfterEach
  void stopServer() {
    repository.close();
  }

  @Test
  void authenticatesARepositoryOnAHostTheCliLists() throws Exception {
    BuildResult result = runMaven(cliListing(HttpsRepository.HOST), writeSettings(""), "validate");

    assertEquals(0, result.exitCode, result.output);
    assertTrue(repository.receivedAuthorizations().contains(cliCredential()), result.output);
  }

  @Test
  void sendsNoCredentialToAHostTheCliDoesNotList() throws Exception {
    BuildResult result = runMaven(cliListing("dl.cloudsmith.io"), writeSettings(""), "validate");

    assertNotEquals(0, result.exitCode, result.output);
    assertTrue(result.output.contains("401"), result.output);
    assertEquals(List.of(), repository.receivedAuthorizations(), result.output);
  }

  @Test
  void leavesACredentialFromSettingsXmlInPlace() throws Exception {
    String servers =
        "<servers><server><id>it-mirror</id><username>explicit-user</username>"
            + "<password>explicit-secret</password></server></servers>";

    BuildResult result =
        runMaven(cliListing(HttpsRepository.HOST), writeSettings(servers), "validate");

    assertEquals(0, result.exitCode, result.output);
    assertTrue(
        repository.receivedAuthorizations().contains(basic("explicit-user", "explicit-secret")),
        result.output);
    assertFalse(repository.receivedAuthorizations().contains(cliCredential()), result.output);
  }

  @Test
  void warnsWithTheErrorOfTheCliWhenTheCliFails() throws Exception {
    Path cli = FakeCloudsmithCli.writeBehindProxy(proxyDirectory(), HttpsRepository.HOST);

    BuildResult result = runMaven(cli, writeSettings(""), "validate");

    assertNotEquals(0, result.exitCode, result.output);
    assertTrue(
        result.output.contains("[WARNING] cloudsmith domains list exited with status 1"),
        result.output);
    assertTrue(result.output.contains(FakeCloudsmithCli.PROXY_HINT), result.output);
  }

  private Path proxyDirectory() throws IOException {
    return Files.createDirectories(workDirectory.resolve("proxy"));
  }

  private Path cliListing(String host) throws IOException {
    return FakeCloudsmithCli.write(workDirectory, host);
  }

  private static String cliCredential() {
    return basic(FakeCloudsmithCli.USERNAME, FakeCloudsmithCli.PASSWORD);
  }

  private BuildResult runMaven(Path fakeCli, Path settings, String... goals) throws Exception {
    Path project = writeProject();
    Path localRepository = installExtension(workDirectory.resolve("local-repository"));
    Path mavenHome = Paths.get(System.getProperty("it.maven.home"));

    List<String> command = new ArrayList<>();
    command.add(mavenHome.resolve("bin").resolve("mvn").toString());
    command.addAll(
        List.of("-B", "-e", "-s", settings.toString(), "-Dmaven.repo.local=" + localRepository));
    command.addAll(List.of(goals));

    Path output = workDirectory.resolve("maven-output.txt");
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(project.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile());
    Map<String, String> environment = builder.environment();
    environment
        .keySet()
        .removeIf(name -> name.startsWith("CLOUDSMITH_") || name.startsWith("MAVEN_"));
    environment.remove("REQUESTS_CA_BUNDLE");
    environment.put(
        "JAVA_HOME", System.getProperty("it.java.home", System.getProperty("java.home")));
    environment.put("CLOUDSMITH_CLI_PATH", fakeCli.toString());
    environment.put("MAVEN_OPTS", repository.trustStoreOptions());

    Process process = builder.start();
    if (!process.waitFor(5, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new AssertionError("Maven did not finish within five minutes");
    }
    return new BuildResult(process.exitValue(), Files.readString(output));
  }

  private Path writeProject() throws IOException {
    Path project = Files.createDirectories(workDirectory.resolve("project"));
    Files.writeString(
        project.resolve("pom.xml"),
        """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>it-parent</artifactId>
            <version>1.0</version>
            <relativePath/>
          </parent>
          <artifactId>it-project</artifactId>
          <packaging>pom</packaging>
        </project>
        """);

    Path mavenDirectory = Files.createDirectories(project.resolve(".mvn"));
    Files.writeString(
        mavenDirectory.resolve("extensions.xml"),
        """
        <extensions>
          <extension>
            <groupId>io.cloudsmith</groupId>
            <artifactId>%s</artifactId>
            <version>%s</version>
          </extension>
        </extensions>
        """
            .formatted(ARTIFACT_ID, System.getProperty("it.extension.version")));
    return project;
  }

  private Path installExtension(Path localRepository) throws IOException {
    String version = System.getProperty("it.extension.version");
    Path artifactDirectory =
        Files.createDirectories(
            localRepository.resolve("io/cloudsmith").resolve(ARTIFACT_ID).resolve(version));
    String baseName = ARTIFACT_ID + "-" + version;
    Files.copy(
        Paths.get(System.getProperty("it.extension.jar")),
        artifactDirectory.resolve(baseName + ".jar"),
        StandardCopyOption.REPLACE_EXISTING);
    Files.copy(
        Paths.get(System.getProperty("it.extension.pom")),
        artifactDirectory.resolve(baseName + ".pom"),
        StandardCopyOption.REPLACE_EXISTING);
    return localRepository;
  }

  /** Mirrors every repository to the server, so Maven never reaches Maven Central. */
  private Path writeSettings(String servers) throws IOException {
    Path settings = workDirectory.resolve("settings.xml");
    Files.writeString(
        settings,
        "<settings><mirrors><mirror><id>it-mirror</id><mirrorOf>*</mirrorOf><url>"
            + repository.url()
            + "</url></mirror></mirrors>"
            + servers
            + "</settings>");
    return settings;
  }

  private record BuildResult(int exitCode, String output) {}
}
