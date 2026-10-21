package works.uncommon.home;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** HomeKit-style TLV8: values over 255 bytes are split and joined back on read. */
final class Tlv8 {

    static final int METHOD = 0x00, IDENTIFIER = 0x01, SALT = 0x02, PUBLIC_KEY = 0x03, PROOF = 0x04,
            ENCRYPTED_DATA = 0x05, SEQ_NO = 0x06, ERROR = 0x07, BACK_OFF = 0x08, SIGNATURE = 0x0A,
            NAME = 0x11;

    private Tlv8() {
    }

    static byte[] write(Map<Integer, byte[]> items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<Integer, byte[]> e : items.entrySet()) {
            byte[] v = e.getValue();
            int pos = 0;
            if (v.length == 0) {
                out.write(e.getKey());
                out.write(0);
            }
            while (pos < v.length) {
                int n = Math.min(255, v.length - pos);
                out.write(e.getKey());
                out.write(n);
                out.write(v, pos, n);
                pos += n;
            }
        }
        return out.toByteArray();
    }

    static Map<Integer, byte[]> read(byte[] data) {
        Map<Integer, byte[]> out = new LinkedHashMap<Integer, byte[]>();
        int pos = 0;
        while (pos + 1 < data.length) {
            int tag = data[pos] & 0xff;
            int len = data[pos + 1] & 0xff;
            int n = Math.min(len, data.length - pos - 2);
            byte[] v = new byte[n];
            System.arraycopy(data, pos + 2, v, 0, n);
            byte[] prev = out.get(tag);
            if (prev != null) {
                byte[] joined = new byte[prev.length + n];
                System.arraycopy(prev, 0, joined, 0, prev.length);
                System.arraycopy(v, 0, joined, prev.length, n);
                v = joined;
            }
            out.put(tag, v);
            pos += 2 + len;
        }
        return out;
    }

    /** Builds a TLV from alternating tag, value pairs. */
    static byte[] of(Object... kv) {
        Map<Integer, byte[]> m = new LinkedHashMap<Integer, byte[]>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((Integer) kv[i], (byte[]) kv[i + 1]);
        return write(m);
    }
}
