// Reproduces the canonical Pangin/Nalim README benchmark functions.
// Compile: gcc -O2 -shared -fPIC -o libnalim_bench.so nalim_bench.c

int raw_add(int a, int b) {
    return a + b;
}

int raw_abs(int x) {
    return x < 0 ? -x : x;
}

int raw_noop(void) {
    return 42;
}

long raw_sum_to(int n) {
    long s = 0;
    for (int i = 0; i < n; i++) s += i;
    return s;
}
