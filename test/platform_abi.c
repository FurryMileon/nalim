#include <stdbool.h>
#include <stdint.h>

#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT __attribute__((visibility("default")))
#endif

EXPORT int32_t abi_xor(int32_t x, int32_t y) {
    return x ^ y;
}

/* One bit per argument: swaps, lost high halves, and mispacked stack slots
 * are distinguishable even when two corruptions would cancel out in a sum. */
EXPORT int32_t abi_mixed(
        int64_t a, float b, int64_t c, double d, int64_t e, float f, int64_t g, double h,
        int64_t i, float j, int64_t k, double l, int64_t m, float n, int64_t o, double p,
        int8_t q, int8_t r, int16_t s, float t, float u, double v, int64_t w) {
    return (a != INT64_C(0x100000001)) | (b != 2.25f) << 1 |
           (c != INT64_C(0x300000003)) << 2 | (d != -4.5) << 3 |
           (e != INT64_C(0x500000005)) << 4 | (f != 6.25f) << 5 |
           (g != INT64_C(0x700000007)) << 6 | (h != -8.5) << 7 |
           (i != INT64_C(0x900000009)) << 8 | (j != 10.25f) << 9 |
           (k != INT64_C(0xb0000000b)) << 10 | (l != -12.5) << 11 |
           (m != INT64_C(0xd0000000d)) << 12 | (n != 14.25f) << 13 |
           (o != INT64_C(0xf0000000f)) << 14 | (p != -16.5) << 15 |
           (q != -73) << 16 | (r != 91) << 17 | (s != -12345) << 18 |
           (t != -20.25f) << 19 | (u != 21.25f) << 20 | (v != -22.5) << 21 |
           (w != INT64_C(0x1700000017)) << 22;
}

EXPORT double abi_memory(int32_t *array, int64_t *field, double scale) {
    array[1] += array[0];
    *field += array[1];
    return *field * scale;
}

EXPORT bool abi_bool(int32_t x) { return x != 0; }
EXPORT int8_t abi_byte(int32_t x) { return (int8_t)x; }
EXPORT int16_t abi_short(int32_t x) { return (int16_t)x; }
EXPORT uint16_t abi_char(int32_t x) { return (uint16_t)x; }

#ifdef __x86_64__
/* These entry points consume HotSpot registers and stack slots, not C arguments.
 * Poison bits 16..31 so Java must sign-extend the short result. */
#ifdef _WIN32
#define JAVA_GP5 "%esi"
#define JAVA_GP6 "%ecx"
#else
#define JAVA_GP5 "%r9d"
#define JAVA_GP6 "%edi"
#endif
EXPORT __attribute__((naked)) int16_t abi_java_regs(void) {
    __asm__("cvttsd2si %xmm7, %eax\n\t"
            "sub " JAVA_GP5 ", %eax\n\t"
            "add " JAVA_GP6 ", %eax\n\t"
            "xor $0x55550000, %eax\n\tret");
}

EXPORT __attribute__((naked)) int16_t abi_java_spills(void) {
    __asm__("cvttss2si 16(%rsp), %eax\n\t"  /* ninth FP */
            "sub 8(%rsp), %eax\n\t"         /* seventh GP */
            "xor $0x55550000, %eax\n\tret");
}
#endif
