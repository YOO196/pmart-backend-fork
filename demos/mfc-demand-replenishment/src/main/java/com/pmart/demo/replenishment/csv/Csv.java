package com.pmart.demo.replenishment.csv;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** UTF-8 CSV 읽기·쓰기(RFC 4180 따옴표 규칙). 데모는 CSV 만 읽는다(명세 6장 "입력 포맷"). */
public final class Csv {

  private Csv() {}

  /** 머리줄 이름 → 값. 머리줄에 없는 이름을 물으면 예외. */
  public record Row(long lineNo, Map<String, String> values) {
    public String get(String column) {
      if (!values.containsKey(column)) {
        throw new IllegalArgumentException("CSV 열이 없음: " + column + " (줄 " + lineNo + ")");
      }
      return values.get(column);
    }

    /** 비었으면 null. */
    public String opt(String column) {
      String v = values.get(column);
      return v == null || v.isBlank() ? null : v.trim();
    }
  }

  /** 줄마다 consumer 를 부른다(큰 파일도 메모리에 다 올리지 않는다). */
  public static void read(Path file, Consumer<Row> consumer) throws IOException {
    try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      List<String> header = null;
      long lineNo = 0;
      String record;
      while ((record = nextRecord(in)) != null) {
        lineNo++;
        if (record.isEmpty()) {
          continue;
        }
        List<String> fields = split(record);
        if (header == null) {
          header = new ArrayList<>();
          for (String h : fields) {
            header.add(h.replace("﻿", "").trim());
          }
          continue;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
          values.put(header.get(i), i < fields.size() ? fields.get(i) : "");
        }
        consumer.accept(new Row(lineNo, values));
      }
    }
  }

  public static List<Row> readAll(Path file) throws IOException {
    List<Row> rows = new ArrayList<>();
    read(file, rows::add);
    return rows;
  }

  /** 따옴표 안 줄바꿈까지 포함한 한 레코드. */
  private static String nextRecord(BufferedReader in) throws IOException {
    String line = in.readLine();
    if (line == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder(line);
    while (countQuotes(sb) % 2 == 1) {
      String more = in.readLine();
      if (more == null) {
        break;
      }
      sb.append('\n').append(more);
    }
    return sb.toString();
  }

  private static int countQuotes(CharSequence s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '"') {
        n++;
      }
    }
    return n;
  }

  static List<String> split(String record) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < record.length(); i++) {
      char c = record.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < record.length() && record.charAt(i + 1) == '"') {
            cur.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          cur.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        out.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    out.add(cur.toString());
    return out;
  }

  /** 간단한 CSV 쓰기. 값에 쉼표·따옴표·줄바꿈이 있으면 따옴표로 감싼다. */
  public static final class Writer implements AutoCloseable {
    private final BufferedWriter out;

    public Writer(Path file, String... header) throws IOException {
      Files.createDirectories(file.toAbsolutePath().getParent());
      this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
      row((Object[]) header);
    }

    public void row(Object... values) throws IOException {
      for (int i = 0; i < values.length; i++) {
        if (i > 0) {
          out.write(',');
        }
        out.write(escape(values[i]));
      }
      out.write('\n');
    }

    private static String escape(Object value) {
      if (value == null) {
        return "";
      }
      String s = value.toString();
      if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
        return '"' + s.replace("\"", "\"\"") + '"';
      }
      return s;
    }

    @Override
    public void close() throws IOException {
      out.close();
    }
  }
}
