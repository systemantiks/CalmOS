package works.uncommon.home;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * X25519 key agreement and Ed25519 signatures (a Java port of TweetNaCl's
 * curve25519 code). Android only gained these in API 31/33, so they're here
 * for the Apple TV pairing on older TVs.
 */
final class Nacl {

    private Nacl() {
    }

    private static final long[] GF0 = new long[16];
    private static final long[] GF1 = {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final long[] K121665 = {0xDB41, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final long[] D = {0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070,
            0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203};
    private static final long[] D2 = {0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0,
            0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406};
    private static final long[] X = {0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c,
            0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169};
    private static final long[] Y = {0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666,
            0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666};
    private static final long[] I = {0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43,
            0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83};
    private static final long[] L = {0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2,
            0xde, 0xf9, 0xde, 0x14, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10};
    private static final byte[] NINE = new byte[32];

    static {
        NINE[0] = 9;
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    // ------------------------------------------------------------ X25519

    /** X25519 public key for a 32-byte private key. */
    static byte[] x25519Public(byte[] priv) {
        return x25519(priv, NINE);
    }

    /** X25519 shared secret. */
    static byte[] x25519(byte[] priv, byte[] pub) {
        byte[] z = new byte[32];
        System.arraycopy(priv, 0, z, 0, 31);
        z[31] = (byte) ((priv[31] & 127) | 64);
        z[0] &= (byte) 248;
        long[] x = new long[16];
        unpack25519(x, pub);
        long[] a = new long[16], b = new long[16], c = new long[16], d = new long[16];
        long[] e = new long[16], f = new long[16];
        for (int i = 0; i < 16; i++) b[i] = x[i];
        a[0] = d[0] = 1;
        for (int i = 254; i >= 0; --i) {
            int r = ((z[i >>> 3] & 0xff) >>> (i & 7)) & 1;
            sel25519(a, b, r);
            sel25519(c, d, r);
            A(e, a, c);
            Z(a, a, c);
            A(c, b, d);
            Z(b, b, d);
            S(d, e);
            S(f, a);
            M(a, c, a);
            M(c, b, e);
            A(e, a, c);
            Z(a, a, c);
            S(b, a);
            Z(c, d, f);
            M(a, c, K121665);
            A(a, a, d);
            M(c, c, a);
            M(a, d, f);
            M(d, b, x);
            S(b, e);
            sel25519(a, b, r);
            sel25519(c, d, r);
        }
        inv25519(c, c);
        M(a, a, c);
        byte[] q = new byte[32];
        pack25519(q, a);
        return q;
    }

    // ------------------------------------------------------------ Ed25519

    /** Ed25519 public key for a 32-byte seed (the "private key" in raw form). */
    static byte[] ed25519Public(byte[] seed) {
        byte[] d = sha512(seed, null, null);
        clamp(d);
        long[][] p = point();
        scalarbase(p, d);
        byte[] pk = new byte[32];
        pack(pk, p);
        return pk;
    }

    /** Ed25519 signature (64 bytes) of {@code msg} with a 32-byte seed. */
    static byte[] ed25519Sign(byte[] seed, byte[] msg) {
        byte[] d = sha512(seed, null, null);
        clamp(d);
        byte[] pk = ed25519Public(seed);

        byte[] prefix = new byte[32];
        System.arraycopy(d, 32, prefix, 0, 32);
        byte[] r = sha512(prefix, msg, null);
        reduce(r);
        long[][] p = point();
        scalarbase(p, r);
        byte[] sig = new byte[64];
        pack(sig, p);

        byte[] rEnc = new byte[32];
        System.arraycopy(sig, 0, rEnc, 0, 32);
        byte[] h = sha512(rEnc, pk, msg);
        reduce(h);

        long[] x = new long[64];
        for (int i = 0; i < 32; i++) x[i] = r[i] & 0xff;
        for (int i = 0; i < 32; i++)
            for (int j = 0; j < 32; j++) x[i + j] += (h[i] & 0xff) * (long) (d[j] & 0xff);
        byte[] s = new byte[32];
        modL(s, 0, x);
        System.arraycopy(s, 0, sig, 32, 32);
        return sig;
    }

    /** True if {@code sig} is a valid Ed25519 signature of {@code msg} by {@code pk}. */
    static boolean ed25519Verify(byte[] pk, byte[] msg, byte[] sig) {
        if (pk == null || pk.length != 32 || sig == null || sig.length != 64) return false;
        long[][] q = point();
        if (unpackneg(q, pk) != 0) return false;
        byte[] rEnc = new byte[32];
        System.arraycopy(sig, 0, rEnc, 0, 32);
        byte[] h = sha512(rEnc, pk, msg);
        reduce(h);
        long[][] p = point();
        scalarmult(p, q, h);
        byte[] s = new byte[32];
        System.arraycopy(sig, 32, s, 0, 32);
        long[][] q2 = point();
        scalarbase(q2, s);
        add(p, q2);
        byte[] t = new byte[32];
        pack(t, p);
        int diff = 0;
        for (int i = 0; i < 32; i++) diff |= (t[i] ^ sig[i]);
        return diff == 0;
    }

    private static void clamp(byte[] d) {
        d[0] &= (byte) 248;
        d[31] &= 127;
        d[31] |= 64;
    }

    static byte[] sha512(byte[] a, byte[] b, byte[] c) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            if (a != null) md.update(a);
            if (b != null) md.update(b);
            if (c != null) md.update(c);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long[][] point() {
        return new long[4][16];
    }

    private static void add(long[][] p, long[][] q) {
        long[] a = new long[16], b = new long[16], c = new long[16], d = new long[16], t = new long[16];
        long[] e = new long[16], f = new long[16], g = new long[16], h = new long[16];
        Z(a, p[1], p[0]);
        Z(t, q[1], q[0]);
        M(a, a, t);
        A(b, p[0], p[1]);
        A(t, q[0], q[1]);
        M(b, b, t);
        M(c, p[3], q[3]);
        M(c, c, D2);
        M(d, p[2], q[2]);
        A(d, d, d);
        Z(e, b, a);
        Z(f, d, c);
        A(g, d, c);
        A(h, b, a);
        M(p[0], e, f);
        M(p[1], h, g);
        M(p[2], g, f);
        M(p[3], e, h);
    }

    private static void cswap(long[][] p, long[][] q, int b) {
        for (int i = 0; i < 4; i++) sel25519(p[i], q[i], b);
    }

    private static void pack(byte[] r, long[][] p) {
        long[] tx = new long[16], ty = new long[16], zi = new long[16];
        inv25519(zi, p[2]);
        M(tx, p[0], zi);
        M(ty, p[1], zi);
        pack25519(r, ty);
        r[31] ^= (byte) (par25519(tx) << 7);
    }

    private static void scalarmult(long[][] p, long[][] q, byte[] s) {
        set25519(p[0], GF0);
        set25519(p[1], GF1);
        set25519(p[2], GF1);
        set25519(p[3], GF0);
        for (int i = 255; i >= 0; --i) {
            int b = ((s[i >>> 3] & 0xff) >>> (i & 7)) & 1;
            cswap(p, q, b);
            add(q, p);
            add(p, p);
            cswap(p, q, b);
        }
    }

    private static void scalarbase(long[][] p, byte[] s) {
        long[][] q = point();
        set25519(q[0], X);
        set25519(q[1], Y);
        set25519(q[2], GF1);
        M(q[3], X, Y);
        scalarmult(p, q, s);
    }

    private static void modL(byte[] r, int off, long[] x) {
        long carry;
        int i, j;
        for (i = 63; i >= 32; --i) {
            carry = 0;
            for (j = i - 32; j < i - 12; ++j) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)];
                carry = (x[j] + 128) >> 8;
                x[j] -= carry << 8;
            }
            x[j] += carry;
            x[i] = 0;
        }
        carry = 0;
        for (j = 0; j < 32; j++) {
            x[j] += carry - (x[31] >> 4) * L[j];
            carry = x[j] >> 8;
            x[j] &= 255;
        }
        for (j = 0; j < 32; j++) x[j] -= carry * L[j];
        for (i = 0; i < 32; i++) {
            x[i + 1] += x[i] >> 8;
            r[off + i] = (byte) (x[i] & 255);
        }
    }

    /** Reduce a 64-byte hash mod L, in place (result in the first 32 bytes). */
    private static void reduce(byte[] r) {
        long[] x = new long[64];
        for (int i = 0; i < 64; i++) x[i] = r[i] & 0xff;
        for (int i = 0; i < 64; i++) r[i] = 0;
        modL(r, 0, x);
    }

    private static int unpackneg(long[][] r, byte[] p) {
        long[] t = new long[16], chk = new long[16], num = new long[16], den = new long[16];
        long[] den2 = new long[16], den4 = new long[16], den6 = new long[16];
        set25519(r[2], GF1);
        unpack25519(r[1], p);
        S(num, r[1]);
        M(den, num, D);
        Z(num, num, r[2]);
        A(den, r[2], den);
        S(den2, den);
        S(den4, den2);
        M(den6, den4, den2);
        M(t, den6, num);
        M(t, t, den);
        pow2523(t, t);
        M(t, t, num);
        M(t, t, den);
        M(t, t, den);
        M(r[0], t, den);
        S(chk, r[0]);
        M(chk, chk, den);
        if (neq25519(chk, num)) M(r[0], r[0], I);
        S(chk, r[0]);
        M(chk, chk, den);
        if (neq25519(chk, num)) return -1;
        if (par25519(r[0]) == ((p[31] & 0xff) >>> 7)) Z(r[0], GF0, r[0]);
        M(r[3], r[0], r[1]);
        return 0;
    }

    // ------------------------------------------------------------ field arithmetic

    private static void set25519(long[] r, long[] a) {
        System.arraycopy(a, 0, r, 0, 16);
    }

    private static void car25519(long[] o) {
        for (int i = 0; i < 16; i++) {
            o[i] += (1L << 16);
            long c = o[i] >> 16;
            if (i < 15) o[i + 1] += c - 1;
            else o[0] += 38 * (c - 1);   // c-1 + 37*(c-1)
            o[i] -= c << 16;
        }
    }

    private static void sel25519(long[] p, long[] q, int b) {
        long c = ~(b - 1L);
        for (int i = 0; i < 16; i++) {
            long t = c & (p[i] ^ q[i]);
            p[i] ^= t;
            q[i] ^= t;
        }
    }

    private static void pack25519(byte[] o, long[] n) {
        long[] m = new long[16], t = new long[16];
        System.arraycopy(n, 0, t, 0, 16);
        car25519(t);
        car25519(t);
        car25519(t);
        for (int j = 0; j < 2; j++) {
            m[0] = t[0] - 0xffed;
            for (int i = 1; i < 15; i++) {
                m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
                m[i - 1] &= 0xffff;
            }
            m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
            int b = (int) ((m[15] >> 16) & 1);
            m[14] &= 0xffff;
            sel25519(t, m, 1 - b);
        }
        for (int i = 0; i < 16; i++) {
            o[2 * i] = (byte) (t[i] & 0xff);
            o[2 * i + 1] = (byte) ((t[i] >> 8) & 0xff);
        }
    }

    private static boolean neq25519(long[] a, long[] b) {
        byte[] c = new byte[32], d = new byte[32];
        pack25519(c, a);
        pack25519(d, b);
        int diff = 0;
        for (int i = 0; i < 32; i++) diff |= (c[i] ^ d[i]);
        return diff != 0;
    }

    private static int par25519(long[] a) {
        byte[] d = new byte[32];
        pack25519(d, a);
        return d[0] & 1;
    }

    private static void unpack25519(long[] o, byte[] n) {
        for (int i = 0; i < 16; i++) o[i] = (n[2 * i] & 0xff) + ((long) (n[2 * i + 1] & 0xff) << 8);
        o[15] &= 0x7fff;
    }

    private static void A(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) o[i] = a[i] + b[i];
    }

    private static void Z(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) o[i] = a[i] - b[i];
    }

    private static void M(long[] o, long[] a, long[] b) {
        long[] t = new long[31];
        for (int i = 0; i < 16; i++)
            for (int j = 0; j < 16; j++) t[i + j] += a[i] * b[j];
        for (int i = 0; i < 15; i++) t[i] += 38 * t[i + 16];
        System.arraycopy(t, 0, o, 0, 16);
        car25519(o);
        car25519(o);
    }

    private static void S(long[] o, long[] a) {
        M(o, a, a);
    }

    private static void inv25519(long[] o, long[] in) {
        long[] c = new long[16];
        System.arraycopy(in, 0, c, 0, 16);
        for (int a = 253; a >= 0; a--) {
            S(c, c);
            if (a != 2 && a != 4) M(c, c, in);
        }
        System.arraycopy(c, 0, o, 0, 16);
    }

    private static void pow2523(long[] o, long[] in) {
        long[] c = new long[16];
        System.arraycopy(in, 0, c, 0, 16);
        for (int a = 250; a >= 0; a--) {
            S(c, c);
            if (a != 1) M(c, c, in);
        }
        System.arraycopy(c, 0, o, 0, 16);
    }
}
