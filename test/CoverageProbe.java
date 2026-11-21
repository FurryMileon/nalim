import one.nalim.Linker;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

// Decoder-coverage probe: for every symbol name on stdin-file (one per line,
// e.g. produced by `nm -D --defined-only lib.so | awk '$2=="T"{print $3}'`),
// link a never-invoked probe method and report whether the public linker
// installed an inline body. Works with any nalim.jar on the classpath, so the
// same probe measures the old and the new decoder on identical libraries.
//
// Usage: CoverageProbe <abs-lib-path> <symbols-file> [-v]
public class CoverageProbe {
    private static native long candidate();

    public static void main(String[] args) throws Exception {
        String lib = args[0];
        List<String> symbols = Files.readAllLines(Paths.get(args[1]));
        boolean verbose = args.length > 2 && args[2].equals("-v");

        Linker.loadLibrary(lib);

        Method probe = CoverageProbe.class.getDeclaredMethod("candidate");

        int accepted = 0, total = 0;
        List<String> acceptedNames = new ArrayList<>();
        for (String symbol : symbols) {
            symbol = symbol.trim();
            if (symbol.isEmpty()) continue;
            if (Linker.findAddress(symbol) == 0) continue;
            total++;
            int before = Linker.getInlinedMethodCount();
            Linker.linkMethod(probe, symbol, true);
            if (Linker.getInlinedMethodCount() != before) {
                accepted++;
                acceptedNames.add(symbol);
            }
        }
        System.out.println("INLINE-ACCEPTED " + accepted + " / " + total);
        if (verbose) {
            for (String s : acceptedNames) System.out.println("  " + s);
        }
    }
}
