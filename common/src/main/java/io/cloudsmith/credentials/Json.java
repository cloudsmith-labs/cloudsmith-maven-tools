package io.cloudsmith.credentials;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A strict RFC 8259 JSON parser for the small documents that the {@code cloudsmith} CLI prints.
 *
 * <p>This code runs inside the class loader of Maven or Gradle, so it carries no JSON library that
 * could conflict with one that a build already uses. Objects become {@link Map}, arrays become
 * {@link List}, integers become {@link Long} and other numbers become {@link Double}.
 */
final class Json {
  private static final Pattern NUMBER =
      Pattern.compile("-?(?:0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

  private final String text;
  private int position;

  private Json(String text) {
    this.text = text;
  }

  /** Parses one JSON value, or throws {@link IllegalArgumentException} if the text is not JSON. */
  static Object parse(String text) {
    Json parser = new Json(text);
    Object value = parser.readValue();
    parser.skipWhitespace();
    if (parser.position != text.length()) {
      throw parser.error("unexpected content after the value");
    }
    return value;
  }

  private Object readValue() {
    skipWhitespace();
    if (position >= text.length()) {
      throw error("unexpected end of input");
    }
    return switch (text.charAt(position)) {
      case '{' -> readObject();
      case '[' -> readArray();
      case '"' -> readString();
      case 't' -> readLiteral("true", Boolean.TRUE);
      case 'f' -> readLiteral("false", Boolean.FALSE);
      case 'n' -> readLiteral("null", null);
      default -> readNumber();
    };
  }

  private Map<String, Object> readObject() {
    Map<String, Object> members = new LinkedHashMap<>();
    position++;
    skipWhitespace();
    if (consume('}')) {
      return members;
    }
    do {
      skipWhitespace();
      String name = readString();
      skipWhitespace();
      expect(':');
      members.put(name, readValue());
      skipWhitespace();
    } while (consume(','));
    expect('}');
    return members;
  }

  private List<Object> readArray() {
    List<Object> elements = new ArrayList<>();
    position++;
    skipWhitespace();
    if (consume(']')) {
      return elements;
    }
    do {
      elements.add(readValue());
      skipWhitespace();
    } while (consume(','));
    expect(']');
    return elements;
  }

  private String readString() {
    expect('"');
    StringBuilder value = new StringBuilder();
    while (position < text.length()) {
      char current = text.charAt(position++);
      if (current == '"') {
        return value.toString();
      }
      if (current < 0x20) {
        throw error("unescaped control character in a string");
      }
      value.append(current == '\\' ? readEscape() : current);
    }
    throw error("unterminated string");
  }

  private char readEscape() {
    if (position >= text.length()) {
      throw error("unterminated escape sequence");
    }
    char escape = text.charAt(position++);
    return switch (escape) {
      case '"', '\\', '/' -> escape;
      case 'b' -> '\b';
      case 'f' -> '\f';
      case 'n' -> '\n';
      case 'r' -> '\r';
      case 't' -> '\t';
      case 'u' -> readUnicodeEscape();
      default -> throw error("invalid escape sequence");
    };
  }

  private char readUnicodeEscape() {
    int end = position + 4;
    if (end > text.length()
        || !text.substring(position, end).chars().allMatch(HexFormat::isHexDigit)) {
      throw error("invalid unicode escape");
    }
    char codeUnit = (char) HexFormat.fromHexDigits(text, position, end);
    position = end;
    return codeUnit;
  }

  private Object readLiteral(String literal, Object value) {
    if (!text.startsWith(literal, position)) {
      throw error("invalid literal");
    }
    position += literal.length();
    return value;
  }

  private Object readNumber() {
    Matcher matcher = NUMBER.matcher(text).region(position, text.length());
    if (!matcher.lookingAt()) {
      throw error("invalid value");
    }
    position = matcher.end();
    String number = matcher.group();
    boolean isInteger = matcher.group(1) == null && matcher.group(2) == null;
    if (isInteger) {
      try {
        return Long.valueOf(number);
      } catch (NumberFormatException tooLargeForLong) {
        return Double.valueOf(number);
      }
    }
    return Double.valueOf(number);
  }

  private void skipWhitespace() {
    while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) {
      position++;
    }
  }

  private boolean consume(char expected) {
    if (position < text.length() && text.charAt(position) == expected) {
      position++;
      return true;
    }
    return false;
  }

  private void expect(char expected) {
    if (!consume(expected)) {
      throw error("expected '" + expected + "'");
    }
  }

  private IllegalArgumentException error(String reason) {
    return new IllegalArgumentException("Malformed JSON at offset " + position + ": " + reason);
  }
}
