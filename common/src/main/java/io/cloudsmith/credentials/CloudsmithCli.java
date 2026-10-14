package io.cloudsmith.credentials;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.File;
import java.io.IOException;
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
 * build to behave as if the extension or plugin were absent.
 */
public final class CloudsmithCli {
  private static final Logger LOGGER = LoggerFactory.getLogger(CloudsmithCli.class);
  private static final Long CONTRACT_VERSION = 1L;

  /** Takes the CLI arguments and returns the stdout of the CLI, or {@code null} if it failed. */
  private final Function<List<String>, String> runner;

  public CloudsmithCli(Function<List<String>, String> runner) {
    this.runner = runner;
  }

  /** Returns a CLI that runs the {@code cloudsmith} binary, or {@code null} if there is none. */
  static CloudsmithCli locate(Map<String, String> environment, boolean windows, Duration timeout) {
    Path binary = findBinary(environment, windows);
    return binary == null ? null : new CloudsmithCli(arguments -> run(binary, arguments, timeout));
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

  /** Runs the binary directly, with no shell and an empty stdin, and returns its stdout. */
  static String run(Path binary, List<String> arguments, Duration timeout) {
    List<String> command = new ArrayList<>();
    command.add(binary.toString());
    command.addAll(arguments);
    Process process;
    try {
      process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      process.getOutputStream().close();
    } catch (IOException notExecutable) {
      LOGGER.debug("The cloudsmith CLI could not be executed: {}", notExecutable.getMessage());
      return null;
    }

    // Stdout is read on its own thread, so a full pipe cannot block the CLI past the timeout.
    FutureTask<byte[]> stdout = new FutureTask<>(process.getInputStream()::readAllBytes);
    Thread reader = new Thread(stdout, "cloudsmith-cli-stdout");
    reader.setDaemon(true);
    reader.start();
    try {
      if (!process.waitFor(timeout.toMillis(), MILLISECONDS)) {
        LOGGER.debug("The cloudsmith CLI timed out after {}", timeout);
        return null;
      }
      if (process.exitValue() != 0) {
        LOGGER.debug("The cloudsmith CLI exited with status {}", process.exitValue());
        return null;
      }
      return new String(stdout.get(timeout.toMillis(), MILLISECONDS), StandardCharsets.UTF_8);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException | TimeoutException unreadable) {
      LOGGER.debug("The output of the cloudsmith CLI could not be read");
      return null;
    } finally {
      process.destroyForcibly();
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
      LOGGER.debug("cloudsmith {} printed output that is not JSON", arguments[0]);
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
