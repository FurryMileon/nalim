import one.nalim.Link;
import one.nalim.Linker;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.SplittableRandom;

import static java.lang.foreign.ValueLayout.*;

// Probes signatures that need native STACK arguments (7+ integer-class or
// 9+ FP parameters). HotSpot's compiled Java convention and System V both
// spill the same argument set in the same order starting at the callee's
// [rsp+8], so with Nalim's tail-jump these may line up — or corrupt args.
// Verified against Panama and a Java mirror; the result decides whether
// Nalim keeps accepting such signatures or must reject them.
public class StackArgsTest {

    @Link static native long   take7(long a, long b, long c, long d, long e, long f, long g);
    @Link static native long   take8(long a, long b, long c, long d, long e, long f, long g, long h);
    @Link static native double take9d(double a, double b, double c, double d, double e,
                                      double f, double g, double h, double i);
    @Link static native double mixed10(int a, double b, long c, double d, int e,
                                       double f, long g, double h, int i, double j);

    public static void main(String[] args) throws Throwable {
        System.out.println("java.version = " + System.getProperty("java.version")
                + " noinline=" + Boolean.getBoolean("nalim.noinline"));
        Linker.loadLibrary("stack_args");
        Linker.linkClass(StackArgsTest.class);

        var ln = java.lang.foreign.Linker.nativeLinker();
        var lookup = SymbolLookup.loaderLookup();
        MethodHandle p7 = ln.downcallHandle(lookup.find("take7").orElseThrow(),
                FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        MethodHandle p8 = ln.downcallHandle(lookup.find("take8").orElseThrow(),
                FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        MethodHandle p9d = ln.downcallHandle(lookup.find("take9d").orElseThrow(),
                FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
        MethodHandle pm = ln.downcallHandle(lookup.find("mixed10").orElseThrow(),
                FunctionDescriptor.of(JAVA_DOUBLE, JAVA_INT, JAVA_DOUBLE, JAVA_LONG, JAVA_DOUBLE, JAVA_INT,
                        JAVA_DOUBLE, JAVA_LONG, JAVA_DOUBLE, JAVA_INT, JAVA_DOUBLE));

        SplittableRandom r = new SplittableRandom(7);
        int iters = Integer.getInteger("iters", 500_000);
        int bad7 = 0, bad8 = 0, bad9 = 0, badM = 0;
        for (int i = 0; i < iters; i++) {
            long a = r.nextLong(1000), b = r.nextLong(1000), c = r.nextLong(1000), d = r.nextLong(1000),
                 e = r.nextLong(1000), f = r.nextLong(1000), g = r.nextLong(1000), h = r.nextLong(1000);
            long ref7 = a*3 + b*5 + c*7 + d*11 + e*13 + f*17 + g*19;
            long n7 = take7(a, b, c, d, e, f, g);
            long q7 = (long) p7.invokeExact(a, b, c, d, e, f, g);
            if (n7 != ref7 || q7 != ref7) {
                if (bad7++ < 3) System.out.println("take7: nalim=" + n7 + " panama=" + q7 + " ref=" + ref7);
            }
            long ref8 = ref7 + h*23;
            long n8 = take8(a, b, c, d, e, f, g, h);
            long q8 = (long) p8.invokeExact(a, b, c, d, e, f, g, h);
            if (n8 != ref8 || q8 != ref8) {
                if (bad8++ < 3) System.out.println("take8: nalim=" + n8 + " panama=" + q8 + " ref=" + ref8);
            }

            double da = r.nextDouble(), db = r.nextDouble(), dc = r.nextDouble(), dd = r.nextDouble(),
                   de = r.nextDouble(), df = r.nextDouble(), dg = r.nextDouble(), dh = r.nextDouble(), di = r.nextDouble();
            double ref9 = da*3 + db*5 + dc*7 + dd*11 + de*13 + df*17 + dg*19 + dh*23 + di*29;
            double n9 = take9d(da, db, dc, dd, de, df, dg, dh, di);
            double q9 = (double) p9d.invokeExact(da, db, dc, dd, de, df, dg, dh, di);
            if (Double.doubleToRawLongBits(n9) != Double.doubleToRawLongBits(ref9)
                    || Double.doubleToRawLongBits(q9) != Double.doubleToRawLongBits(ref9)) {
                if (bad9++ < 3) System.out.println("take9d: nalim=" + n9 + " panama=" + q9 + " ref=" + ref9);
            }

            int ia = (int) r.nextLong(1000), ie = (int) r.nextLong(1000), ii = (int) r.nextLong(1000);
            long lc = r.nextLong(1000), lg = r.nextLong(1000);
            double refM = ia*3 + db*5 + lc*7 + dd*11 + ie*13 + df*17 + lg*19 + dh*23 + ii*29 + di*31;
            double nM = mixed10(ia, db, lc, dd, ie, df, lg, dh, ii, di);
            double qM = (double) pm.invokeExact(ia, db, lc, dd, ie, df, lg, dh, ii, di);
            if (Double.doubleToRawLongBits(nM) != Double.doubleToRawLongBits(refM)
                    || Double.doubleToRawLongBits(qM) != Double.doubleToRawLongBits(refM)) {
                if (badM++ < 3) System.out.println("mixed10: nalim=" + nM + " panama=" + qM + " ref=" + refM);
            }
        }
        System.out.println("take7 bad=" + bad7 + " take8 bad=" + bad8
                + " take9d bad=" + bad9 + " mixed10 bad=" + badM);
        System.out.println(bad7 + bad8 + bad9 + badM == 0 ? "=== ALL MATCH ===" : "=== FAILURES ===");
    }
}
