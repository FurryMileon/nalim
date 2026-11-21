package one.nalim;

/** Unsupported targets have to fail before any machine code is selected for installation. */
public final class PlatformSelectionTest {
    public static void main(String[] args) {
        reject("ppc64le", "Linux");
        reject("loongarch64", "Linux");
        reject("s390x", "Linux");
        reject("riscv64", "Linux");
        reject("aarch64", "Windows 11");
        reject("amd64", "Unknown Unix");
        System.out.println("Unsupported native targets rejected");
    }

    private static void reject(String architecture, String os) {
        try {
            CallingConvention.forTarget(architecture, os);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("selected a machine-code emitter for " + os + " / " + architecture);
    }
}
