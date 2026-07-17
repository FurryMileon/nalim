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
