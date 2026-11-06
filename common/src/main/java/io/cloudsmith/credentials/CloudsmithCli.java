package io.cloudsmith.credentials;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
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
import java.util.regex.Matcher;
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
  private static final String NO_CREDENTIAL =
      "so the build gets no credential for Cloudsmith repositories";
  private static final String SIGN_IN =
      " Sign in with `cloudsmith auth`, or set CLOUDSMITH_API_KEY. `cloudsmith whoami --verbose`"
          + " shows the credential that the CLI uses.";

  /** The text of the CLI when it has no credential, or the API rejects it. */
  private static final Pattern NO_VALID_CREDENTIAL =
      Pattern.compile("\\b401\\b|Unable to retrieve credentials");

  private static final Pattern FIRST_ERROR = Pattern.compile("(?m)^Error:");
  private static final int STDERR_LIMIT = 16 * 1024;

  /** The stdout and stderr of a CLI command that exited with status 0. */
  public record Output(String stdout, String stderr) {}

  /** Takes the CLI arguments and returns the output of the CLI, or {@code null} if it failed. */
  private final Function<List<String>, Output> runner;

  public CloudsmithCli(Function<List<String>, Output> runner) {
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
      return ("Cloudsmith: CLOUDSMITH_CLI_PATH is %s, which is not an executable file, %s."
              + " Set CLOUDSMITH_CLI_PATH to the path of the cloudsmith CLI.")
          .formatted(explicitPath, NO_CREDENTIAL);
    }
    return ("Cloudsmith: the cloudsmith CLI is not on PATH, %s. Install the cloudsmith CLI, or set"
            + " CLOUDSMITH_CLI_PATH to its path.")
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
   * Runs the binary directly, with no shell and an empty stdin, and returns its output.
   *
   * <p>A failure logs the stderr of the CLI, which explains most failures. The CLI never prints the
   * credential on stderr.
   */
  static Output run(Path binary, List<String> arguments, Duration timeout) {
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
          "Cloudsmith: the cloudsmith CLI could not start, %s. `%s` failed: %s"
              .formatted(NO_CREDENTIAL, commandLine, notExecutable.getMessage()));
      return null;
    }

    // Each stream is read on its own thread, so a full pipe cannot block the CLI past the timeout.
    FutureTask<byte[]> stdout = new FutureTask<>(process.getInputStream()::readAllBytes);
    Thread reader = new Thread(stdout, "cloudsmith-cli-stdout");
    reader.setDaemon(true);
    reader.start();
    StderrCollector stderr = new StderrCollector(process.getErrorStream());
    try {
      if (!process.waitFor(timeout.toMillis(), MILLISECONDS)) {
        Warnings.warn(
            ("Cloudsmith: the cloudsmith CLI did not answer in %s seconds, %s."
                    + " Set CLOUDSMITH_MAVEN_TOOLS_TIMEOUT to a larger number of seconds.%s")
                .formatted(
                    seconds(timeout),
                    NO_CREDENTIAL,
                    printed("`%s` printed so far:".formatted(commandLine), stderr.textSoFar())));
        return null;
      }
      if (process.exitValue() != 0) {
        Warnings.warn(failureMessage(commandLine, process.exitValue(), stderr.text(timeout)));
        return null;
      }
      return new Output(
          new String(stdout.get(timeout.toMillis(), MILLISECONDS), StandardCharsets.UTF_8),
          stderr.text(timeout));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException | TimeoutException unreadable) {
      Warnings.warn(
          ("Cloudsmith: the output of the cloudsmith CLI could not be read, %s."
                  + " The command was `%s`.")
              .formatted(NO_CREDENTIAL, commandLine));
      return null;
    } finally {
      process.destroyForcibly();
    }
  }

  /**
   * Returns the warning for a CLI command that failed. A missing or rejected credential is the
   * usual cause, so that warning says how to sign in.
   */
  private static String failureMessage(String commandLine, int status, String stderr) {
    String cause =
        NO_VALID_CREDENTIAL.matcher(stderr).find()
            ? "the cloudsmith CLI is not signed in, or its credential is not valid, %s.%s"
                .formatted(NO_CREDENTIAL, SIGN_IN)
            : "the cloudsmith CLI failed, %s.".formatted(NO_CREDENTIAL);
    String exited = "`%s` exited with status %d".formatted(commandLine, status);
    return "Cloudsmith: "
        + cause
        + (stderr.isEmpty() ? " " + exited + "." : printed(exited + " and printed:", stderr));
  }

  /**
   * Returns the introduction and then the stderr of the CLI from its first {@code Error:} line, so
   * that unrelated notices of the CLI drop out. Returns an empty string for an empty stderr.
   */
  private static String printed(String introduction, String stderr) {
    if (stderr.isEmpty()) {
      return "";
    }
    Matcher error = FIRST_ERROR.matcher(stderr);
    String relevant = error.find() ? stderr.substring(error.start()) : stderr;
    return " " + introduction + System.lineSeparator() + relevant;
  }

  /** Returns the duration as a number of seconds with no trailing zeros, for example 30 or 0.5. */
  private static String seconds(Duration duration) {
    return BigDecimal.valueOf(duration.toMillis(), 3).stripTrailingZeros().toPlainString();
  }

  /**
   * Reads the stderr of the CLI on its own thread, so a full pipe cannot block the CLI, and keeps
   * the first {@value #STDERR_LIMIT} bytes. A timeout can then report what the CLI printed so far.
   */
  private static final class StderrCollector {
    private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
    private final Thread reader;

    StderrCollector(InputStream stream) {
      reader = new Thread(() -> collect(stream), "cloudsmith-cli-stderr");
      reader.setDaemon(true);
      reader.start();
    }

    private void collect(InputStream stream) {
      byte[] buffer = new byte[8192];
      try {
        for (int read = stream.read(buffer); read != -1; read = stream.read(buffer)) {
          synchronized (kept) {
            kept.write(buffer, 0, Math.min(read, STDERR_LIMIT - kept.size()));
          }
        }
      } catch (IOException closed) {
        // The process ended, so the text so far is all the text.
      }
    }

    /** Returns the text after the CLI closes stderr, or after the wait, whichever comes first. */
    String text(Duration wait) throws InterruptedException {
      reader.join(Math.max(1, wait.toMillis()));
      return textSoFar();
    }

    String textSoFar() {
      synchronized (kept) {
        return kept.toString(StandardCharsets.UTF_8).strip();
      }
    }
  }

  private Map<?, ?> runForObject(String... arguments) {
    Output output = runner.apply(List.of(arguments));
    if (output == null) {
      return null;
    }
    try {
      return Json.parse(output.stdout()) instanceof Map<?, ?> object ? object : null;
    } catch (IllegalArgumentException malformed) {
      Warnings.warn(
          ("Cloudsmith: the cloudsmith CLI gave an answer that is not JSON, %s."
                  + " Update the cloudsmith CLI.%s")
              .formatted(
                  NO_CREDENTIAL,
                  printed(
                      "`cloudsmith %s` printed:".formatted(String.join(" ", arguments)),
                      output.stderr())));
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
