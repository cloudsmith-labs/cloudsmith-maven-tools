package io.cloudsmith.examples;

/** The library that the example app depends on. */
public final class Greeting {
  private Greeting() {}

  public static String greet() {
    return "Hello from a private Cloudsmith repository";
  }
}
