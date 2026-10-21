package works.uncommon.home;

import java.math.BigInteger;
import java.security.GeneralSecurityException;

/** ChaCha20-Poly1305 AEAD (RFC 8439), for the Apple TV link. Messages are small. */
final class ChaChaPoly {

    private ChaChaPoly() {
    }

    private static final BigInteger P1305 = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5));
    private static final BigInteger MASK128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);

    /** Returns ciphertext followed by the 16-byte tag. */
    static byte[] seal(byte[] key, byte[] nonce, byte[] plain, byte[] aad) {
        byte[] out = new byte[plain.length + 16];
        xor(key, nonce, 1, plain, 0, plain.length, out, 0);
        byte[] tag = tag(key, nonce, out, plain.length, aad);
        System.arraycopy(tag, 0, out, plain.length, 16);
        return out;
    }

    /** Opens ciphertext+tag; throws if the tag doesn't match. */
    static byte[] open(byte[] key, byte[] nonce, byte[] sealed, byte[] aad) throws GeneralSecurityException {
        if (sealed.length < 16) throw new GeneralSecurityException("short message");
        int n = sealed.length - 16;
        byte[] expect = tag(key, nonce, sealed, n, aad);
        int diff = 0;
        for (int i = 0; i < 16; i++) diff |= expect[i] ^ sealed[n + i];
        if (diff != 0) throw new GeneralSecurityException("bad tag");
        byte[] out = new byte[n];
        xor(key, nonce, 1, sealed, 0, n, out, 0);
        return out;
    }

    private static byte[] tag(byte[] key, byte[] nonce, byte[] ct, int ctLen, byte[] aad) {
        byte[] block0 = new byte[64];
        block(key, nonce, 0, block0);
        byte[] polyKey = new byte[32];
        System.arraycopy(block0, 0, polyKey, 0, 32);
        int aadLen = aad == null ? 0 : aad.length;
        int len = pad16(aadLen) + pad16(ctLen) + 16;
        byte[] mac = new byte[len];
        if (aadLen > 0) System.arraycopy(aad, 0, mac, 0, aadLen);
        System.arraycopy(ct, 0, mac, pad16(aadLen), ctLen);
        le64(mac, len - 16, aadLen);
        le64(mac, len - 8, ctLen);
        return poly1305(polyKey, mac);
    }

    private static int pad16(int n) {
        return (n + 15) / 16 * 16;
    }

    private static void le64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * i));
    }

    private static byte[] poly1305(byte[] key, byte[] msg) {
        byte[] rb = new byte[16];
        System.arraycopy(key, 0, rb, 0, 16);
        rb[3] &= 15;
        rb[7] &= 15;
        rb[11] &= 15;
        rb[15] &= 15;
        rb[4] &= (byte) 252;
        rb[8] &= (byte) 252;
        rb[12] &= (byte) 252;
        BigInteger r = le(rb, 0, 16);
        BigInteger s = le(key, 16, 16);
        BigInteger acc = BigInteger.ZERO;
        for (int i = 0; i < msg.length; i += 16) {
            int n = Math.min(16, msg.length - i);
            byte[] blk = new byte[n + 1];
            System.arraycopy(msg, i, blk, 0, n);
            blk[n] = 1;
            acc = acc.add(le(blk, 0, n + 1)).multiply(r).mod(P1305);
        }
        acc = acc.add(s).and(MASK128);
        byte[] out = new byte[16];
        byte[] be = acc.toByteArray();
        for (int i = 0; i < 16 && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    private static BigInteger le(byte[] b, int off, int len) {
        byte[] be = new byte[len + 1];
        for (int i = 0; i < len; i++) be[len - i] = b[off + i];
        return new BigInteger(be);
    }

    private static void xor(byte[] key, byte[] nonce, int counter, byte[] in, int inOff, int len, byte[] out, int outOff) {
        byte[] ks = new byte[64];
        for (int pos = 0; pos < len; pos += 64) {
            block(key, nonce, counter++, ks);
            int n = Math.min(64, len - pos);
            for (int i = 0; i < n; i++) out[outOff + pos + i] = (byte) (in[inOff + pos + i] ^ ks[i]);
        }
    }

    private static int rd(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }

    private static void block(byte[] key, byte[] nonce, int counter, byte[] out) {
        int[] s = new int[16];
        s[0] = 0x61707865;
        s[1] = 0x3320646e;
        s[2] = 0x79622d32;
        s[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) s[4 + i] = rd(key, 4 * i);
        s[12] = counter;
        s[13] = rd(nonce, 0);
        s[14] = rd(nonce, 4);
        s[15] = rd(nonce, 8);
        int[] x = s.clone();
        for (int i = 0; i < 10; i++) {
            qr(x, 0, 4, 8, 12);
            qr(x, 1, 5, 9, 13);
            qr(x, 2, 6, 10, 14);
            qr(x, 3, 7, 11, 15);
            qr(x, 0, 5, 10, 15);
            qr(x, 1, 6, 11, 12);
            qr(x, 2, 7, 8, 13);
            qr(x, 3, 4, 9, 14);
        }
        for (int i = 0; i < 16; i++) {
            int v = x[i] + s[i];
            out[4 * i] = (byte) v;
            out[4 * i + 1] = (byte) (v >>> 8);
            out[4 * i + 2] = (byte) (v >>> 16);
            out[4 * i + 3] = (byte) (v >>> 24);
        }
    }

    private static void qr(int[] x, int a, int b, int c, int d) {
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }
}
