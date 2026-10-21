package works.uncommon.home;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SRP-6a client for HomeKit-style pair-setup (3072-bit group, SHA-512, user
 * "Pair-Setup", password = the PIN on screen), byte-for-byte the same as the
 * srptools library pyatv pairs Apple TVs with. Also the HKDF used around it.
 */
final class Srp {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // RFC 5054 3072-bit group
    private static final BigInteger N = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
                    + "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
                    + "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
                    + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
                    + "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
                    + "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
                    + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
                    + "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33"
                    + "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7"
                    + "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864"
                    + "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2"
                    + "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16);
    private static final BigInteger G = BigInteger.valueOf(5);
    private static final String USER = "Pair-Setup";

    private final BigInteger a;
    private final BigInteger bigA;

    private byte[] key;         // K = H(S)
    private byte[] proof;       // M1
    private byte[] proofHash;   // M2, what the Apple TV should send back

    Srp() {
        a = new BigInteger(1, Nacl.random(32));
        bigA = G.modPow(a, N);
    }

    byte[] publicKey() {
        return bytes(bigA);
    }

    byte[] proof() {
        return proof;
    }

    byte[] sessionKey() {
        return key;
    }

    /** Runs the client side with the Apple TV's salt and public key B, and the PIN. */
    void process(String pin, byte[] salt, byte[] serverPublic) throws GeneralSecurityException {
        BigInteger bigB = new BigInteger(1, serverPublic);
        if (bigB.mod(N).signum() == 0) throw new GeneralSecurityException("bad public key");
        BigInteger k = hashInt(bytes(N), pad(G));
        BigInteger u = hashInt(pad(bigA), pad(bigB));
        byte[] inner = hash((USER + ":" + pin).getBytes(UTF8));
        BigInteger x = hashInt(salt, inner);
        BigInteger v = G.modPow(x, N);
        BigInteger base = bigB.subtract(k.multiply(v)).mod(N);
        BigInteger s = base.modPow(a.add(u.multiply(x)), N);
        key = hash(bytes(s));
        BigInteger hng = hashInt(bytes(N)).xor(hashInt(bytes(G)));
        proof = hash(bytes(hng), bytes(hashInt(USER.getBytes(UTF8))), salt, bytes(bigA), bytes(bigB), key);
        proofHash = hash(bytes(bigA), proof, key);
    }

    boolean verifyServerProof(byte[] serverProof) {
        return serverProof != null && MessageDigest.isEqual(serverProof, proofHash);
    }

    // srptools turns integers into bytes without leading zeros, except where it pads to |N|.
    private static byte[] bytes(BigInteger v) {
        byte[] b = v.toByteArray();
        int i = 0;
        while (i < b.length - 1 && b[i] == 0) i++;
        return i == 0 ? b : Arrays.copyOfRange(b, i, b.length);
    }

    private static byte[] pad(BigInteger v) {
        byte[] b = bytes(v);
        int n = bytes(N).length;
        if (b.length >= n) return b;
        byte[] out = new byte[n];
        System.arraycopy(b, 0, out, n - b.length, b.length);
        return out;
    }

    private static byte[] hash(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigInteger hashInt(byte[]... parts) {
        return new BigInteger(1, hash(parts));
    }

    /** HKDF-SHA512 with a 32-byte output, as HomeKit uses. */
    static byte[] hkdf(String salt, String info, byte[] secret) {
        try {
            byte[] s = salt.getBytes(UTF8);
            if (s.length == 0) s = new byte[64];    // an empty HMAC key is the same as all zeros
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(s, "HmacSHA512"));
            byte[] prk = mac.doFinal(secret);
            mac.init(new SecretKeySpec(prk, "HmacSHA512"));
            mac.update(info.getBytes(UTF8));
            mac.update((byte) 1);
            return Arrays.copyOf(mac.doFinal(), 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
