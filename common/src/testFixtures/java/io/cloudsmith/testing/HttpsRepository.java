package io.cloudsmith.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * A Maven repository on a local HTTPS server that demands Basic auth.
 *
 * <p>A request without an {@code Authorization} header gets 401 in the realms that Cloudsmith uses.
 * The server accepts any credential and records it, so a test asserts which credential the build
 * sent. It serves the files that a test adds, with a SHA-1 checksum for each, and stores each file
 * that a build uploads with PUT.
 *
 * <p>The trust store holds the certificate authorities of the JDK too, so a build that trusts it
 * can also reach Maven Central.
 */
public final class HttpsRepository implements AutoCloseable {
  public static final String HOST = "127.0.0.1";
  public static final String STORE_PASSWORD = "changeit";
  private static final String CONTEXT = "/repository/";

  private final Map<String, byte[]> files = new ConcurrentHashMap<>();
  private final List<String> receivedAuthorizations = new CopyOnWriteArrayList<>();
  private final HttpsServer server;
  private final Path trustStore;

  public HttpsRepository(Path workDirectory) throws Exception {
    Path keyStore = workDirectory.resolve("server.p12");
    trustStore = workDirectory.resolve("trust.p12");
    createKeyAndTrustStores(keyStore, trustStore);

    server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName(HOST), 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(serverSslContext(keyStore)));
    server.createContext(CONTEXT, this::serve);
    server.start();
  }

  /** Serves the content at the path, relative to the repository root, and its SHA-1 checksum. */
  public void addFile(String path, String content) throws IOException {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    files.put(CONTEXT + path, bytes);
    files.put(CONTEXT + path + ".sha1", sha1Hex(bytes).getBytes(StandardCharsets.US_ASCII));
  }

  /** Returns the content that a build uploaded to the path, or {@code null}. */
  public byte[] file(String path) {
    return files.get(CONTEXT + path);
  }

  public String url() {
    return "https://" + HOST + ":" + server.getAddress().getPort() + "/repository";
  }

  public Path trustStore() {
    return trustStore;
  }

  /** The JVM options that make a client trust this server's self-signed certificate. */
  public String trustStoreOptions() {
    return "-Djavax.net.ssl.trustStore="
        + trustStore
        + " -Djavax.net.ssl.trustStorePassword="
        + STORE_PASSWORD
        + " -Djavax.net.ssl.trustStoreType=PKCS12";
  }

  public List<String> receivedAuthorizations() {
    return receivedAuthorizations;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  public static String basic(String username, String password) {
    String pair = username + ":" + password;
    return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
  }

  private void serve(HttpExchange exchange) throws IOException {
    try {
      respond(exchange);
    } finally {
      exchange.close();
    }
  }

  private void respond(HttpExchange exchange) throws IOException {
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    if (authorization == null) {
      // Like Cloudsmith, the upload realm differs from the download realm.
      String realm =
          "PUT".equals(exchange.getRequestMethod())
              ? "Cloudsmith API"
              : "Private Repository: it/it";
      exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"" + realm + "\"");
      exchange.sendResponseHeaders(401, -1);
      return;
    }
    receivedAuthorizations.add(authorization);

    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    if ("PUT".equals(method)) {
      try (InputStream body = exchange.getRequestBody()) {
        files.put(path, body.readAllBytes());
      }
      exchange.sendResponseHeaders(201, -1);
      return;
    }
    byte[] body = files.get(path);
    if (body == null) {
      exchange.sendResponseHeaders(404, -1);
      return;
    }
    boolean isHead = "HEAD".equals(method);
    exchange.sendResponseHeaders(200, isHead ? -1 : body.length);
    if (!isHead) {
      try (OutputStream response = exchange.getResponseBody()) {
        response.write(body);
      }
    }
  }

  private static String sha1Hex(byte[] content) throws IOException {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-1").digest(content);
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IOException(unavailable);
    }
  }

  private static void createKeyAndTrustStores(Path keyStore, Path trustStore) throws Exception {
    Path certificate = keyStore.resolveSibling("server.crt");
    keytool(
        "-genkeypair",
        "-alias",
        "server",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-validity",
        "1",
        "-dname",
        "CN=" + HOST,
        "-ext",
        "SAN=ip:" + HOST,
        "-storetype",
        "PKCS12",
        "-keystore",
        keyStore.toString(),
        "-storepass",
        STORE_PASSWORD,
        "-keypass",
        STORE_PASSWORD);
    keytool(
        "-exportcert",
        "-alias",
        "server",
        "-keystore",
        keyStore.toString(),
        "-storepass",
        STORE_PASSWORD,
        "-file",
        certificate.toString());
    keytool(
        "-importkeystore",
        "-noprompt",
        "-srckeystore",
        Paths.get(System.getProperty("java.home"), "lib", "security", "cacerts").toString(),
        "-srcstorepass",
        STORE_PASSWORD,
        "-deststoretype",
        "PKCS12",
        "-destkeystore",
        trustStore.toString(),
        "-deststorepass",
        STORE_PASSWORD);
    keytool(
        "-importcert",
        "-noprompt",
        "-alias",
        "server",
        "-file",
        certificate.toString(),
        "-storetype",
        "PKCS12",
        "-keystore",
        trustStore.toString(),
        "-storepass",
        STORE_PASSWORD);
  }

  private static void keytool(String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "keytool").toString());
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output;
    try (InputStream stream = process.getInputStream()) {
      output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (process.waitFor() != 0) {
      throw new IOException("keytool failed: " + output);
    }
  }

  private static SSLContext serverSslContext(Path keyStorePath) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (InputStream stream = Files.newInputStream(keyStorePath)) {
      keyStore.load(stream, STORE_PASSWORD.toCharArray());
    }
    KeyManagerFactory keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keyStore, STORE_PASSWORD.toCharArray());
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagers.getKeyManagers(), null, null);
    return context;
  }
}
