package io.cloudsmith.credentials;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class CredentialTest {
  @Test
  void keepsThePasswordOutOfToString() {
    assertFalse(new Credential("token", "secret").toString().contains("secret"));
  }
}
