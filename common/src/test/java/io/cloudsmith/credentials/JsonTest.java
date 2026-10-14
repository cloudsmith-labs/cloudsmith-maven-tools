package io.cloudsmith.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {
  @Test
  void parsesNestedObjectsAndArrays() {
    Object parsed =
        Json.parse(" {\"version\": 1, \"domains\": [{\"host\": \"dl.cloudsmith.io\"}]} ");

    Map<String, Object> domain = new LinkedHashMap<>();
    domain.put("host", "dl.cloudsmith.io");
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("version", 1L);
    expected.put("domains", List.of(domain));
    assertEquals(expected, parsed);
  }

  @Test
  void parsesLiteralsAndNumbers() {
    assertEquals(
        Arrays.asList(true, false, null, -12L, 2.5, 1.0e3),
        Json.parse("[true, false, null, -12, 2.5, 1e3]"));
  }

  @Test
  void decodesStringEscapes() {
    assertEquals("a\"b\\c/d\b\f\n\r\té", Json.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\\u00e9\""));
  }

  @Test
  void parsesEmptyContainers() {
    assertEquals(Map.of(), Json.parse("{}"));
    assertEquals(List.of(), Json.parse("[ ]"));
  }

  @Test
  void parsesAnIntegerTooLargeForALongAsADouble() {
    assertEquals(1.0e20, Json.parse("100000000000000000000"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "{",
        "{\"a\" 1}",
        "{\"a\": 1,}",
        "[1 2]",
        "\"unterminated",
        "\"control\u0001character\"",
        "\"bad \\x escape\"",
        "\"short \\u12 escape\"",
        "True",
        "01",
        "-",
        "1.",
        "{} trailing",
        "{1: 2}"
      })
  void rejectsMalformedInput(String text) {
    assertThrows(IllegalArgumentException.class, () -> Json.parse(text));
  }
}
