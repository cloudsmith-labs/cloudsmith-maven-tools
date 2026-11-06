package io.cloudsmith.credentials;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the {@code cloudsmith} CLI and reads the JSON contracts of the two commands that this
 * project uses.
 *
 * <p>Every failure returns {@code null}, so a missing, broken or unauthenticated CLI leaves the
 * build to behave as if the extension or plugin were absent. Each failure logs a warning, because
 * the build then gets 401 responses with no other clue to the cause.
 */
public final class CloudsmithCli {
  private static final Logger LOGGER = LoggerFactory.getLogger(CloudsmithCli.class);
  private static final Long CONTRACT_VERSION = 1L;
  private static final String NO_CREDENTIAL = "so no repository gets the CLI credential";

  /** Takes the CLI arguments and returns the stdout of the CLI, or {@code null} if it failed. */
  private final Function<List<String>, String> runner;

  public CloudsmithCli(Function<List<String>, String> runner) {
    this.runner = runner;
  }

  /** Returns a CLI that runs the {@code cloudsmith} binary, or {@code null} if there is none. */
  static CloudsmithCli locate(Map<String, String> environment, boolean windows, Duration timeout) {
    Path binary = findBinary(environment, windows);
    if (binary == null) {
      Warnings.warn(missingBinaryMessage(environment));
      return null;
    }
    return new CloudsmithCli(arguments -> run(binary, arguments, timeout));
  }

  private static String missingBinaryMessage(Map<String, String> environment) {
    String explicitPath = environment.get("CLOUDSMITH_CLI_PATH");
    if (explicitPath != null && !explicitPath.isEmpty()) {
      return "CLOUDSMITH_CLI_PATH is %s, which is not an executable file, %s."
          .formatted(explicitPath, NO_CREDENTIAL);
    }
    return "The cloudsmith CLI is not on PATH, %s. Install the CLI, or set CLOUDSMITH_CLI_PATH."
        .formatted(NO_CREDENTIAL);
  }

  /** Runs {@code credential-helper generic} and returns its credential, or {@code null}. */
  Credential credential() {
    Map<?, ?> payload = runForObject("credential-helper", "generic");
    if (payload == null || !CONTRACT_VERSION.equals(payload.get("version"))) {
      return null;
    }
    String username = nonEmptyString(payload.get("username"));
    String password = nonEmptyString(payload.get("password"));
    return username == null || password == null ? null : new Credential(username, password);
  }

  /**
   * Runs {@code domains list} and returns the hosts that serve Maven, or {@code null} on failure.
   *
   * <p>A host serves Maven if it is a download host, which serves every format, or the Maven native
   * API host that {@code mvn deploy} and Gradle's {@code publish} upload to. The CLI lists only
   * enabled, validated domains, and adds the custom domains of the workspace when it knows the
   * workspace. An unsupported contract version is an answer with no hosts, so an unknown response
   * shape is never guessed at.
   */
  Set<String> mavenHosts() {
    Map<?, ?> payload = runForObject("domains", "list");
    if (payload == null) {
      return null;
    }
    if (!CONTRACT_VERSION.equals(payload.get("version"))) {
      LOGGER.debug("cloudsmith domains list returned an unsupported contract version");
      return Set.of();
    }
    if (!(payload.get("domains") instanceof List<?> domains)) {
      return null;
    }
    Set<String> hosts = new HashSet<>();
    for (Object entry : domains) {
      if (entry instanceof Map<?, ?> domain && servesMaven(domain)) {
        String host = nonEmptyString(domain.get("host"));
        if (host != null) {
          hosts.add(host.toLowerCase(Locale.ROOT));
        }
      }
    }
    return hosts;
  }

  /**
   * Returns {@code CLOUDSMITH_CLI_PATH} if it is set, else the first {@code cloudsmith} on {@code
   * PATH}, else {@code null}.
   *
   * <p>Relative {@code PATH} entries are skipped. They resolve against the directory the build runs
   * in, which is often an untrusted checkout. A binary planted there would answer {@code domains
   * list} too, so it could declare any host to be Cloudsmith and then collect the credential for
   * it. On Windows only {@code cloudsmith.exe} is accepted, because a {@code .bat} or {@code .cmd}
   * file is the easiest file to plant.
   */
  static Path findBinary(Map<String, String> environment, boolean windows) {
    String explicitPath = environment.get("CLOUDSMITH_CLI_PATH");
    if (explicitPath != null && !explicitPath.isEmpty()) {
      Path binary = toPath(explicitPath);
      return binary != null && isExecutableFile(binary) ? binary : null;
    }
    String searchPath = environment.getOrDefault("PATH", "");
    for (String entry : searchPath.split(Pattern.quote(File.pathSeparator))) {
      Path directory = toPath(entry);
      if (directory != null && directory.isAbsolute()) {
        Path candidate = directory.resolve(windows ? "cloudsmith.exe" : "cloudsmith");
        if (isExecutableFile(candidate)) {
          return candidate;
        }
      }
    }
    return null;
  }

  /**
   * Runs the binary directly, with no shell and an empty stdin, and returns its stdout.
   *
   * <p>A failure logs the stderr of the CLI, which explains most failures. The CLI never prints the
   * credential on stderr.
   */
  static String run(Path binary, List<String> arguments, Duration timeout) {
    List<String> command = new ArrayList<>();
    command.add(binary.toString());
    command.addAll(arguments);
    String commandLine = "cloudsmith " + String.join(" ", arguments);
    Process process;
    try {
      process = new ProcessBuilder(command).start();
      process.getOutputStream().close();
    } catch (IOException notExecutable) {
      Warnings.warn(
          "%s could not start, %s: %s"
              .formatted(commandLine, NO_CREDENTIAL, notExecutable.getMessage()));
      return null;
    }

    // Each stream is read on its own thread, so a full pipe cannot block the CLI past the timeout.
    FutureTask<byte[]> stdout = readInBackground(process.getInputStream(), "stdout");
    FutureTask<byte[]> stderr = readInBackground(process.getErrorStream(), "stderr");
    try {
      if (!process.waitFor(timeout.toMillis(), MILLISECONDS)) {
        Warnings.warn(
            ("%s did not finish in %s seconds, %s."
                    + " Set CLOUDSMITH_MAVEN_TOOLS_TIMEOUT to allow more time.")
                .formatted(commandLine, timeout.toMillis() / 1000.0, NO_CREDENTIAL));
        return null;
      }
      if (process.exitValue() != 0) {
        Warnings.warn(
            "%s exited with status %d, %s.%s"
                .formatted(
                    commandLine,
                    process.exitValue(),
                    NO_CREDENTIAL,
                    stderrSection(stderr, timeout)));
        return null;
      }
      return new String(stdout.get(timeout.toMillis(), MILLISECONDS), StandardCharsets.UTF_8);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException | TimeoutException unreadable) {
      Warnings.warn(
          "The output of %s could not be read, %s.".formatted(commandLine, NO_CREDENTIAL));
      return null;
    } finally {
      process.destroyForcibly();
    }
  }

  private static FutureTask<byte[]> readInBackground(InputStream stream, String name) {
    FutureTask<byte[]> content = new FutureTask<>(stream::readAllBytes);
    Thread reader = new Thread(content, "cloudsmith-cli-" + name);
    reader.setDaemon(true);
    reader.start();
    return content;
  }

  /** Returns the stderr of a finished CLI as lines to append to a message, or an empty string. */
  private static String stderrSection(FutureTask<byte[]> stderr, Duration timeout)
      throws InterruptedException {
    try {
      String text =
          new String(stderr.get(timeout.toMillis(), MILLISECONDS), StandardCharsets.UTF_8).strip();
      return text.isEmpty() ? "" : " The CLI printed:" + System.lineSeparator() + text;
    } catch (ExecutionException | TimeoutException unreadable) {
      return "";
    }
  }

  private Map<?, ?> runForObject(String... arguments) {
    String stdout = runner.apply(List.of(arguments));
    if (stdout == null || stdout.isBlank()) {
      return null;
    }
    try {
      return Json.parse(stdout) instanceof Map<?, ?> object ? object : null;
    } catch (IllegalArgumentException malformed) {
      Warnings.warn(
          "cloudsmith %s printed output that is not JSON, %s."
              .formatted(String.join(" ", arguments), NO_CREDENTIAL));
      return null;
    }
  }

  private static boolean servesMaven(Map<?, ?> domain) {
    Object domainType = domain.get("domain_type");
    return "download".equals(domainType)
        || ("native_api".equals(domainType) && "maven".equals(domain.get("format")));
  }

  private static String nonEmptyString(Object value) {
    return value instanceof String text && !text.isEmpty() ? text : null;
  }

  private static Path toPath(String value) {
    try {
      return Path.of(value);
    } catch (InvalidPathException invalid) {
      return null;
    }
  }

  private static boolean isExecutableFile(Path path) {
    return Files.isRegularFile(path) && Files.isExecutable(path);
  }
}
