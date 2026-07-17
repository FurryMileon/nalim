// Memory-path payloads for BenchMemAccess.
// gcc -O2 -shared -fPIC -fno-stack-protector -o libnalim_mem.so nalim_mem.c

#include <stdint.h>

int64_t mem_sum_longs(const int64_t* p, int32_t n) {
    int64_t s = 0;
    for (int32_t i = 0; i < n; i++) s += p[i];
    return s;
}

void mem_fill_longs(int64_t* p, int32_t n, int64_t v) {
    for (int32_t i = 0; i < n; i++) p[i] = v + i;
}

int64_t mem_load_long(const int64_t* p) {
    return *p;
}

void mem_store_long(int64_t* p, int64_t v) {
    *p = v;
}

// Mixed heap+native op: read from a Java heap array, accumulate into native
// memory, return running total — the "kernel touching both worlds" shape.
int64_t mem_axpy_into(int64_t* nativeAcc, const int64_t* heapSrc, int32_t n) {
    int64_t s = *nativeAcc;
    for (int32_t i = 0; i < n; i++) s += heapSrc[i] * 3;
    *nativeAcc = s;
    return s;
}
