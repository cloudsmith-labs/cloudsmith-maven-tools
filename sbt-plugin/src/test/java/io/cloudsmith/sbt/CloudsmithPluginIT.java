package io.cloudsmith.sbt;

import static io.cloudsmith.testing.HttpsRepository.basic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cloudsmith.testing.FakeCloudsmithCli;
import io.cloudsmith.testing.HttpsRepository;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Runs real sbt builds with the packaged plugin against a local HTTPS repository.
 *
 * <p>A fake {@code cloudsmith} script stands in for the CLI. The sbt under test is {@code
 * it.sbt.version}, which the sbt launcher on {@code PATH} downloads, so a CI matrix over sbt
 * versions covers each of them.
 */
@DisabledOnOs(OS.WINDOWS)
class CloudsmithPluginIT {
  private static final String LIBRARY = "com/example/it-library/1.0/it-library-1.0";
  private static final String JAVA_ONLY = "autoScalaLibrary := false\ncrossPaths := false\n";

  @TempDir Path workDirectory;

  private HttpsRepository repository;
  private Path project;

  @BeforeEach
  void startServer() throws Exception {
    repository = new HttpsRepository(workDirectory);
    repository.addFile(
        LIBRARY + ".pom",
        """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>it-library</artifactId>
          <version>1.0</version>
        </project>
        """);
    repository.addFile(LIBRARY + ".jar", "not a real jar");
    project = Files.createDirectories(workDirectory.resolve("project"));
  }

  @AfterEach
  void stopServer() {
    repository.close();
  }

  @Test
  void authenticatesAResolverOnAHostTheCliLists() throws Exception {
    BuildResult result = runSbt(HttpsRepository.HOST, resolverBuild(""), "update");

    assertEquals(0, result.exitCode(), result.output());
    assertTrue(repository.receivedAuthorizations().contains(cliCredential()), result.output());
  }

  @Test
  void authenticatesAPublishRepository() throws Exception {
    BuildResult result = runSbt(HttpsRepository.HOST, publishBuild(""), "publish");

    assertEquals(0, result.exitCode(), result.output());
    assertNotNull(
        repository.file("com/example/it-project/1.0/it-project-1.0.pom"), result.output());
    assertTrue(repository.receivedAuthorizations().contains(cliCredential()), result.output());
  }

  @Test
  void leavesAnExplicitPublishCredentialInPlace() throws Exception {
    String explicit = explicitCredential("Cloudsmith API");

    BuildResult result = runSbt(HttpsRepository.HOST, publishBuild(explicit), "publish");

    assertEquals(0, result.exitCode(), result.output());
    assertTrue(
        repository.receivedAuthorizations().contains(basic("explicit-user", "explicit-secret")),
        result.output());
    assertFalse(repository.receivedAuthorizations().contains(cliCredential()), result.output());
  }

  @Test
  void sendsNoCredentialToAHostTheCliDoesNotList() throws Exception {
    BuildResult result = runSbt("dl.cloudsmith.io", resolverBuild(""), "update");

    assertNotEquals(0, result.exitCode(), result.output());
    assertEquals(List.of(), repository.receivedAuthorizations(), result.output());
  }

  @Test
  void leavesAnExplicitResolverCredentialInPlace() throws Exception {
    String explicit = explicitCredential("Private Repository: it/it");

    BuildResult result = runSbt(HttpsRepository.HOST, resolverBuild(explicit), "update");

    assertEquals(0, result.exitCode(), result.output());
    assertTrue(
        repository.receivedAuthorizations().contains(basic("explicit-user", "explicit-secret")),
        result.output());
    assertFalse(repository.receivedAuthorizations().contains(cliCredential()), result.output());
  }

  @Test
  void warnsWithTheErrorOfTheCliWhenTheCliFails() throws Exception {
    Path proxy = Files.createDirectories(workDirectory.resolve("proxy"));
    Path cli = FakeCloudsmithCli.writeBehindProxy(proxy, HttpsRepository.HOST);

    BuildResult result = runSbt(cli, resolverBuild(""), "update");

    assertNotEquals(0, result.exitCode(), result.output());
    assertTrue(
        result.output().contains("`cloudsmith domains list` exited with status 1"),
        result.output());
    assertTrue(result.output().contains(FakeCloudsmithCli.PROXY_HINT), result.output());
  }

  private static String explicitCredential(String realm) {
    return "credentials += Credentials(\"%s\", \"%s\", \"explicit-user\", \"explicit-secret\")\n"
        .formatted(realm, HttpsRepository.HOST);
  }

  private String publishBuild(String extra) {
    return JAVA_ONLY
        + """
        organization := "com.example"
        name := "it-project"
        version := "1.0"
        publishMavenStyle := true
        publishTo := Some("it" at "%s")
        Compile / packageBin / publishArtifact := false
        Compile / packageSrc / publishArtifact := false
        Compile / packageDoc / publishArtifact := false
        """
            .formatted(repository.url())
        + extra;
  }

  private String resolverBuild(String extra) {
    return JAVA_ONLY
        + """
        resolvers += "it" at "%s"
        libraryDependencies += "com.example" %% "it-library" %% "1.0"
        """
            .formatted(repository.url())
        + extra;
  }

  private BuildResult runSbt(String listedHost, String build, String command) throws Exception {
    return runSbt(FakeCloudsmithCli.write(workDirectory, listedHost), build, command);
  }

  private BuildResult runSbt(Path cli, String build, String command) throws Exception {
    Path meta = Files.createDirectories(project.resolve("project"));
    Files.writeString(
        meta.resolve("build.properties"), "sbt.version=" + property("it.sbt.version"));
    // sbt 2 puts virtual file references on the classpath, not files.
    String jar = "file(\"%s\")".formatted(property("it.plugin.jar"));
    if (property("it.sbt.version").startsWith("2.")) {
      jar =
          "fileConverter.value.toVirtualFile(%s.toPath): xsbti.HashedVirtualFileRef".formatted(jar);
    }
    Files.writeString(
        meta.resolve("plugins.sbt"),
        "Compile / unmanagedJars += Attributed.blank(%s)\n".formatted(jar));
    Files.writeString(project.resolve("build.sbt"), build);

    Path output = workDirectory.resolve("sbt-output.txt");
    ProcessBuilder builder =
        // --server runs sbt itself, not a client of a background sbt server that outlives the test.
        new ProcessBuilder("sbt", "--server", "-batch", "-no-colors", command)
            .directory(project.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile());
    Map<String, String> environment = builder.environment();
    environment
        .keySet()
        .removeIf(name -> name.startsWith("CLOUDSMITH_") || name.startsWith("SBT_"));
    environment.remove("REQUESTS_CA_BUNDLE");
    environment.put(
        "JAVA_HOME", System.getProperty("it.java.home", System.getProperty("java.home")));
    environment.put("CLOUDSMITH_CLI_PATH", cli.toString());
    environment.put(
        "SBT_OPTS",
        repository.trustStoreOptions()
            + " -Dsbt.global.base="
            + workDirectory.resolve("sbt-global"));

    Process process = builder.start();
    if (!process.waitFor(10, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new AssertionError("sbt did not finish within ten minutes");
    }
    return new BuildResult(process.exitValue(), Files.readString(output));
  }

  private static String property(String name) {
    String value = System.getProperty(name);
    if (value == null) {
      throw new IllegalStateException(name + " is not set; run the tests with sbt");
    }
    return value;
  }

  private static String cliCredential() {
    return basic(FakeCloudsmithCli.USERNAME, FakeCloudsmithCli.PASSWORD);
  }

  private record BuildResult(int exitCode, String output) {}
}
