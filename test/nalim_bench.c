// Reproduces the canonical Pangin/Nalim README benchmark functions.
// Compile: gcc -O2 -shared -fPIC -o libnalim_bench.so nalim_bench.c
#include <stdint.h>

uint32_t raw_add(uint32_t a, uint32_t b) {
    return a + b;
}

uint32_t raw_abs(int32_t x) {
    // Java int arithmetic wraps, including abs(INT32_MIN).
    return x < 0 ? 0u - (uint32_t)x : (uint32_t)x;
}

int raw_noop(void) {
    return 42;
}

long raw_sum_to(int n) {
    long s = 0;
    for (int i = 0; i < n; i++) s += i;
    return s;
}
