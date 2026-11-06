package io.cloudsmith.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** A shell script that answers the two {@code cloudsmith} commands like the real CLI. */
public final class FakeCloudsmithCli {
  public static final String USERNAME = "token";
  public static final String PASSWORD = "it-secret";
  public static final String PROXY_HINT =
      "set REQUESTS_CA_BUNDLE to a CA bundle that contains the proxy CA.";

  private FakeCloudsmithCli() {}

  /** Writes a script that lists one Maven host and prints the test credential. */
  public static Path write(Path directory, String listedHost) throws IOException {
    Path script = directory.resolve("cloudsmith");
    Files.writeString(
        script,
        """
        #!/bin/sh
        case "$1 $2" in
          "domains list")
            printf '%%s\\n' '{"version": 1, "domains": [{"host": "%s", "format": "maven", "type": "custom", "domain_type": "native_api"}]}' ;;
          "credential-helper generic")
            printf '%%s\\n' '{"version": 1, "username": "%s", "password": "%s"}' ;;
          *)
            exit 1 ;;
        esac
        """
            .formatted(listedHost, USERNAME, PASSWORD));
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    return script;
  }

  /**
   * Writes a script that fails like the CLI behind a TLS-intercepting proxy until {@code
   * REQUESTS_CA_BUNDLE} is set, and then answers like {@link #write}.
   */
  public static Path writeBehindProxy(Path directory, String listedHost) throws IOException {
    Path trusted = write(Files.createDirectories(directory.resolve("trusted")), listedHost);
    Path script = directory.resolve("cloudsmith");
    Files.writeString(
        script,
        """
        #!/bin/sh
        if [ -z "$REQUESTS_CA_BUNDLE" ]; then
          echo 'Error: certificate verify failed: unable to get local issuer certificate' >&2
          echo 'Hint: %s' >&2
          exit 1
        fi
        exec '%s' "$@"
        """
            .formatted(PROXY_HINT, trusted));
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    return script;
  }
}
