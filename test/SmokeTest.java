import one.nalim.Code;
import one.nalim.Link;
import one.nalim.Linker;

public class SmokeTest {

    @Link
    public static native int getuid();

    @Link
    public static native int getpid();

    @Link
    public static native int getppid();

    // rdtsc
    // shl $0x20, %rdx
    // or  %rdx, %rax
    // ret
    @Code("0f31 48c1e220 4809d0 c3")
    public static native long rdtsc();

    public static void main(String[] args) throws Exception {
        System.out.println("java.version = " + System.getProperty("java.version"));
        System.out.println("java.vm.version = " + System.getProperty("java.vm.version"));

        Linker.linkClass(SmokeTest.class);

        System.out.println("--- @Link path ---");
        int uid = getuid();
        int pid = getpid();
        int ppid = getppid();
        System.out.println("uid  = " + uid);
        System.out.println("pid  = " + pid);
        System.out.println("ppid = " + ppid);
        if (uid < 0)            throw new AssertionError("uid invalid");
        if (pid <= 0)           throw new AssertionError("pid invalid");
        if (ppid <= 0)          throw new AssertionError("ppid invalid");
        long jpid = ProcessHandle.current().pid();
        if (pid != jpid)        throw new AssertionError("pid mismatch java=" + jpid + " native=" + pid);

        System.out.println("--- @Code path ---");
        long t1 = rdtsc();
        long t2 = rdtsc();
        long t3 = rdtsc();
        System.out.println("rdtsc samples: " + t1 + " " + t2 + " " + t3);
        if (t2 < t1 || t3 < t2) throw new AssertionError("rdtsc non-monotonic");
        if (t3 - t1 > 100_000_000L) throw new AssertionError("rdtsc gap suspiciously huge");

        System.out.println("--- micro-bench (1M calls) ---");
        long start = System.nanoTime();
        for (int i = 0; i < 1_000_000; i++) rdtsc();
        long elapsed = System.nanoTime() - start;
        System.out.printf("rdtsc x 1_000_000 = %d ns (%.2f ns/call)%n", elapsed, elapsed / 1_000_000.0);

        start = System.nanoTime();
        for (int i = 0; i < 1_000_000; i++) getuid();
        elapsed = System.nanoTime() - start;
        System.out.printf("getuid x 1_000_000 = %d ns (%.2f ns/call)%n", elapsed, elapsed / 1_000_000.0);

        System.out.println("=== PASS ===");
    }
}
