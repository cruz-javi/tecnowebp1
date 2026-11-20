package unexo.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Hash de contraseñas con PBKDF2-HMAC-SHA256 (formato iteraciones:salt:hash). */
public final class Passwords {

    private static final int ITERACIONES = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] h = pbkdf2(password, salt, ITERACIONES);
        Base64.Encoder b64 = Base64.getEncoder();
        return ITERACIONES + ":" + b64.encodeToString(salt) + ":" + b64.encodeToString(h);
    }

    public static boolean verificar(String password, String almacenado) {
        if (password == null || almacenado == null) {
            return false;
        }
        String[] p = almacenado.split(":");
        if (p.length != 3) {
            return false;
        }
        Base64.Decoder d = Base64.getDecoder();
        byte[] salt = d.decode(p[1]);
        byte[] esperado = d.decode(p[2]);
        byte[] real = pbkdf2(password, salt, Integer.parseInt(p[0]));
        return MessageDigest.isEqual(esperado, real);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iter) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iter, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
