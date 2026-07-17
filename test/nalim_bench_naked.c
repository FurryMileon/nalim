// Custom C functions that accept arguments in HOTSPOT JAVA calling convention
// instead of System V C. With @Link(naked=true), Nalim skips its 2-mov shuffle
// prologue, so the installed nmethod becomes the absolute minimum:
//     mov rax, addr
//     jmp rax
// We test: does naked + Java-conv really shave the shuffle cycle?
//
// HotSpot x86-64 Linux static-native calling convention:
//   integer args: rsi, rdx, rcx, r8, r9, rdi, stack
//   return:       rax / eax
//
// Compile: gcc -O2 -shared -fPIC -o libnalim_bench_naked.so nalim_bench_naked.c

#include <stdint.h>

// raw_add(int a, int b): a in ESI, b in EDX, return in EAX.
// Whole body is "lea eax, [rsi+rdx]; ret" — 4 bytes.
__attribute__((naked, sysv_abi))
int32_t java_add(void) {
    __asm__ volatile (
        "lea (%rsi,%rdx,1), %eax\n\t"
        "ret"
    );
}

// raw_abs(int x): x in ESI.
__attribute__((naked, sysv_abi))
int32_t java_abs(void) {
    __asm__ volatile (
        "mov %esi, %eax\n\t"
        "neg %eax\n\t"
        "cmovs %esi, %eax\n\t"
        "ret"
    );
}

// raw_noop: just return 42.
__attribute__((naked, sysv_abi))
int32_t java_noop(void) {
    __asm__ volatile (
        "mov $42, %eax\n\t"
        "ret"
    );
}
