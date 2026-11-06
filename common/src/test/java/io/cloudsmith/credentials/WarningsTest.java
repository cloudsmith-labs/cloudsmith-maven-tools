package io.cloudsmith.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WarningsTest {
  @Test
  void sendsTheWarningsOfTheActionToTheSink() {
    List<String> warnings = new ArrayList<>();

    String result =
        Warnings.to(
            warnings::add,
            () -> {
              Warnings.warn("first");
              return "done";
            });

    assertEquals("done", result);
    assertEquals(List.of("first"), warnings);
  }

  @Test
  void stopsSendingWarningsToTheSinkWhenTheActionEnds() {
    List<String> warnings = new ArrayList<>();

    Warnings.to(warnings::add, () -> null);
    Warnings.warn("later");

    assertEquals(List.of(), warnings);
  }

  @Test
  void keepsTheWarningsOfOtherThreadsOutOfTheSink() throws InterruptedException {
    List<String> warnings = new ArrayList<>();

    Warnings.to(
        warnings::add,
        () -> {
          Thread other = new Thread(() -> Warnings.warn("other thread"));
          other.start();
          try {
            other.join();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          return null;
        });

    assertEquals(List.of(), warnings);
  }
}
