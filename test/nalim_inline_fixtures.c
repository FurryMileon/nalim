// Compiler-generated fixtures for the extended auto-inline decoder. Each
// function is a small leaf mirrored bit-exactly in InlineFixturesTest.java.
//
// Built twice with different symbol suffixes:
//   gcc -O2 -shared -fPIC -fno-stack-protector -DFXSUF=_base -o libnalim_fx_base.so nalim_inline_fixtures.c
//   gcc -O2 -march=x86-64-v3 -ffp-contract=off -shared -fPIC -fno-stack-protector -DFXSUF=_v3 -o libnalim_fx_v3.so nalim_inline_fixtures.c
//
// The v3 build exercises VEX scalar/packed AVX2, FMA and BMI2 encodings; the
// base build exercises SSE2 + legacy integer encodings of the same bodies.

#include <stdint.h>

#define CAT2(a, b) a##b
#define CAT(a, b) CAT2(a, b)
#define FX(name) CAT(name, FXSUF)

// Loop with a backward branch (and, at -O2, alignment padding inside the body).
int64_t FX(fx_sum_to)(int32_t n) {
    int64_t s = 0;
    for (int64_t i = 0; i < n; i++) s += i;
    return s;
}

// Branchy select; multiplies differ so the compiler prefers real branches
// (forward Jcc + jmp join) over cmov at least in one of the two builds.
int32_t FX(fx_select)(int32_t c, int32_t a, int32_t b) {
    if (c > 0) {
        return a * 3 + 7;
    }
    return b ^ 0x55;
}

// popcount/lzcnt/tzcnt (v3) or bit-trick/bsr/bsf fallbacks (base).
int32_t FX(fx_popcount)(uint64_t x) {
    return __builtin_popcountll(x);
}

int32_t FX(fx_clz)(uint64_t x) {
    return x == 0 ? 64 : __builtin_clzll(x);
}

int32_t FX(fx_ctz)(uint64_t x) {
    return x == 0 ? 64 : __builtin_ctzll(x);
}

// floor/ceil: roundsd (SSE4.1/AVX) in v3; libm call or branchy SSE2 in base
// (the base variant may legitimately stay on the stub path).
double FX(fx_floor)(double x) {
    return __builtin_floor(x);
}

// Scalar float chain: v3 emits VEX (vsubss/vmulss/vaddss, possibly vfmadd).
float FX(fx_lerp)(float a, float b, float t) {
    return a + (b - a) * t;
}

// Polynomial with constants: RIP-relative literal loads (+ FMA in v3).
double FX(fx_horner)(double x) {
    return ((1.25 * x + 2.5) * x - 0.75) * x + 42.0;
}

// Variable rotate/shift: v3 uses BMI2 (shlx/shrx/rorx-style codegen).
uint64_t FX(fx_rotl)(uint64_t x, int32_t r) {
    return (x << (r & 63)) | (x >> (-r & 63));
}

uint64_t FX(fx_shift_mix)(uint64_t x, int32_t s) {
    return (x >> (s & 63)) ^ (x << ((s * 7) & 63));
}

// SplitMix64 mixer — multiply/xor/shift integer core.
uint64_t FX(fx_mix64)(uint64_t z) {
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
    return z ^ (z >> 31);
}

// 16-bit loads/stores (66-prefixed ops).
int32_t FX(fx_u16_sum3)(const uint16_t* p) {
    return (int32_t) p[0] + p[1] + p[2];
}

void FX(fx_u16_store)(uint16_t* p, int32_t v) {
    p[0] = (uint16_t) v;
    p[1] = (uint16_t) (v >> 16);
}

// Fixed-size dot product: v3 vectorizes with 128/256-bit VEX ops without a loop.
double FX(fx_dot4)(const double* a, const double* b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
}

// Multiply four doubles by a constant vector: the v3 build materializes a
// 32-byte RIP-relative literal (tests the 32B pool relocation).
void FX(fx_scale4)(double* out, const double* in) {
    out[0] = in[0] * 2.0;
    out[1] = in[1] * 3.0;
    out[2] = in[2] * 5.0;
    out[3] = in[3] * 7.0;
}

// Small byte loop — memset-like without calling memset.
void FX(fx_fill_bytes)(uint8_t* p, int32_t n, int32_t v) {
    for (int32_t i = 0; i < n; i++) p[i] = (uint8_t) (v + i);
}

// Narrow return + branches: exercises return-normalization interplay.
int8_t FX(fx_sign8)(int64_t x) {
    if (x > 0) return 1;
    if (x < 0) return -1;
    return 0;
}
