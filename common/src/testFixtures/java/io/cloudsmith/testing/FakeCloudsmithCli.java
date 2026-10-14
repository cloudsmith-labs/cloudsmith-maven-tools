package io.cloudsmith.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** A shell script that answers the two {@code cloudsmith} commands like the real CLI. */
public final class FakeCloudsmithCli {
  public static final String USERNAME = "token";
  public static final String PASSWORD = "it-secret";

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
}
