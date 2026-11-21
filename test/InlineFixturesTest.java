import one.nalim.Link;
import one.nalim.Linker;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;

// Correctness + coverage gate for the extended auto-inline decoder (loops,
// forward jmp, VEX/AVX2/FMA/BMI, SSE2 integer core, 16-bit ops, rep string
// ops, bswap/shld/inc-dec/padding-nops). Three fixture libraries:
//   libnalim_fx_base.so — gcc -O2 (SSE2 baseline)
//   libnalim_fx_v3.so   — gcc -O2 -march=x86-64-v3 -ffp-contract=off (VEX)
//   libnalim_fx_asm.so  — hand-written exact encodings
// Every function is mirrored bit-exactly in Java over random + special
// inputs. The test also asserts WHICH fixtures took the inline path, so
// decoder regressions show up as coverage changes, not just perf drift.
public class InlineFixturesTest {

    static class Base {
        @Link(name = "fx_sum_to_base")     static native long  sumTo(int n);
        @Link(name = "fx_select_base")     static native int   select(int c, int a, int b);
        @Link(name = "fx_popcount_base")   static native int   popcount(long x);
        @Link(name = "fx_clz_base")        static native int   clz(long x);
        @Link(name = "fx_ctz_base")        static native int   ctz(long x);
        @Link(name = "fx_floor_base")      static native double floor(double x);
        @Link(name = "fx_lerp_base")       static native float lerp(float a, float b, float t);
        @Link(name = "fx_horner_base")     static native double horner(double x);
        @Link(name = "fx_rotl_base")       static native long  rotl(long x, int r);
        @Link(name = "fx_shift_mix_base")  static native long  shiftMix(long x, int s);
        @Link(name = "fx_mix64_base")      static native long  mix64(long z);
        @Link(name = "fx_u16_sum3_base")   static native int   u16sum3(char[] p);
        @Link(name = "fx_u16_store_base")  static native void  u16store(char[] p, int v);
        @Link(name = "fx_dot4_base")       static native double dot4(double[] a, double[] b);
        @Link(name = "fx_scale4_base")     static native void  scale4(double[] out, double[] in);
        @Link(name = "fx_fill_bytes_base") static native void  fillBytes(byte[] p, int n, int v);
        @Link(name = "fx_sign8_base")      static native byte  sign8(long x);
    }

    static class V3 {
        @Link(name = "fx_sum_to_v3")     static native long  sumTo(int n);
        @Link(name = "fx_select_v3")     static native int   select(int c, int a, int b);
        @Link(name = "fx_popcount_v3")   static native int   popcount(long x);
        @Link(name = "fx_clz_v3")        static native int   clz(long x);
        @Link(name = "fx_ctz_v3")        static native int   ctz(long x);
        @Link(name = "fx_floor_v3")      static native double floor(double x);
        @Link(name = "fx_lerp_v3")       static native float lerp(float a, float b, float t);
        @Link(name = "fx_horner_v3")     static native double horner(double x);
        @Link(name = "fx_rotl_v3")       static native long  rotl(long x, int r);
        @Link(name = "fx_shift_mix_v3")  static native long  shiftMix(long x, int s);
        @Link(name = "fx_mix64_v3")      static native long  mix64(long z);
        @Link(name = "fx_u16_sum3_v3")   static native int   u16sum3(char[] p);
        @Link(name = "fx_u16_store_v3")  static native void  u16store(char[] p, int v);
        @Link(name = "fx_dot4_v3")       static native double dot4(double[] a, double[] b);
        @Link(name = "fx_scale4_v3")     static native void  scale4(double[] out, double[] in);
        @Link(name = "fx_fill_bytes_v3") static native void  fillBytes(byte[] p, int n, int v);
        @Link(name = "fx_sign8_v3")      static native byte  sign8(long x);
    }

    static class Asm {
        @Link static native int fxa_forward(int x);
        @Link static native int fxa_forward_chain(int x);
        @Link static native int fxa_forward_page(int x);
        @Link static native short fxa_literal_word(int x);
        @Link static native byte fxa_classify8(int x);
        @Link static native long fxa_jmp_fwd(long x);
        @Link static native long fxa_loop_back(long n);
        @Link static native long fxa_rep_stos(long[] p, long n, long v);
        @Link static native long fxa_rep_movs(byte[] dst, byte[] src, long n);
        @Link static native long fxa_bswap(long x);
        @Link static native long fxa_shld(long x, long y);
        @Link static native long fxa_shrd_cl(long x, long y, long c);
        @Link static native long fxa_inc_dec(long x, long[] scratch);
        @Link static native int  fxa_16bit(char[] p, int v);
        @Link static native long fxa_padded_nop(long x);
        @Link static native float fxa_vex_scalar(float a, float b);
        @Link static native double fxa_fma(double a, double b, double c);
        @Link static native double fxa_vround(double x);
        @Link static native double fxa_vex_lit(double x);
        @Link static native long fxa_bmi(long x, long y);
        @Link static native long fxa_mulx(long x, long y);
        @Link static native long fxa_popcnts(long x);
        @Link static native long fxa_bsf_bsr(long x);
        @Link static native long fxa_sse2_int(long[] p2);
        @Link static native int  fxa_pmulld(int[] p4);
        @Link static native long fxa_pshufb(byte[] p16);
        @Link static native double fxa_roundsd_legacy(double x);
        @Link static native long fxa_ymm_lit(long[] p4);
        @Link static native long fxa_ymm_aligned(long[] p4);
        @Link static native byte fxa_parity8(long x, long n);
        @Link static native long fxa_two_rets(long x);
        @Link static native long fxa_cmov_set(long x, long y);
        @Link static native long fxa_endbr64(long x);
        @Link static native long fxa_endbr32(long x);
    }

    static class Edges {
        @Link static native byte fxb_rel8_limit(int x);
        @Link static native short fxb_word(int x);
        @Link static native short fxb_word_control();
        @Link static native void fxb_set_word(int value);
        @Link static native int fxb_indirect(int x);
        @Link static native void fxb_switch();
        @Link static native int fxb_long_chain(int x);
    }

    // ── link + inline coverage report ──────────────────────────────────

    static final TreeMap<String, Boolean> inlineStatus = new TreeMap<>();

    static void linkAll(Class<?> c) throws Exception {
        List<Method> methods = new ArrayList<>();
        for (Method m : c.getDeclaredMethods()) {
            if (m.getAnnotation(Link.class) != null) methods.add(m);
        }
        methods.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (Method m : methods) {
            int before = Linker.getInlinedMethodCount();
            Linker.linkMethod(m);
            boolean inlined = Linker.getInlinedMethodCount() > before;
            Link link = m.getAnnotation(Link.class);
            String symbol = link.name().isEmpty() ? m.getName() : link.name();
            inlineStatus.put(symbol, inlined);
        }
    }

    // ── Java mirrors ───────────────────────────────────────────────────

    static long mSumTo(int n) { long s = 0; for (long i = 0; i < n; i++) s += i; return s; }
    static int mSelect(int c, int a, int b) { return c > 0 ? a * 3 + 7 : b ^ 0x55; }
    static double mFloor(double x) { return Math.floor(x); }
    static float mLerp(float a, float b, float t) { return a + (b - a) * t; }
    static double mHorner(double x) { return ((1.25 * x + 2.5) * x - 0.75) * x + 42.0; }
    static long mRotl(long x, int r) { return (x << (r & 63)) | (x >>> (-r & 63)); }
    static long mShiftMix(long x, int s) { return (x >>> (s & 63)) ^ (x << ((s * 7) & 63)); }
    static long mMix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
    static byte mSign8(long x) { return (byte) (x > 0 ? 1 : x < 0 ? -1 : 0); }

    static long mBzhi(long x, long y) {
        int n = (int) (y & 0xFF);
        return n < 64 ? x & ((1L << n) - 1) : x;
    }
    static long mPdep(long x, long mask) {
        long r = 0; int b = 0;
        for (int i = 0; i < 64; i++) {
            if ((mask & (1L << i)) != 0) {
                if ((x & (1L << b)) != 0) r |= 1L << i;
                b++;
            }
        }
        return r;
    }
    static long mPext(long x, long mask) {
        long r = 0; int b = 0;
        for (int i = 0; i < 64; i++) {
            if ((mask & (1L << i)) != 0) {
                if ((x & (1L << i)) != 0) r |= 1L << b;
                b++;
            }
        }
        return r;
    }
    static long mBmi(long x, long y) {
        long r = (~x & y);
        r ^= x << (y & 63);
        r ^= x >>> (y & 63);
        r ^= Long.rotateRight(x, 17);
        r ^= x & (x - 1);
        r ^= y & -y;
        r ^= x ^ (x - 1);
        r ^= mBzhi(x, y);
        r ^= mPdep(x, y);
        r ^= mPext(x, y);
        return r;
    }

    static int checked = 0;

    static void eq(long actual, long expected, String what) {
        checked++;
        if (actual != expected) {
            throw new AssertionError(what + ": got " + actual + " expected " + expected
                    + " (0x" + Long.toHexString(actual) + " vs 0x" + Long.toHexString(expected) + ")");
        }
    }

    static void eqD(double actual, double expected, String what) {
        checked++;
        if (Double.doubleToLongBits(actual) != Double.doubleToLongBits(expected)) {
            throw new AssertionError(what + ": got " + actual + " expected " + expected);
        }
    }

    static void eqF(float actual, float expected, String what) {
        checked++;
        if (Float.floatToIntBits(actual) != Float.floatToIntBits(expected)) {
            throw new AssertionError(what + ": got " + actual + " expected " + expected);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("java.version = " + System.getProperty("java.version"));
        Linker.loadLibrary("nalim_fx_base");
        Linker.loadLibrary("nalim_fx_v3");
        Linker.loadLibrary("nalim_fx_asm");
        linkAll(Base.class);
        linkAll(V3.class);
        linkAll(Asm.class);

        System.out.println("--- inline coverage ---");
        for (var e : inlineStatus.entrySet()) {
            System.out.printf("  %-24s %s%n", e.getKey(), e.getValue() ? "INLINE" : "stub");
        }

        SplittableRandom rnd = new SplittableRandom(42);
        for (int x : new int[] { Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE }) {
            eq(Asm.fxa_forward(x), x + 17, "forwarded RIP-relative leaf");
            eq(Asm.fxa_forward_chain(x), x + 17, "chained entry thunks");
            eq(Asm.fxa_forward_page(x), x + 5, "forwarded leaf across readable pages");
            eq(Asm.fxa_literal_word(x), x == 0 ? (short) 0x9980 : -32768, "rel32 edges with relocated literal");
            eq(Asm.fxa_classify8(x), x < 0 ? -128 : x == 0 ? 0 : 1, "narrow branch exit");
        }
        linkAll(Edges.class);
        for (int x : new int[] { -1, 0, 1, 2 }) {
            eq(Edges.fxb_rel8_limit(x), x < 0 ? 98 : x == 0 ? 0 : x == 1 ? -128 : 127, "rel8 limit");
        }
        eq(Edges.fxb_word(1), -32768, "rel32 fallthrough");
        eq(Edges.fxb_word(0), 0x7abc, "mutable RIP load");
        eq(Edges.fxb_word_control(), 0x7abc, "native rebase reachability control");
        if (inlineStatus.get("fxb_word_control") && !inlineStatus.get("fxb_word")) {
            throw new AssertionError("reachable live RIP operand dropped the rel32 leaf to the bridge");
        }
        Edges.fxb_set_word(0xcafe8003);
        eq(Edges.fxb_word(0), -32765, "relocated load after native write");
        eq(Edges.fxb_indirect(25), 42, "indirect entry before update");
        Edges.fxb_switch();
        eq(Edges.fxb_indirect(25), 8, "indirect entry after update");
        eq(Edges.fxb_long_chain(41), 42, "bounded thunk-chain fallback");
        for (String symbol : new String[] { "fxb_rel8_limit", "fxb_indirect", "fxb_long_chain" }) {
            if (inlineStatus.get(symbol)) throw new AssertionError("expected fallback: " + symbol);
        }

        // ── C fixtures, both builds ─────────────────────────────────────
        int[] ns = {0, 1, 2, 3, 7, 100, 1000, -5};
        for (int n : ns) {
            eq(Base.sumTo(n), mSumTo(n), "base sumTo(" + n + ")");
            eq(V3.sumTo(n), mSumTo(n), "v3 sumTo(" + n + ")");
        }
        for (int i = 0; i < 100_000; i++) {
            int c = rnd.nextInt(-2, 3), a = rnd.nextInt(), b = rnd.nextInt();
            eq(Base.select(c, a, b), mSelect(c, a, b), "base select");
            eq(V3.select(c, a, b), mSelect(c, a, b), "v3 select");

            long x = rnd.nextLong();
            eq(Base.popcount(x), Long.bitCount(x), "base popcount");
            eq(V3.popcount(x), Long.bitCount(x), "v3 popcount");
            eq(Base.clz(x), x == 0 ? 64 : Long.numberOfLeadingZeros(x), "base clz");
            eq(V3.clz(x), x == 0 ? 64 : Long.numberOfLeadingZeros(x), "v3 clz");
            eq(Base.ctz(x), x == 0 ? 64 : Long.numberOfTrailingZeros(x), "base ctz");
            eq(V3.ctz(x), x == 0 ? 64 : Long.numberOfTrailingZeros(x), "v3 ctz");

            int r = rnd.nextInt(256) - 128;
            eq(Base.rotl(x, r), mRotl(x, r), "base rotl");
            eq(V3.rotl(x, r), mRotl(x, r), "v3 rotl");
            eq(Base.shiftMix(x, r), mShiftMix(x, r), "base shiftMix");
            eq(V3.shiftMix(x, r), mShiftMix(x, r), "v3 shiftMix");
            eq(Base.mix64(x), mMix64(x), "base mix64");
            eq(V3.mix64(x), mMix64(x), "v3 mix64");
            eq(Base.sign8(x), mSign8(x), "base sign8");
            eq(V3.sign8(x), mSign8(x), "v3 sign8");

            double d = Double.longBitsToDouble(rnd.nextLong());
            if (!Double.isNaN(d)) {
                eqD(Base.floor(d), mFloor(d), "base floor(" + d + ")");
                eqD(V3.floor(d), mFloor(d), "v3 floor(" + d + ")");
            }
            double dx = rnd.nextDouble(-1000, 1000);
            eqD(Base.horner(dx), mHorner(dx), "base horner");
            eqD(V3.horner(dx), mHorner(dx), "v3 horner");

            float fa = rnd.nextFloat() * 100 - 50, fb = rnd.nextFloat() * 100 - 50,
                  ft = rnd.nextFloat();
            eqF(Base.lerp(fa, fb, ft), mLerp(fa, fb, ft), "base lerp");
            eqF(V3.lerp(fa, fb, ft), mLerp(fa, fb, ft), "v3 lerp");
        }
        double[] specials = {0.0, -0.0, 0.5, -0.5, 1.5, -1.5,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.MAX_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE, 1e300, -1e300};
        for (double d : specials) {
            eqD(Base.floor(d), mFloor(d), "base floor special " + d);
            eqD(V3.floor(d), mFloor(d), "v3 floor special " + d);
        }

        char[] cbuf = new char[8];
        byte[] bbuf = new byte[64];
        double[] din = new double[4], dout = new double[4], db = new double[4];
        for (int i = 0; i < 20_000; i++) {
            for (int k = 0; k < 4; k++) cbuf[k] = (char) rnd.nextInt(65536);
            eq(Base.u16sum3(cbuf), cbuf[0] + cbuf[1] + cbuf[2], "base u16sum3");
            eq(V3.u16sum3(cbuf), cbuf[0] + cbuf[1] + cbuf[2], "v3 u16sum3");
            int v = rnd.nextInt();
            Base.u16store(cbuf, v);
            eq(cbuf[0], (char) v, "base u16store lo");
            eq(cbuf[1], (char) (v >>> 16), "base u16store hi");
            V3.u16store(cbuf, ~v);
            eq(cbuf[0], (char) ~v, "v3 u16store lo");
            eq(cbuf[1], (char) (~v >>> 16), "v3 u16store hi");

            for (int k = 0; k < 4; k++) {
                din[k] = rnd.nextDouble(-100, 100);
                db[k] = rnd.nextDouble(-100, 100);
            }
            eqD(Base.dot4(din, db), din[0] * db[0] + din[1] * db[1] + din[2] * db[2] + din[3] * db[3], "base dot4");
            eqD(V3.dot4(din, db), din[0] * db[0] + din[1] * db[1] + din[2] * db[2] + din[3] * db[3], "v3 dot4");

            Base.scale4(dout, din);
            eqD(dout[0], din[0] * 2.0, "base scale4[0]");
            eqD(dout[1], din[1] * 3.0, "base scale4[1]");
            eqD(dout[2], din[2] * 5.0, "base scale4[2]");
            eqD(dout[3], din[3] * 7.0, "base scale4[3]");
            V3.scale4(dout, din);
            eqD(dout[0], din[0] * 2.0, "v3 scale4[0]");
            eqD(dout[3], din[3] * 7.0, "v3 scale4[3]");

            int n = rnd.nextInt(64);
            int fv = rnd.nextInt();
            Arrays.fill(bbuf, (byte) 0);
            Base.fillBytes(bbuf, n, fv);
            for (int k = 0; k < n; k++) eq(bbuf[k], (byte) (fv + k), "base fillBytes[" + k + "]");
            Arrays.fill(bbuf, (byte) 0);
            V3.fillBytes(bbuf, n, fv);
            for (int k = 0; k < n; k++) eq(bbuf[k], (byte) (fv + k), "v3 fillBytes[" + k + "]");
        }

        // ── asm fixtures ────────────────────────────────────────────────
        long[] lbuf = new long[16];
        byte[] src = new byte[64], dst = new byte[64];
        int[] ibuf = new int[4];
        for (int i = 0; i < 100_000; i++) {
            long x = rnd.nextLong(), y = rnd.nextLong();

            eq(Asm.fxa_jmp_fwd(x), x + 1, "fxa_jmp_fwd");
            long n = rnd.nextLong(1, 50);
            eq(Asm.fxa_loop_back(n), n * (n + 1) / 2, "fxa_loop_back");
            eq(Asm.fxa_bswap(x), Long.reverseBytes(x), "fxa_bswap");
            eq(Asm.fxa_shld(x, y), (x << 13) | (y >>> 51), "fxa_shld");
            long c = rnd.nextLong(1, 64);
            eq(Asm.fxa_shrd_cl(x, y, c), (x >>> c) | (y << (64 - c)), "fxa_shrd_cl");
            eq(Asm.fxa_padded_nop(x), x * 2, "fxa_padded_nop");
            eq(Asm.fxa_endbr64(x), x + 64, "fxa_endbr64");
            eq(Asm.fxa_endbr32(x), x + 32, "fxa_endbr32");
            eq(Asm.fxa_bmi(x, y), mBmi(x, y), "fxa_bmi");
            eq(Asm.fxa_mulx(x, y), Math.unsignedMultiplyHigh(x, y) ^ (x * y), "fxa_mulx");
            eq(Asm.fxa_popcnts(x), Long.bitCount(x)
                    + ((long) Long.numberOfLeadingZeros(x) << 8)
                    + ((long) Long.numberOfTrailingZeros(x) << 16), "fxa_popcnts");
            if (x != 0) {
                eq(Asm.fxa_bsf_bsr(x), Long.numberOfTrailingZeros(x)
                        | ((long) (63 - Long.numberOfLeadingZeros(x)) << 8), "fxa_bsf_bsr");
            }
            eq(Asm.fxa_two_rets(x), x > 0 ? x * 2 : -x, "fxa_two_rets");
            eq(Asm.fxa_cmov_set(x, y), Math.max(x, y) + (x == y ? 1 : 0), "fxa_cmov_set");
            long pn = rnd.nextLong(1, 16);
            long par = 0, px = x;
            for (long k = 0; k < pn; k++) { par ^= px; px = Long.rotateRight(px, 8); }
            eq(Asm.fxa_parity8(x, pn), (byte) (par & 1), "fxa_parity8");

            // inc/dec with heap scratch
            lbuf[0] = x; lbuf[1] = y;
            long r = Asm.fxa_inc_dec(x, lbuf);
            eq(r, x + 1, "fxa_inc_dec ret");
            eq(lbuf[0], x - 1, "fxa_inc_dec [0]");
            int lo = (int) y + 1;
            eq(lbuf[1], (y & 0xFFFFFFFF00000000L) | (lo & 0xFFFFFFFFL), "fxa_inc_dec [1]");

            // 16-bit soup
            for (int k = 0; k < 8; k++) cbuf[k] = (char) rnd.nextInt(65536);
            char c0 = cbuf[0], c1 = cbuf[1];
            int v16 = rnd.nextInt();
            int expected = (char) (c0 + (v16 & 0xFFFF) + c1 + 7);
            eq(Asm.fxa_16bit(cbuf, v16), expected, "fxa_16bit ret");
            eq(cbuf[2], (char) expected, "fxa_16bit p[2]");
            eq(cbuf[3], (char) 0x1234, "fxa_16bit p[3]");

            // rep stos/movs on heap arrays
            long sn = rnd.nextLong(0, 9);
            Arrays.fill(lbuf, 0L);
            eq(Asm.fxa_rep_stos(lbuf, sn, x), sn, "fxa_rep_stos ret");
            for (int k = 0; k < 16; k++) eq(lbuf[k], k < sn ? x : 0, "fxa_rep_stos [" + k + "]");
            rnd.nextBytes(src);
            Arrays.fill(dst, (byte) 0);
            long mn = rnd.nextLong(0, 65);
            eq(Asm.fxa_rep_movs(dst, src, mn), mn, "fxa_rep_movs ret");
            for (int k = 0; k < 64; k++) eq(dst[k], k < mn ? src[k] : 0, "fxa_rep_movs [" + k + "]");

            // float/double VEX
            float fa = rnd.nextFloat() * 64 - 32, fb = rnd.nextFloat() * 64 - 32;
            eqF(Asm.fxa_vex_scalar(fa, fb), (fa + fb) * fa - fb, "fxa_vex_scalar");
            double da = rnd.nextDouble(-1e6, 1e6), db2 = rnd.nextDouble(-1e6, 1e6),
                   dc = rnd.nextDouble(-1e6, 1e6);
            eqD(Asm.fxa_fma(da, db2, dc), Math.fma(da, db2, dc), "fxa_fma");
            eqD(Asm.fxa_vround(da), Math.floor(da), "fxa_vround");
            eqD(Asm.fxa_roundsd_legacy(da), Math.ceil(da), "fxa_roundsd_legacy");
            eqD(Asm.fxa_vex_lit(da), da * 2.5 + 0.125, "fxa_vex_lit");

            // SIMD integer
            lbuf[0] = x; lbuf[1] = y;
            long lane0 = ((x * 2) << 3 ^ x) + ((y * 2) << 3 ^ y);
            eq(Asm.fxa_sse2_int(lbuf), lane0, "fxa_sse2_int");
            for (int k = 0; k < 4; k++) ibuf[k] = rnd.nextInt();
            eq(Asm.fxa_pmulld(ibuf), ibuf[0] * ibuf[1], "fxa_pmulld");
            rnd.nextBytes(src);
            long le0 = 0;
            for (int k = 7; k >= 0; k--) le0 = (le0 << 8) | (src[k] & 0xFF);
            eq(Asm.fxa_pshufb(src), Long.reverseBytes(le0), "fxa_pshufb");

            // ymm literals
            lbuf[0] = x; lbuf[1] = y; lbuf[2] = x ^ y; lbuf[3] = x + y;
            long sum = (lbuf[0] + 0x1111111111111111L) + (lbuf[1] + 0x2222222222222222L)
                     + (lbuf[2] + 0x3333333333333333L) + (lbuf[3] + 0x4444444444444444L);
            eq(Asm.fxa_ymm_lit(lbuf), sum, "fxa_ymm_lit");
            long sum2 = (lbuf[0] + 0x0102030405060708L) + (lbuf[1] + 0x1112131415161718L)
                      + (lbuf[2] + 0x2122232425262728L) + (lbuf[3] + 0x3132333435363738L);
            eq(Asm.fxa_ymm_aligned(lbuf), sum2, "fxa_ymm_aligned");
        }

        // ── coverage assertions ─────────────────────────────────────────
        // Every asm fixture must take the inline path — that's the point.
        List<String> notInlined = new ArrayList<>();
        for (var e : inlineStatus.entrySet()) {
            if (e.getKey().startsWith("fxa_") && !e.getValue()) {
                notInlined.add(e.getKey());
            }
        }
        if (!notInlined.isEmpty()) {
            throw new AssertionError("asm fixtures fell off the inline path: " + notInlined);
        }
        // C fixtures known to inline (regression watermark; extend when the
        // decoder learns more): everything except cases where gcc emits
        // encodings we deliberately keep on the stub path.
        String[] mustInline = {
                "fx_sum_to_base", "fx_sum_to_v3",
                "fx_select_base", "fx_select_v3",
                "fx_popcount_v3", "fx_clz_v3", "fx_ctz_v3",
                "fx_floor_v3", "fx_lerp_base", "fx_lerp_v3",
                "fx_horner_base", "fx_horner_v3",
                "fx_rotl_base", "fx_rotl_v3",
                "fx_shift_mix_base", "fx_shift_mix_v3",
                "fx_mix64_base", "fx_mix64_v3",
                "fx_u16_sum3_base", "fx_u16_sum3_v3",
                "fx_u16_store_base", "fx_u16_store_v3",
                "fx_dot4_base", "fx_dot4_v3",
                "fx_scale4_base", "fx_scale4_v3",
                "fx_fill_bytes_base", "fx_fill_bytes_v3",
        };
        List<String> missing = new ArrayList<>();
        for (String s : mustInline) {
            if (!Boolean.TRUE.equals(inlineStatus.get(s))) missing.add(s);
        }
        if (!missing.isEmpty()) {
            throw new AssertionError("expected INLINE for: " + missing);
        }

        System.out.println("=== PASS === (" + checked + " comparisons, "
                + Linker.getInlinedMethodCount() + " inlined)");
    }
}
