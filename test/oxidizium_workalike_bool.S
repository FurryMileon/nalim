// Boolean-returning workalike functions with DELIBERATELY dirty upper RAX.
// System V only defines AL for _Bool returns; leaving garbage above AL is
// ABI-legal and is what exposes a missing narrow-return normalization in an
// FFI layer that reads the full EAX/RAX.

    .intel_syntax noprefix
    .text

#define FUNC(name)      \
    .globl name;        \
    .type name, @function; \
    .p2align 4;         \
name:

// bool is_power_of_2(int64_t v): v != 0 && (v & (v-1)) == 0
FUNC(is_power_of_2)
    movabs rax, 0x1122334455667700   // dirty everything above AL
    test rdi, rdi
    jz 1f
    lea rcx, [rdi-1]
    and rcx, rdi
    jnz 1f
    or al, 1
1:  ret

// bool is_power_of_two(int32_t v): same for ints
FUNC(is_power_of_two)
    movabs rax, 0x1122334455667700
    test edi, edi
    jz 2f
    lea ecx, [rdi-1]
    and ecx, edi
    jnz 2f
    or al, 1
2:  ret

// bool approximately_equals_float(float a, float b): |a-b| < 1e-5f
FUNC(approximately_equals_float)
    movabs rax, 0x1122334455667700
    subss xmm0, xmm1
    andps xmm0, [rip + .Labsf]
    comiss xmm0, [rip + .Leps_f]
    jae 3f
    or al, 1
3:  ret

// bool approximately_equals_double(double a, double b): |a-b| < 1e-9
FUNC(approximately_equals_double)
    movabs rax, 0x1122334455667700
    subsd xmm0, xmm1
    andpd xmm0, [rip + .Labsd]
    comisd xmm0, [rip + .Leps_d]
    jae 4f
    or al, 1
4:  ret

    .section .rodata
    .p2align 4
.Labsf:
    .long 0x7fffffff, 0x7fffffff, 0x7fffffff, 0x7fffffff
    .p2align 4
.Labsd:
    .quad 0x7fffffffffffffff, 0x7fffffffffffffff
.Leps_f:
    .float 1e-5
.Leps_d:
    .double 1e-9

    .section .note.GNU-stack,"",@progbits
