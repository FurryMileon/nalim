// Executes the Windows-x64 framed adapters emitted by WinEmitProbe on Linux.
//
// The adapter's INPUT is HotSpot's compiled-Java Windows convention:
//     ints: rdx, r8, r9, rdi, rsi, rcx, then stack ([rsp+8+8k] at entry)
//       fp: xmm0-xmm7, then stack
// Its OUTPUT is a Win64 native call, which GCC can receive natively via
// __attribute__((ms_abi)). Compare against System-V twins or the individual
// source values, including the actual field displacement from the probe VM.
//
// Build: gcc -O1 -o win_abi_harness win_abi_harness.c
// Run:   ./win_abi_harness   (in the directory with win_adapter_*.bin)

#define _GNU_SOURCE
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>

#define MAGIC 0xDEADBEEFCAFEBABEULL

// Distinct, easily-traced argument values.
const uint64_t INT_REGS[6] = {
    0x100000065, 0x200000066, 0x300000067, 0x400000068, 0x500000069, 0x60000006a
};
// xmm0-7 raw: low 32 = float(10.5+i), high 32 = float(20.5+i) -> valid doubles.
uint64_t XMM_RAW[8];
// 4 shared Java stack slots.
uint64_t SLOT_RAW[4];

static float  as_f32(uint64_t raw) { float v;  uint32_t x = (uint32_t) raw; memcpy(&v, &x, 4); return v; }
static double as_f64(uint64_t raw) { double v; memcpy(&v, &raw, 8); return v; }

// ── callee bodies (one macro, two ABIs) ─────────────────────────────────
#define BODY_III(a, b, c)          ((a) * 3 + (b) * 5 + (c) * 7)
#define BODY_FFF(a, b, c)          ((a) * 3.0f + (b) * 5.0f + (c) * 7.0f)
#define BODY_FII(a, b, c)          ((int32_t)((a) * 3.0f) + (b) * 5 + (c) * 7)
#define BODY_FFFI(a, b, c, e)      ((int32_t)((a) * 3.0f + (b) * 5.0f + (c) * 7.0f) + (e) * 11)
#define BODY_F5(a, b, c, e, g)     ((a) * 3.0f + (b) * 5.0f + (c) * 7.0f + (e) * 11.0f + (g) * 13.0f)
#define BODY_D5(a, b, c, e, g)     ((a) * 3 + (b) * 5 + (c) * 7 + (e) * 11 + (g) * 13)
#define BODY_D6(a, b, c, e, g, h)  (BODY_D5(a, b, c, e, g) + (h) * 17)
#define BODY_D11(a,b,c,e,g,h,j,k,m,n,o) \
    (BODY_D6(a,b,c,e,g,h) + (j)*19 + (k)*23 + (m)*29 + (n)*31 + (o)*37)
#define BODY_L7(a, b, c, e, g, h, j) \
    ((a) * 3 + (b) * 5 + (c) * 7 + (e) * 11 + (g) * 13 + (h) * 17 + (j) * 19)
#define BODY_MIXED10(a,b,c,e,g,h,j,k,m,n) \
    ((a)*3 + (b)*5 + (c)*7 + (e)*11 + (g)*13 + (h)*17 + (j)*19 + (k)*23 + (m)*29 + (n)*31)
#define BODY_IF4(a, b, c, e, g)    ((a) * 3 + (b) * 5.0 + (c) * 7.0 + (e) * 11.0 + (g) * 13.0)

#define TWIN(ret, name, params, call)                          \
    static ret __attribute__((ms_abi)) ms_##name params call   \
    static ret sysv_##name params call

TWIN(int32_t, iii,  (int32_t a, int32_t b, int32_t c), { return BODY_III(a, b, c); })
TWIN(float,   fff,  (float a, float b, float c),       { return BODY_FFF(a, b, c); })
TWIN(int32_t, fii,  (float a, int32_t b, int32_t c),   { return BODY_FII(a, b, c); })
TWIN(int32_t, fffi, (float a, float b, float c, int32_t e), { return BODY_FFFI(a, b, c, e); })
TWIN(float,   f5,   (float a, float b, float c, float e, float g), { return BODY_F5(a, b, c, e, g); })
TWIN(double,  d5,   (double a, double b, double c, double e, double g), { return BODY_D5(a, b, c, e, g); })
TWIN(double,  d6,   (double a, double b, double c, double e, double g, double h), { return BODY_D6(a, b, c, e, g, h); })
TWIN(double,  d11,  (double a, double b, double c, double e, double g, double h,
                     double j, double k, double m, double n, double o),
                    { return BODY_D11(a, b, c, e, g, h, j, k, m, n, o); })
TWIN(uint64_t, l7,  (uint64_t a, uint64_t b, uint64_t c, uint64_t e, uint64_t g, uint64_t h, uint64_t j),
                    { return BODY_L7(a, b, c, e, g, h, j); })
TWIN(double,  mixed10, (int32_t a, double b, int64_t c, double e, int32_t g,
                        double h, int64_t j, double k, int32_t m, double n),
                    { return BODY_MIXED10(a, b, c, e, g, h, j, k, m, n); })
TWIN(double,  if4,  (int32_t a, float b, float c, float e, float g), { return BODY_IF4(a, b, c, e, g); })

static uint64_t __attribute__((ms_abi)) ms_ffll(float a, float b, uint64_t c, uint64_t d) {
    return a == as_f32(XMM_RAW[0]) && b == as_f32(XMM_RAW[1])
        && c == INT_REGS[0] && d == INT_REGS[1];
}

static unsigned field_offset;

static uint64_t __attribute__((ms_abi)) ms_fields(
        uint64_t a, float b, uint64_t c, uint64_t d, uint64_t e, double f, uint64_t g, double h,
        float i, double j, float k, double l, uint64_t m, uint64_t n, float o, uint64_t p,
        double q, float r) {
    return a == INT_REGS[0] && b == as_f32(XMM_RAW[0]) && c == INT_REGS[1] + field_offset
        && d == INT_REGS[2] && e == INT_REGS[3] + field_offset && f == as_f64(XMM_RAW[1])
        && g == INT_REGS[4] && h == as_f64(XMM_RAW[2]) && i == as_f32(XMM_RAW[3])
        && j == as_f64(XMM_RAW[4]) && k == as_f32(XMM_RAW[5]) && l == as_f64(XMM_RAW[6])
        && m == INT_REGS[5] && n == SLOT_RAW[0] + field_offset && o == as_f32(XMM_RAW[7])
        && p == SLOT_RAW[1] + field_offset && q == as_f64(SLOT_RAW[2]) && r == as_f32(SLOT_RAW[3]);
}

// ── trampoline: load HotSpot-Java-Windows-convention state, call adapter ──

extern uint64_t __attribute__((ms_abi)) ms_shadow(void);
__asm__(
    ".text\n"
    ".type ms_shadow, @function\n"
    "ms_shadow:\n\t"
    "movaps %xmm0, 8(%rsp)\n\t"    // must be aligned and owned by the callee
    "movaps %xmm1, 24(%rsp)\n\t"
    "mov $1, %eax\n\t"
    "ret\n"
);
double ret_xmm0;

extern uint64_t run_adapter(void *code);

__asm__(
    ".text\n"
    ".globl run_adapter\n"
    ".type run_adapter, @function\n"
    "run_adapter:\n\t"
    "push %rbx\n\t"                // entry rsp%16==8 -> now 0
    "mov  %rdi, %r10\n\t"
    "sub  $48, %rsp\n\t"           // rsp%16==0; 4 slots at [rsp..rsp+31]
    "lea  SLOT_RAW(%rip), %rax\n\t"
    "mov  0(%rax),  %rbx\n\t"  "mov %rbx, 0(%rsp)\n\t"
    "mov  8(%rax),  %rbx\n\t"  "mov %rbx, 8(%rsp)\n\t"
    "mov  16(%rax), %rbx\n\t"  "mov %rbx, 16(%rsp)\n\t"
    "mov  24(%rax), %rbx\n\t"  "mov %rbx, 24(%rsp)\n\t"
    "lea  XMM_RAW(%rip), %rax\n\t"
    "movsd 0(%rax),  %xmm0\n\t"
    "movsd 8(%rax),  %xmm1\n\t"
    "movsd 16(%rax), %xmm2\n\t"
    "movsd 24(%rax), %xmm3\n\t"
    "movsd 32(%rax), %xmm4\n\t"
    "movsd 40(%rax), %xmm5\n\t"
    "movsd 48(%rax), %xmm6\n\t"
    "movsd 56(%rax), %xmm7\n\t"
    "lea  INT_REGS(%rip), %rax\n\t"
    "mov   0(%rax), %rdx\n\t"
    "mov   8(%rax), %r8\n\t"
    "mov  16(%rax), %r9\n\t"
    "mov  24(%rax), %rdi\n\t"
    "mov  32(%rax), %rsi\n\t"
    "mov  40(%rax), %rcx\n\t"
    "call *%r10\n\t"               // adapter entry: rsp%16==8, slots at [rsp+8+8k]
    "add  $48, %rsp\n\t"
    "movsd %xmm0, ret_xmm0(%rip)\n\t"
    "pop  %rbx\n\t"
    "ret\n"
);

// ── expected-value computation: mirror of HotSpot's Java convention ─────
typedef struct { uint64_t raw[16]; int n; } ArgVals;   // raw source per arg

static ArgVals java_map(const char *sig) {
    ArgVals v = {{0}, 0};
    int ii = 0, fi = 0, sp = 0;
    for (const char *c = sig; *c; c++, v.n++) {
        if (*c == 'f' || *c == 'd') {
            v.raw[v.n] = (fi < 8) ? XMM_RAW[fi++] : SLOT_RAW[sp++];
        } else {
            v.raw[v.n] = (ii < 6) ? (uint64_t) INT_REGS[ii++] : SLOT_RAW[sp++];
        }
    }
    return v;
}

static void *load_adapter(const char *name, void *callee) {
    char path[256];
    snprintf(path, sizeof path, "win_adapter_%s.bin", name);
    FILE *fp = fopen(path, "rb");
    if (!fp) { fprintf(stderr, "missing %s\n", path); exit(1); }
    static unsigned char tmp[4096];
    size_t len = fread(tmp, 1, sizeof tmp, fp);
    fclose(fp);

    unsigned char *pat = memmem(tmp, len, "\xBE\xBA\xFE\xCA\xEF\xBE\xAD\xDE", 8);
    if (!pat) { fprintf(stderr, "%s: magic not found\n", name); exit(1); }
    memcpy(pat, &callee, 8);

    void *mem = mmap(NULL, 4096, PROT_READ | PROT_WRITE | PROT_EXEC,
                     MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) { perror("mmap"); exit(1); }
    memcpy(mem, tmp, len);
    return mem;
}

static int failures = 0;

static void check_i(const char *name, int32_t got, int32_t want) {
    if (got != want) { failures++; printf("FAIL %-8s got=%d want=%d\n", name, got, want); }
    else printf("ok   %-8s %d\n", name, got);
}
static void check_l(const char *name, int64_t got, int64_t want) {
    if (got != want) { failures++; printf("FAIL %-8s got=%ld want=%ld\n", name, got, want); }
    else printf("ok   %-8s %ld\n", name, got);
}
static void check_d(const char *name, double got, double want) {
    if (memcmp(&got, &want, 8)) { failures++; printf("FAIL %-8s got=%.17g want=%.17g\n", name, got, want); }
    else printf("ok   %-8s %.17g\n", name, got);
}
static void check_f(const char *name, double got_xmm0, float want) {
    float got; uint64_t raw; memcpy(&raw, &got_xmm0, 8);
    uint32_t lo = (uint32_t) raw; memcpy(&got, &lo, 4);
    if (memcmp(&got, &want, 4)) { failures++; printf("FAIL %-8s got=%.9g want=%.9g\n", name, got, want); }
    else printf("ok   %-8s %.9g\n", name, got);
}

int main(void) {
    for (int i = 0; i < 8; i++) {
        float lo = 10.5f + i, hi = 20.5f + i;
        uint32_t lb, hb; memcpy(&lb, &lo, 4); memcpy(&hb, &hi, 4);
        XMM_RAW[i] = (uint64_t) lb | ((uint64_t) hb << 32);
    }
    for (int i = 0; i < 4; i++) {
        float lo = 201.5f + i, hi = 211.5f + i;
        uint32_t lb, hb; memcpy(&lb, &lo, 4); memcpy(&hb, &hi, 4);
        SLOT_RAW[i] = (uint64_t) lb | ((uint64_t) hb << 32);
    }

    ArgVals v;
    uint64_t r;

    v = java_map("iii");
    r = run_adapter(load_adapter("iii", (void *) ms_iii));
    check_i("iii", (int32_t) r, sysv_iii((int32_t) v.raw[0], (int32_t) v.raw[1], (int32_t) v.raw[2]));

    v = java_map("fff");
    run_adapter(load_adapter("fff", (void *) ms_fff));
    check_f("fff", ret_xmm0, sysv_fff(as_f32(v.raw[0]), as_f32(v.raw[1]), as_f32(v.raw[2])));

    v = java_map("fii");
    r = run_adapter(load_adapter("fii", (void *) ms_fii));
    check_i("fii", (int32_t) r, sysv_fii(as_f32(v.raw[0]), (int32_t) v.raw[1], (int32_t) v.raw[2]));

    v = java_map("fffi");
    r = run_adapter(load_adapter("fffi", (void *) ms_fffi));
    check_i("fffi", (int32_t) r,
            sysv_fffi(as_f32(v.raw[0]), as_f32(v.raw[1]), as_f32(v.raw[2]), (int32_t) v.raw[3]));

    v = java_map("fffff");
    run_adapter(load_adapter("f5", (void *) ms_f5));
    check_f("f5", ret_xmm0,
            sysv_f5(as_f32(v.raw[0]), as_f32(v.raw[1]), as_f32(v.raw[2]), as_f32(v.raw[3]), as_f32(v.raw[4])));

    v = java_map("ddddd");
    run_adapter(load_adapter("d5", (void *) ms_d5));
    check_d("d5", ret_xmm0,
            sysv_d5(as_f64(v.raw[0]), as_f64(v.raw[1]), as_f64(v.raw[2]), as_f64(v.raw[3]), as_f64(v.raw[4])));

    v = java_map("dddddd");
    run_adapter(load_adapter("d6", (void *) ms_d6));
    check_d("d6", ret_xmm0,
            sysv_d6(as_f64(v.raw[0]), as_f64(v.raw[1]), as_f64(v.raw[2]),
                    as_f64(v.raw[3]), as_f64(v.raw[4]), as_f64(v.raw[5])));

    v = java_map("ddddddddddd");
    run_adapter(load_adapter("d11", (void *) ms_d11));
    check_d("d11", ret_xmm0,
            sysv_d11(as_f64(v.raw[0]), as_f64(v.raw[1]), as_f64(v.raw[2]), as_f64(v.raw[3]),
                     as_f64(v.raw[4]), as_f64(v.raw[5]), as_f64(v.raw[6]), as_f64(v.raw[7]),
                     as_f64(v.raw[8]), as_f64(v.raw[9]), as_f64(v.raw[10])));

    v = java_map("lllllll");
    r = run_adapter(load_adapter("l7", (void *) ms_l7));
    check_l("l7", (int64_t) r, (int64_t) sysv_l7(v.raw[0], v.raw[1], v.raw[2], v.raw[3],
                                                 v.raw[4], v.raw[5], v.raw[6]));

    v = java_map("idldidldid");
    run_adapter(load_adapter("mixed10", (void *) ms_mixed10));
    check_d("mixed10", ret_xmm0,
            sysv_mixed10((int32_t) v.raw[0], as_f64(v.raw[1]), (int64_t) v.raw[2], as_f64(v.raw[3]),
                         (int32_t) v.raw[4], as_f64(v.raw[5]), (int64_t) v.raw[6], as_f64(v.raw[7]),
                         (int32_t) v.raw[8], as_f64(v.raw[9])));

    v = java_map("iffff");
    run_adapter(load_adapter("if4", (void *) ms_if4));
    check_d("if4", ret_xmm0,
            sysv_if4((int32_t) v.raw[0], as_f32(v.raw[1]), as_f32(v.raw[2]), as_f32(v.raw[3]), as_f32(v.raw[4])));

    check_l("shadow", run_adapter(load_adapter("shadow", (void *) ms_shadow)), 1);
    check_l("ffll", run_adapter(load_adapter("ffll", (void *) ms_ffll)), 1);
    FILE *offsets = fopen("win_field_offset.txt", "r");
    if (!offsets || fscanf(offsets, "%u", &field_offset) != 1 || !field_offset) {
        fprintf(stderr, "Missing or invalid win_field_offset.txt\n");
        return 1;
    }
    fclose(offsets);
    check_l("fields", run_adapter(load_adapter("fields", (void *) ms_fields)), 1);

    printf(failures ? "=== %d FAILURES ===\n" : "=== ALL WIN64 ADAPTERS MATCH ===\n", failures);
    return failures != 0;
}
