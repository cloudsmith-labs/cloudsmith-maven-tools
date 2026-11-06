package io.cloudsmith.credentials;

import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends warnings to SLF4J, or to the logger of the build tool while {@link #to} runs.
 *
 * <p>sbt does not show SLF4J warnings, so the sbt plugin gives its task logger to {@link #to}.
 */
public final class Warnings {
  private static final Logger LOGGER = LoggerFactory.getLogger(Warnings.class);
  private static final ThreadLocal<Consumer<String>> SINK = new ThreadLocal<>();

  private Warnings() {}

  static void warn(String message) {
    Consumer<String> sink = SINK.get();
    if (sink == null) {
      LOGGER.warn(message);
    } else {
      sink.accept(message);
    }
  }

  /** Runs the action and sends the warnings of the current thread to the sink while it runs. */
  public static <T> T to(Consumer<String> sink, Supplier<T> action) {
    Consumer<String> previous = SINK.get();
    SINK.set(sink);
    try {
      return action.get();
    } finally {
      SINK.set(previous);
    }
  }
}
