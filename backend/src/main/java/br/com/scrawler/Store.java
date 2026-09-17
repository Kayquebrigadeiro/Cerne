package br.com.scrawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Store {
  final JdbcTemplate jdbc;
  final ObjectMapper json;

  Store(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  void lock() {
    jdbc.execute("SELECT lock_dominio()");
  }

  String json(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON invalido", e);
    }
  }

  Object decode(String s) {
    try {
      return json.readValue(s, Object.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON invalido", e);
    }
  }

  List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
        sql,
        (rs, i) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          var meta = rs.getMetaData();
          for (int c = 1; c <= meta.getColumnCount(); c++) {
            Object value = rs.getObject(c);
            String type = meta.getColumnTypeName(c);
            if (value instanceof java.sql.Array a) value = a.getArray();
            else if (value != null && (type.equals("jsonb") || type.equals("json")))
              value = decode(value.toString());
            else if (value != null
                && meta.getColumnType(c) == Types.OTHER
                && !(value instanceof UUID)) value = value.toString();
            m.put(meta.getColumnLabel(c), value);
          }
          return m;
        },
        args);
  }

  Map<String, Object> one(String sql, Object... args) {
    var r = rows(sql, args);
    if (r.isEmpty()) throw new Missing();
    return r.get(0);
  }

  static class Missing extends RuntimeException {}

  static String text(String x) {
    return x == null ? "" : x;
  }

  static void xor(UUID e, UUID v) {
    if ((e == null) == (v == null))
      throw new IllegalArgumentException("Informe exatamente exercicioId OU variacaoId");
  }
}
