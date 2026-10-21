package works.uncommon.home;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A minimal client for Apple's Companion protocol: the one the Remote in an
 * iPhone's Control Centre uses to drive an Apple TV over the network. It can
 * pair (PIN shown on the Apple TV), list installed apps, launch one, and wake
 * the Apple TV. Modelled on pyatv; blocking, so run it off the main thread.
 */
final class Companion implements Closeable {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int PS_START = 3, PS_NEXT = 4, PV_START = 5, PV_NEXT = 6;
    private static final int U_OPACK = 7, E_OPACK = 8, P_OPACK = 9;

    /** Thrown when the Apple TV refuses: wrong PIN, too many tries, or not paired any more. */
    static final class RefusedException extends IOException {
        final int code;

        RefusedException(String message, int code) {
            super(message);
            this.code = code;
        }
    }

    /** What pairing leaves us with; stored and reused for every later connection. */
    static final class Credentials {
        final byte[] ltpk;      // Apple TV's long-term public key
        final byte[] ltsk;      // our long-term Ed25519 seed
        final byte[] atvId;     // Apple TV's pairing identifier
        final byte[] clientId;  // our pairing identifier

        Credentials(byte[] ltpk, byte[] ltsk, byte[] atvId, byte[] clientId) {
            this.ltpk = ltpk;
            this.ltsk = ltsk;
            this.atvId = atvId;
            this.clientId = clientId;
        }

        /** Same "ltpk:ltsk:atv_id:client_id" hex format pyatv uses. */
        @Override
        public String toString() {
            return hex(ltpk) + ":" + hex(ltsk) + ":" + hex(atvId) + ":" + hex(clientId);
        }

        static Credentials parse(String s) {
            if (s == null) return null;
            String[] p = s.split(":", -1);
            if (p.length != 4) return null;
            try {
                return new Credentials(unhex(p[0]), unhex(p[1]), unhex(p[2]), unhex(p[3]));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;

    private byte[] outKey, inKey;
    private long outCounter, inCounter;
    private long xid = (long) (Math.random() * 65536);

    private Srp srp;
    private byte[] salt, serverPub;

    /** The last app-list reply, kept for diagnostics when it doesn't contain apps. */
    Map<Object, Object> lastAppListReply;

    Companion(String host, int port, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    void setReadTimeout(int ms) throws IOException {
        socket.setSoTimeout(ms);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------ pairing (once)

    /** Asks the Apple TV to show a PIN. */
    void pairStart() throws IOException {
        srp = new Srp();
        Map<Integer, byte[]> r = auth(PS_START, PS_NEXT, Opack.map(
                "_pd", Tlv8.of(Tlv8.METHOD, new byte[]{0}, Tlv8.SEQ_NO, new byte[]{1}),
                "_pwTy", 1));
        salt = r.get(Tlv8.SALT);
        serverPub = r.get(Tlv8.PUBLIC_KEY);
        if (salt == null || serverPub == null) throw new IOException("Apple TV didn't start pairing");
    }

    /** Finishes pairing with the PIN shown on the Apple TV. */
    Credentials pairFinish(String pin, String displayName) throws IOException {
        if (srp == null) throw new IllegalStateException("pairStart first");
        try {
            srp.process(pin, salt, serverPub);
        } catch (GeneralSecurityException e) {
            throw new IOException("pairing failed", e);
        }
        Map<Integer, byte[]> r = auth(PS_NEXT, PS_NEXT, Opack.map(
                "_pd", Tlv8.of(Tlv8.SEQ_NO, new byte[]{3}, Tlv8.PUBLIC_KEY, srp.publicKey(), Tlv8.PROOF, srp.proof()),
                "_pwTy", 1));
        if (!srp.verifyServerProof(r.get(Tlv8.PROOF))) throw new RefusedException("Apple TV's proof didn't match", 2);

        byte[] k = srp.sessionKey();
        byte[] seed = Nacl.random(32);
        byte[] pub = Nacl.ed25519Public(seed);
        byte[] clientId = UUID.randomUUID().toString().getBytes(UTF8);
        byte[] deviceX = Srp.hkdf("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", k);
        byte[] sessionKey = Srp.hkdf("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", k);
        byte[] sig = Nacl.ed25519Sign(seed, concat(deviceX, clientId, pub));
        byte[] inner = Tlv8.of(
                Tlv8.IDENTIFIER, clientId,
                Tlv8.PUBLIC_KEY, pub,
                Tlv8.SIGNATURE, sig,
                Tlv8.NAME, Opack.pack(Opack.map("name", displayName)));
        byte[] enc = ChaChaPoly.seal(sessionKey, named("PS-Msg05"), inner, null);
        r = auth(PS_NEXT, PS_NEXT, Opack.map(
                "_pd", Tlv8.of(Tlv8.SEQ_NO, new byte[]{5}, Tlv8.ENCRYPTED_DATA, enc),
                "_pwTy", 1));
        byte[] reply;
        try {
            reply = ChaChaPoly.open(sessionKey, named("PS-Msg06"), r.get(Tlv8.ENCRYPTED_DATA), null);
        } catch (GeneralSecurityException | NullPointerException e) {
            throw new IOException("couldn't read the Apple TV's reply", e);
        }
        Map<Integer, byte[]> atv = Tlv8.read(reply);
        byte[] atvId = atv.get(Tlv8.IDENTIFIER), ltpk = atv.get(Tlv8.PUBLIC_KEY);
        if (atvId == null || ltpk == null) throw new IOException("Apple TV didn't send its identity");
        // Like pyatv, the Apple TV's identity is checked on every later connection (pair-verify).
        return new Credentials(ltpk, seed, atvId, clientId);
    }

    // ------------------------------------------------------------ every connection

    /** Proves both sides are still paired, then turns on encryption. */
    void verify(Credentials c) throws IOException {
        byte[] priv = Nacl.random(32);
        byte[] pub = Nacl.x25519Public(priv);
        Map<Integer, byte[]> r = auth(PV_START, PV_NEXT, Opack.map(
                "_pd", Tlv8.of(Tlv8.SEQ_NO, new byte[]{1}, Tlv8.PUBLIC_KEY, pub),
                "_auTy", 4));
        byte[] serverKey = r.get(Tlv8.PUBLIC_KEY);
        byte[] enc = r.get(Tlv8.ENCRYPTED_DATA);
        if (serverKey == null || enc == null) throw new IOException("Apple TV didn't answer verify");
        byte[] shared = Nacl.x25519(priv, serverKey);
        byte[] sessionKey = Srp.hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared);
        Map<Integer, byte[]> atv;
        try {
            atv = Tlv8.read(ChaChaPoly.open(sessionKey, named("PV-Msg02"), enc, null));
        } catch (GeneralSecurityException e) {
            throw new IOException("couldn't read the Apple TV's verify reply", e);
        }
        byte[] id = atv.get(Tlv8.IDENTIFIER), sig = atv.get(Tlv8.SIGNATURE);
        if (id == null || !Arrays.equals(id, c.atvId)) throw new RefusedException("a different Apple TV answered", 0);
        if (!Nacl.ed25519Verify(c.ltpk, concat(serverKey, id, pub), sig)) {
            throw new RefusedException("Apple TV's signature didn't match", 0);
        }
        byte[] mine = Nacl.ed25519Sign(c.ltsk, concat(pub, c.clientId, serverKey));
        byte[] enc3 = ChaChaPoly.seal(sessionKey, named("PV-Msg03"),
                Tlv8.of(Tlv8.IDENTIFIER, c.clientId, Tlv8.SIGNATURE, mine), null);
        auth(PV_NEXT, PV_NEXT, Opack.map("_pd", Tlv8.of(Tlv8.SEQ_NO, new byte[]{3}, Tlv8.ENCRYPTED_DATA, enc3)));
        outKey = Srp.hkdf("", "ClientEncrypt-main", shared);
        inKey = Srp.hkdf("", "ServerEncrypt-main", shared);
        outCounter = 0;
        inCounter = 0;
    }

    /** The hello an iPhone remote sends after verifying; the Apple TV expects it before commands. */
    void hello(Credentials c, String rpId, String name) throws IOException {
        String pubId = "02:" + rpId.substring(0, 2) + ":" + rpId.substring(2, 4) + ":" + rpId.substring(4, 6)
                + ":" + rpId.substring(6, 8) + ":" + rpId.substring(8, 10);
        request("_systemInfo", Opack.map(
                "_bf", 0, "_cf", 512, "_clFl", 128,
                "_i", rpId,
                "_idsID", c.clientId,
                "_pubID", pubId.toUpperCase(),
                "_sf", 256, "_sv", "170.18",
                "model", "iPhone10,6",
                "name", name));
        // Same order as pyatv's connect(). tvOS won't answer some requests (the app
        // list among them) until the TV Remote session below is registered.
        optional("_touchStart", Opack.map("_height", 1000.0, "_tFl", 0, "_width", 1000.0));
        request("_sessionStart", Opack.map(
                "_srvT", "com.apple.tvremoteservices",
                "_sid", (long) (Math.random() * 4294967295.0)));
        optional("TVRCSessionStart", Opack.map("ProtocolVersionKey", "1.2"));
        optional("_tiStart", new LinkedHashMap<String, Object>());
        event("_interest", Opack.map("_regEvents", java.util.Collections.singletonList("_iMC")));
    }

    /** A request whose failure doesn't matter (older tvOS lacks some of these). */
    private void optional(String id, Map<String, Object> content) {
        int timeout = 0;
        try {
            timeout = socket.getSoTimeout();
            socket.setSoTimeout(4000);
            request(id, content);
        } catch (IOException ignored) {
        } finally {
            try {
                socket.setSoTimeout(timeout);
            } catch (IOException ignored) {
            }
        }
    }

    /** A one-way message (no reply expected). */
    void event(String id, Map<String, Object> content) throws IOException {
        send(E_OPACK, Opack.pack(Opack.map("_i", id, "_t", 1, "_c", content, "_x", xid++)));
    }

    /** Installed apps: bundle id -> name, in the order the Apple TV lists them. */
    Map<String, String> appList() throws IOException {
        // A big list on a busy Apple TV can take a while.
        int timeout = socket.getSoTimeout();
        socket.setSoTimeout(Math.max(timeout, 15000));
        Map<Object, Object> r;
        try {
            r = request("FetchLaunchableApplicationsEvent", new LinkedHashMap<String, Object>());
        } finally {
            socket.setSoTimeout(timeout);
        }
        lastAppListReply = r;
        Map<String, String> apps = new LinkedHashMap<String, String>();
        Object c = r.get("_c");
        if (c instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) c).entrySet()) {
                if (e.getKey() instanceof String && e.getValue() instanceof String) {
                    apps.put((String) e.getKey(), (String) e.getValue());
                }
            }
        }
        return apps;
    }

    /** Opens an app by bundle id (or a URL / scheme). */
    void launch(String bundleOrUrl) throws IOException {
        boolean url = bundleOrUrl.contains("://") || (bundleOrUrl.contains(":") && !bundleOrUrl.contains("."));
        request("_launchApp", Opack.map(url ? "_urlS" : "_bundleID", bundleOrUrl));
    }

    /** Wakes the Apple TV from sleep (as pyatv's turn_on does). */
    void wake() throws IOException {
        request("_hidC", Opack.map("_hBtS", 2, "_hidC", 13));
    }

    // ------------------------------------------------------------ messages

    Map<Object, Object> request(String id, Map<String, Object> content) throws IOException {
        long x = xid++;
        Map<String, Object> msg = Opack.map("_i", id, "_t", 2, "_c", content, "_x", x);
        send(E_OPACK, Opack.pack(msg));
        while (true) {
            int[] type = new int[1];
            byte[] payload = receive(type);
            if (type[0] != E_OPACK && type[0] != U_OPACK && type[0] != P_OPACK) continue;
            Object o = Opack.unpack(payload);
            if (!(o instanceof Map)) continue;
            Map<Object, Object> m = cast(o);
            if (!isLong(m.get("_t"), 3) || !isLong(m.get("_x"), x)) continue;   // events, other replies
            if (m.containsKey("_em")) throw new IOException(id + " failed: " + m.get("_em"));
            return m;
        }
    }

    /** Sends an auth frame and returns the TLV inside the reply. */
    private Map<Integer, byte[]> auth(int sendType, int replyType, Map<String, Object> msg) throws IOException {
        msg.put("_x", xid++);
        send(sendType, Opack.pack(msg));
        while (true) {
            int[] type = new int[1];
            byte[] payload = receive(type);
            if (type[0] != replyType) continue;
            Object o = Opack.unpack(payload);
            if (!(o instanceof Map)) continue;
            Object pd = cast(o).get("_pd");
            if (!(pd instanceof byte[])) throw new IOException("no pairing data in reply");
            Map<Integer, byte[]> tlv = Tlv8.read((byte[]) pd);
            byte[] err = tlv.get(Tlv8.ERROR);
            if (err != null && err.length > 0) {
                int code = err[0] & 0xff;
                String why = code == 2 ? "wrong code" : code == 3 ? "too many attempts, try again later"
                        : code == 5 ? "too many paired devices" : code == 6 ? "busy, try again" : "refused (" + code + ")";
                throw new RefusedException(why, code);
            }
            return tlv;
        }
    }

    // ------------------------------------------------------------ framing

    private void send(int type, byte[] data) throws IOException {
        int len = data.length;
        boolean encrypt = outKey != null && len > 0;
        if (encrypt) len += 16;
        byte[] header = {(byte) type, (byte) (len >>> 16), (byte) (len >>> 8), (byte) len};
        if (encrypt) data = ChaChaPoly.seal(outKey, counter(outCounter++), data, header);
        byte[] frame = new byte[4 + data.length];
        System.arraycopy(header, 0, frame, 0, 4);
        System.arraycopy(data, 0, frame, 4, data.length);
        out.write(frame);
        out.flush();
    }

    private byte[] receive(int[] typeOut) throws IOException {
        byte[] header = new byte[4];
        in.readFully(header);
        int len = (header[1] & 0xff) << 16 | (header[2] & 0xff) << 8 | (header[3] & 0xff);
        byte[] payload = new byte[len];
        in.readFully(payload);
        typeOut[0] = header[0] & 0xff;
        if (inKey != null && len > 0) {
            try {
                payload = ChaChaPoly.open(inKey, counter(inCounter++), payload, header);
            } catch (GeneralSecurityException e) {
                throw new IOException("couldn't decrypt a message from the Apple TV", e);
            }
        }
        return payload;
    }

    private static byte[] counter(long n) {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 8; i++) nonce[i] = (byte) (n >>> (8 * i));
        return nonce;
    }

    /** "PS-Msg05" etc: 8 ASCII bytes after 4 zero bytes. */
    private static byte[] named(String s) {
        byte[] nonce = new byte[12];
        byte[] b = s.getBytes(UTF8);
        System.arraycopy(b, 0, nonce, 12 - b.length, b.length);
        return nonce;
    }

    // ------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> cast(Object o) {
        return (Map<Object, Object>) o;
    }

    private static boolean isLong(Object o, long v) {
        return o instanceof Number && ((Number) o).longValue() == v;
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    static byte[] unhex(String s) {
        if (s.length() % 2 != 0) throw new IllegalArgumentException("odd hex");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
