package io.cloudsmith.examples;

/** Compiles only if the build resolved the example library from Cloudsmith. */
public final class App {
  private App() {}

  public static void main(String[] arguments) {
    System.out.println(Greeting.greet());
  }
}
