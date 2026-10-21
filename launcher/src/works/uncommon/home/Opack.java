package works.uncommon.home;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apple's OPACK serialisation, used for Companion messages. Mirrors pyatv's
 * implementation: integers decode as Long, dicts as LinkedHashMap, lists as
 * ArrayList, data as byte[]. The encoder never emits back-references (Apple TVs
 * accept that); the decoder resolves them.
 */
final class Opack {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Opack() {
    }

    /** Builds an ordered map from alternating key, value pairs. */
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------ pack

    static byte[] pack(Object o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pack(o, out);
        return out.toByteArray();
    }

    private static void pack(Object o, ByteArrayOutputStream out) {
        if (o == null) {
            out.write(0x04);
        } else if (o instanceof Boolean) {
            out.write(((Boolean) o) ? 0x01 : 0x02);
        } else if (o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
            long v = ((Number) o).longValue();
            if (v >= 0 && v < 0x28) {
                out.write((int) v + 8);
            } else if (v >= 0 && v <= 0xFF) {
                out.write(0x30);
                le(out, v, 1);
            } else if (v >= 0 && v <= 0xFFFF) {
                out.write(0x31);
                le(out, v, 2);
            } else if (v >= 0 && v <= 0xFFFFFFFFL) {
                out.write(0x32);
                le(out, v, 4);
            } else {
                out.write(0x33);
                le(out, v, 8);
            }
        } else if (o instanceof Double || o instanceof Float) {
            out.write(0x36);
            le(out, Double.doubleToLongBits(((Number) o).doubleValue()), 8);
        } else if (o instanceof String) {
            byte[] b = ((String) o).getBytes(UTF8);
            if (b.length <= 0x20) {
                out.write(0x40 + b.length);
            } else if (b.length <= 0xFF) {
                out.write(0x61);
                le(out, b.length, 1);
            } else if (b.length <= 0xFFFF) {
                out.write(0x62);
                le(out, b.length, 2);
            } else if (b.length <= 0xFFFFFF) {
                out.write(0x63);
                le(out, b.length, 3);
            } else {
                out.write(0x64);
                le(out, b.length, 4);
            }
            out.write(b, 0, b.length);
        } else if (o instanceof Uuid) {
            out.write(0x05);
            out.write(((Uuid) o).bytes, 0, 16);
        } else if (o instanceof byte[]) {
            byte[] b = (byte[]) o;
            if (b.length <= 0x20) {
                out.write(0x70 + b.length);
            } else if (b.length <= 0xFF) {
                out.write(0x91);
                le(out, b.length, 1);
            } else if (b.length <= 0xFFFF) {
                out.write(0x92);
                le(out, b.length, 2);
            } else {
                out.write(0x93);
                le(out, b.length, 4);
            }
            out.write(b, 0, b.length);
        } else if (o instanceof List) {
            List<?> l = (List<?>) o;
            out.write(0xD0 + Math.min(l.size(), 0xF));
            for (Object x : l) pack(x, out);
            if (l.size() >= 0xF) out.write(0x03);
        } else if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            out.write(0xE0 + Math.min(m.size(), 0xF));
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pack(e.getKey(), out);
                pack(e.getValue(), out);
            }
            if (m.size() >= 0xF) out.write(0x03);
        } else {
            throw new IllegalArgumentException("can't pack " + o.getClass());
        }
    }

    private static void le(ByteArrayOutputStream out, long v, int n) {
        for (int i = 0; i < n; i++) out.write((int) (v >>> (8 * i)) & 0xff);
    }

    // ------------------------------------------------------------ unpack

    static Object unpack(byte[] data) {
        int[] pos = {0};
        return unpack(data, pos, new ArrayList<Object>());
    }

    private static long rdLe(byte[] d, int off, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v |= (long) (d[off + i] & 0xff) << (8 * i);
        return v;
    }

    private static Object unpack(byte[] d, int[] pos, List<Object> objects) {
        int p = pos[0];
        int t = d[p] & 0xff;
        Object value;
        boolean remember = true;
        if (t == 0x01) {
            value = Boolean.TRUE;
            p += 1;
            remember = false;
        } else if (t == 0x02) {
            value = Boolean.FALSE;
            p += 1;
            remember = false;
        } else if (t == 0x04) {
            value = null;
            p += 1;
            remember = false;
        } else if (t == 0x05) {
            value = new Uuid(Arrays.copyOfRange(d, p + 1, p + 17));
            p += 17;
        } else if (t == 0x06) {
            value = rdLe(d, p + 1, 8);                          // absolute time, as an integer
            p += 9;
        } else if (t >= 0x08 && t <= 0x2F) {
            value = (long) (t - 8);
            p += 1;
            remember = false;
        } else if (t == 0x35) {
            value = (double) Float.intBitsToFloat((int) rdLe(d, p + 1, 4));
            p += 5;
        } else if (t == 0x36) {
            value = Double.longBitsToDouble(rdLe(d, p + 1, 8));
            p += 9;
        } else if ((t & 0xF0) == 0x30) {
            int n = 1 << (t & 0xF);
            value = rdLe(d, p + 1, n);
            p += 1 + n;
        } else if (t >= 0x40 && t <= 0x60) {
            int len = t - 0x40;
            value = new String(d, p + 1, len, UTF8);
            p += 1 + len;
        } else if (t > 0x60 && t <= 0x64) {
            int n = t & 0xF;
            int len = (int) rdLe(d, p + 1, n);
            value = new String(d, p + 1 + n, len, UTF8);
            p += 1 + n + len;
        } else if (t >= 0x70 && t <= 0x90) {
            int len = t - 0x70;
            value = Arrays.copyOfRange(d, p + 1, p + 1 + len);
            p += 1 + len;
        } else if (t >= 0x91 && t <= 0x94) {
            int n = 1 << ((t & 0xF) - 1);
            int len = (int) rdLe(d, p + 1, n);
            value = Arrays.copyOfRange(d, p + 1 + n, p + 1 + n + len);
            p += 1 + n + len;
        } else if ((t & 0xF0) == 0xD0) {
            int count = t & 0xF;
            List<Object> list = new ArrayList<Object>();
            pos[0] = p + 1;
            if (count == 0xF) {
                while ((d[pos[0]] & 0xff) != 0x03) list.add(unpack(d, pos, objects));
                pos[0]++;
            } else {
                for (int i = 0; i < count; i++) list.add(unpack(d, pos, objects));
            }
            return list;    // containers are never remembered
        } else if ((t & 0xE0) == 0xE0) {
            int count = t & 0xF;
            Map<Object, Object> map = new LinkedHashMap<Object, Object>();
            pos[0] = p + 1;
            if (count == 0xF) {
                while ((d[pos[0]] & 0xff) != 0x03) {
                    Object k = unpack(d, pos, objects);
                    map.put(k, unpack(d, pos, objects));
                }
                pos[0]++;
            } else {
                for (int i = 0; i < count; i++) {
                    Object k = unpack(d, pos, objects);
                    map.put(k, unpack(d, pos, objects));
                }
            }
            return map;
        } else if (t >= 0xA0 && t <= 0xC0) {
            value = objects.get(t - 0xA0);
            p += 1;
        } else if (t >= 0xC1 && t <= 0xC4) {
            int n = t - 0xC0;
            int idx = (int) rdLe(d, p + 1, n);
            value = objects.get(idx);
            p += 1 + n;
        } else {
            throw new IllegalArgumentException("unknown OPACK type 0x" + Integer.toHexString(t));
        }
        pos[0] = p;
        if (remember && !contains(objects, value)) objects.add(value);
        return value;
    }

    /** A UUID value: 16 bytes, but distinct from plain data when matching back-references. */
    static final class Uuid {
        final byte[] bytes;

        Uuid(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Uuid && Arrays.equals(bytes, ((Uuid) o).bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    /** Same notion of "already seen" as pyatv (Python equality). */
    private static boolean contains(List<Object> objects, Object v) {
        for (Object o : objects) {
            if (o == null ? v == null : same(o, v)) return true;
        }
        return false;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof byte[] && b instanceof byte[]) return Arrays.equals((byte[]) a, (byte[]) b);
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }
}
