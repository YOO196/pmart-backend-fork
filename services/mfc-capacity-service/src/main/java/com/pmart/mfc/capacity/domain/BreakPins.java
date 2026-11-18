package com.pmart.mfc.capacity.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * 센터 휴게 번호(2026-11-18 사용자 결정 — mfc-app B1·B2): 4자리, 저장은 PBKDF2 해시만, 연속 5번 틀리면 그 작업자 15분 잠금. 번호 자체는
 * 로그·사건·응답에 남기지 않는다.
 */
public final class BreakPins {

  public static final String NOT_SET = "BREAK_PIN_NOT_SET";
  public static final String INVALID = "BREAK_PIN_INVALID";
  public static final String LOCKED = "BREAK_PIN_LOCKED";
  public static final String AUTO_BREAK_SUPERVISOR_ONLY = "AUTO_BREAK_SUPERVISOR_ONLY";

  public static final int MAX_FAILURES = 5;
  public static final Duration LOCK = Duration.ofMinutes(15);

  private static final String ALG = "PBKDF2WithHmacSHA256";
  private static final int ITERATIONS = 120_000;
  private static final int SALT_BYTES = 16;
  private static final int KEY_BITS = 256;
  private static final SecureRandom RANDOM = new SecureRandom();

  private BreakPins() {}

  public static boolean wellFormed(@Nullable String pin) {
    return pin != null && pin.matches("[0-9]{4}");
  }

  /** {@code pbkdf2$<반복>$<salt b64>$<해시 b64>}. */
  public static String hash(String pin) {
    byte[] salt = new byte[SALT_BYTES];
    RANDOM.nextBytes(salt);
    return "pbkdf2$" + ITERATIONS + "$" + b64(salt) + "$" + b64(derive(pin, salt, ITERATIONS));
  }

  public static boolean matches(String pin, String stored) {
    String[] p = stored.split("\\$");
    if (p.length != 4 || !"pbkdf2".equals(p[0])) {
      return false;
    }
    byte[] salt = Base64.getDecoder().decode(p[2]);
    byte[] want = Base64.getDecoder().decode(p[3]);
    return MessageDigest.isEqual(want, derive(pin, salt, Integer.parseInt(p[1])));
  }

  private static byte[] derive(String pin, byte[] salt, int iterations) {
    try {
      var spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS);
      return SecretKeyFactory.getInstance(ALG).generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(ALG + " 를 쓸 수 없습니다", e);
    }
  }

  private static String b64(byte[] b) {
    return new String(Base64.getEncoder().encode(b), StandardCharsets.US_ASCII);
  }
}
