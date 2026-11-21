#include <stdbool.h>
#include <stdint.h>

#if !defined(__x86_64__) || defined(_WIN32)
#error "This regression fixture is for the System V AMD64 ABI"
#endif

#define NAKED __attribute__((naked, noinline, visibility("default")))

NAKED bool nalim_dirty_bool_false(void) {
    __asm__("movabs $0x1122334455667700, %rax\n\tret");
}

NAKED bool nalim_dirty_bool_true(void) {
    __asm__("movabs $0x1122334455667701, %rax\n\tret");
}

NAKED int8_t nalim_dirty_byte(void) {
    __asm__("movabs $0x1122334455667780, %rax\n\tret");
}

NAKED int16_t nalim_dirty_short(void) {
    __asm__("movabs $0x1122334455668001, %rax\n\tret");
}

NAKED uint16_t nalim_dirty_char(void) {
    __asm__("movabs $0x112233445566abcd, %rax\n\tret");
}

NAKED bool nalim_stack_is_aligned(void) {
    __asm__(
        "mov %rsp, %rax\n\t"
        "and $15, %eax\n\t"
        "cmp $8, %eax\n\t"
        "sete %al\n\t"
        "ret"
    );
}

NAKED int8_t nalim_narrow_stack7(
        int64_t a, int64_t b, int64_t c, int64_t d,
        int64_t e, int64_t f, int64_t seventh) {
    __asm__("mov 8(%rsp), %rax\n\tret");
}

NAKED int8_t nalim_narrow_stack9d(
        double a, double b, double c, double d, double e,
        double f, double g, double h, double ninth) {
    __asm__("cvttsd2si 8(%rsp), %eax\n\tret");
}

// Fill both register banks, then interleave 16 spilled scalars. The even slot
// count needs alignment padding, and the last spill is beyond a signed disp8.
__attribute__((visibility("default")))
int16_t nalim_narrow_mixed(
        int32_t i1, int32_t i2, int32_t i3, int32_t i4, int32_t i5, int32_t i6,
        double d1, double d2, double d3, double d4, double d5, double d6, double d7, double d8,
        int32_t i7, float f1, int64_t l1, double d9, int32_t i8, float f2, int64_t l2, double d10,
        int32_t i9, float f3, int64_t l3, double d11, int32_t i10, float f4, int64_t l4, double d12) {
    return i1 == 1 && i2 == 2 && i3 == 3 && i4 == 4 && i5 == 5 && i6 == 6 &&
           d1 == 1.5 && d2 == 2.5 && d3 == 3.5 && d4 == 4.5 &&
           d5 == 5.5 && d6 == 6.5 && d7 == 7.5 && d8 == 8.5 &&
           i7 == -7 && f1 == -1.25f && l1 == INT64_C(0x100000001) && d9 == 9.5 &&
           i8 == -8 && f2 == -2.25f && l2 == INT64_C(0x200000002) && d10 == 10.5 &&
           i9 == -9 && f3 == -3.25f && l3 == INT64_C(0x300000003) && d11 == 11.5 &&
           i10 == -10 && f4 == -4.25f && l4 == INT64_C(0x400000004) && d12 == 12.5
           ? -12345 : 0;
}
