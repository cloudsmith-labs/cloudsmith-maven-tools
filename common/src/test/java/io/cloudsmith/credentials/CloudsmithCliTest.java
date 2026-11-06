package io.cloudsmith.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CloudsmithCliTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final List<List<String>> invocations = new ArrayList<>();

  private CloudsmithCli cliAnswering(String stdout) {
    return new CloudsmithCli(
        arguments -> {
          invocations.add(arguments);
          return stdout;
        });
  }

  @Nested
  class CredentialHelper {
    @Test
    void returnsTheUsernameAndPassword() {
      var cli = cliAnswering("{\"version\": 1, \"username\": \"token\", \"password\": \"secret\"}");

      assertEquals(new Credential("token", "secret"), cli.credential());
      assertEquals(List.of(List.of("credential-helper", "generic")), invocations);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "{\"version\": 2, \"username\": \"token\", \"password\": \"secret\"}",
          "{\"username\": \"token\", \"password\": \"secret\"}",
          "{\"version\": 1, \"username\": \"\", \"password\": \"secret\"}",
          "{\"version\": 1, \"username\": \"token\"}",
          "{\"version\": 1, \"username\": \"token\", \"password\": 7}",
          "[]",
          "not json",
          "   "
        })
    void rejectsAnUnusableResponse(String stdout) {
      assertNull(cliAnswering(stdout).credential());
    }

    @Test
    void returnsNullWhenTheCommandFails() {
      assertNull(cliAnswering(null).credential());
    }
  }

  @Nested
  class DomainsList {
    @Test
    void keepsMavenNativeApiAndDownloadHostsFromTheRecordedContract() throws IOException {
      var cli = cliAnswering(fixture("domains_list_v1.json"));

      assertEquals(Set.of("dl.cloudsmith.io", "maven.cloudsmith.io"), cli.mavenHosts());
      assertEquals(List.of(List.of("domains", "list")), invocations);
    }

    @Test
    void lowercasesHostsAndSkipsUnusableEntries() {
      String stdout =
          """
          {"version": 1, "domains": [
            {"host": "Maven.Example.COM", "format": "maven", "domain_type": "native_api"},
            {"host": "dl.example.com", "format": null, "domain_type": "download"},
            {"host": "npm.example.com", "format": "npm", "domain_type": "native_api"},
            {"host": "upload.example.com", "format": null, "domain_type": "upload"},
            {"host": "", "domain_type": "download"},
            {"domain_type": "download"},
            "not an object"]}
          """;

      assertEquals(
          Set.of("maven.example.com", "dl.example.com"), cliAnswering(stdout).mavenHosts());
    }

    @Test
    void treatsAnUnsupportedVersionAsAnAnswerWithNoHosts() {
      String stdout =
          "{\"version\": 2, \"domains\": [{\"host\": \"dl.cloudsmith.io\", \"domain_type\":"
              + " \"download\"}]}";

      assertEquals(Set.of(), cliAnswering(stdout).mavenHosts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"version\": 1}", "{\"version\": 1, \"domains\": {}}", "not json"})
    void returnsNullForAMalformedResponse(String stdout) {
      assertNull(cliAnswering(stdout).mavenHosts());
    }

    @Test
    void returnsNullWhenTheCommandFails() {
      assertNull(cliAnswering(null).mavenHosts());
    }

    private static String fixture(String name) throws IOException {
      try (InputStream stream = CloudsmithCliTest.class.getResourceAsStream("/fixtures/" + name)) {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
  }

  @Nested
  @DisabledOnOs(OS.WINDOWS)
  class FindBinary {
    @TempDir Path directory;

    @Test
    void findsTheFirstExecutableCloudsmithOnPath() throws IOException {
      Path empty = Files.createDirectory(directory.resolve("empty"));
      Path first = executable(directory.resolve("first"), "cloudsmith");
      Path second = executable(directory.resolve("second"), "cloudsmith");

      assertEquals(first, find(Map.of("PATH", join(empty, first.getParent(), second.getParent()))));
    }

    @Test
    void skipsAFileThatIsNotExecutable() throws IOException {
      Path plain = Files.createDirectories(directory.resolve("plain")).resolve("cloudsmith");
      Files.createFile(plain);
      Path executable = executable(directory.resolve("bin"), "cloudsmith");

      assertEquals(
          executable, find(Map.of("PATH", join(plain.getParent(), executable.getParent()))));
    }

    @Test
    void ignoresRelativePathEntriesSoACheckoutCannotPlantTheBinary() throws IOException {
      executable(directory.resolve("bin"), "cloudsmith");

      assertNull(find(Map.of("PATH", String.join(File.pathSeparator, "", ".", "bin", "./bin"))));
    }

    @Test
    void looksForTheExeNameOnWindows() throws IOException {
      executable(directory.resolve("bin"), "cloudsmith");
      Path windowsBinary = executable(directory.resolve("bin"), "cloudsmith.exe");

      assertEquals(
          windowsBinary,
          CloudsmithCli.findBinary(Map.of("PATH", windowsBinary.getParent().toString()), true));
    }

    @Test
    void usesAnExplicitCliPathInsteadOfSearchingPath() throws IOException {
      Path explicit = executable(directory.resolve("explicit"), "my-cloudsmith");
      Path onPath = executable(directory.resolve("bin"), "cloudsmith");

      assertEquals(
          explicit,
          find(
              Map.of(
                  "CLOUDSMITH_CLI_PATH",
                  explicit.toString(),
                  "PATH",
                  onPath.getParent().toString())));
    }

    @Test
    void returnsNullWhenTheExplicitCliPathIsNotExecutable() throws IOException {
      Path onPath = executable(directory.resolve("bin"), "cloudsmith");
      String missing = directory.resolve("missing").toString();

      assertNull(
          find(Map.of("CLOUDSMITH_CLI_PATH", missing, "PATH", onPath.getParent().toString())));
    }

    @Test
    void returnsNullWhenPathIsUnset() {
      assertNull(find(Map.of()));
    }

    @Test
    void warnsWhenTheCliIsNotOnPath() {
      List<String> warnings = new ArrayList<>();

      assertNull(Warnings.to(warnings::add, () -> CloudsmithCli.locate(Map.of(), false, TIMEOUT)));

      assertEquals(
          List.of(
              "The cloudsmith CLI is not on PATH, so no repository gets the CLI credential."
                  + " Install the CLI, or set CLOUDSMITH_CLI_PATH."),
          warnings);
    }

    @Test
    void warnsWhenTheExplicitCliPathIsNotExecutable() {
      String missing = directory.resolve("missing").toString();
      List<String> warnings = new ArrayList<>();

      Warnings.to(
          warnings::add,
          () -> CloudsmithCli.locate(Map.of("CLOUDSMITH_CLI_PATH", missing), false, TIMEOUT));

      assertEquals(
          List.of(
              "CLOUDSMITH_CLI_PATH is "
                  + missing
                  + ", which is not an executable file, so no repository gets the CLI"
                  + " credential."),
          warnings);
    }

    private static Path find(Map<String, String> environment) {
      return CloudsmithCli.findBinary(environment, false);
    }

    private static String join(Path... directories) {
      List<String> entries = new ArrayList<>();
      for (Path entry : directories) {
        entries.add(entry.toString());
      }
      return String.join(File.pathSeparator, entries);
    }
  }

  @Nested
  @DisabledOnOs(OS.WINDOWS)
  class Run {
    @TempDir Path directory;

    @Test
    void returnsStdoutAndPassesTheArguments() throws IOException {
      Path cli = script("printf '%s|%s' \"$1\" \"$2\"");

      assertEquals("domains|list", CloudsmithCli.run(cli, List.of("domains", "list"), TIMEOUT));
    }

    @Test
    void ignoresStderrWhenTheCommandSucceeds() throws IOException {
      assertEquals("ok", run("echo warning >&2; printf ok"));
    }

    @Test
    void readsALargeStderrWithoutBlocking() throws IOException {
      Path cli = script("head -c 200000 /dev/zero >&2; exit 1");

      assertTimeoutPreemptively(
          Duration.ofSeconds(10), () -> assertNull(CloudsmithCli.run(cli, List.of(), TIMEOUT)));
    }

    @Test
    void givesTheCommandAnEmptyStdin() throws IOException {
      assertEquals("read:", run("read line; printf 'read:%s' \"$line\""));
    }

    @Test
    void returnsNullForANonZeroExit() throws IOException {
      assertNull(run("printf partial; exit 3"));
    }

    @Test
    void warnsWithTheStderrOfAFailedCommand() throws IOException {
      Path cli = script("echo 'Hint: set REQUESTS_CA_BUNDLE.' >&2; exit 3");
      List<String> warnings = new ArrayList<>();

      Warnings.to(warnings::add, () -> CloudsmithCli.run(cli, List.of("domains", "list"), TIMEOUT));

      assertEquals(
          List.of(
              "cloudsmith domains list exited with status 3, so no repository gets the CLI"
                  + " credential. The CLI printed:"
                  + System.lineSeparator()
                  + "Hint: set REQUESTS_CA_BUNDLE."),
          warnings);
    }

    @Test
    void returnsNullAndStopsTheCommandWhenItTimesOut() throws IOException {
      Path cli = script("sleep 30");

      assertTimeoutPreemptively(
          Duration.ofSeconds(10),
          () -> assertNull(CloudsmithCli.run(cli, List.of(), Duration.ofMillis(200))));
    }

    @Test
    void returnsNullWhenTheBinaryCannotBeExecuted() {
      assertNull(CloudsmithCli.run(directory.resolve("missing"), List.of(), TIMEOUT));
    }

    private String run(String body) throws IOException {
      return CloudsmithCli.run(script(body), List.of(), TIMEOUT);
    }

    private Path script(String body) throws IOException {
      return Files.writeString(executable(directory, "cloudsmith"), "#!/bin/sh\n" + body + "\n");
    }
  }

  private static Path executable(Path parent, String name) throws IOException {
    Path file = Files.createDirectories(parent).resolve(name);
    Files.writeString(file, "");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    return file;
  }
}
