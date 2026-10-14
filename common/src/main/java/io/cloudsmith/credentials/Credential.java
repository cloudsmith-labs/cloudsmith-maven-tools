package io.cloudsmith.credentials;

/** A Basic auth username and password from {@code cloudsmith credential-helper generic}. */
public record Credential(String username, String password) {
  @Override
  public String toString() {
    return "Credential[username=" + username + "]";
  }
}
