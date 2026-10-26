/* Exercise the actual adapter save/load methods with authored CPU state. */
#include "../src/m68kmusashi.c"
#include <string.h>
m68ki_cpu_core m68ki_cpu;
sint m68ki_remaining_cycles;
uint m68ki_tracing, m68ki_address_space;
uint m68ki_aerr_address, m68ki_aerr_write_mode, m68ki_aerr_fc;
int main(void) {
    const size_t scalar_size = offsetof(m68ki_cpu_core, cyc_instruction);
    unsigned char expected[sizeof(m68ki_cpu_core)];
    memset(&m68ki_cpu, 0, sizeof(m68ki_cpu));
    m68ki_cpu.pc = 0x123456;
    m68ki_cpu.dar[3] = 0xabcdef;
    m68ki_cpu.int_level = 4;
    m68ki_cpu.int_cycles = 31;
    m68ki_cpu.pref_addr = 0x1000;
    m68ki_cpu.pref_data = 0x4e71;
    m68ki_cpu.cyc_instruction = (uint8*)0x1234;
    m68ki_cpu.cyc_exception = (uint8*)0x5678;
    m68ki_remaining_cycles = -9;
    m68ki_tracing = 2;
    m68ki_aerr_address = 0x9876;
    memcpy(expected, &m68ki_cpu, scalar_size);
    FILE *fp = tmpfile(); if (!fp) return 1;
    M68KMusashiSaveState(fp);
    if (ftell(fp) != scalar_size + 6 * sizeof(uint)) return 2;
    memset(&m68ki_cpu, 0xff, scalar_size);
    /* Live host pointers can differ at restore and must remain live. */
    m68ki_cpu.cyc_instruction = (uint8*)0x9abc;
    m68ki_remaining_cycles = m68ki_tracing = m68ki_aerr_address = 0;
    rewind(fp); M68KMusashiLoadState(fp); fclose(fp);
    if (memcmp(expected, &m68ki_cpu, scalar_size) || m68ki_cpu.cyc_instruction != (uint8*)0x9abc ||
        m68ki_cpu.cyc_exception != (uint8*)0x5678 || m68ki_remaining_cycles != -9 ||
        m68ki_tracing != 2 || m68ki_aerr_address != 0x9876) return 3;
    puts("PASS: Musashi scalar/interrupt/prefetch restore preserves live host pointers");
    return 0;
}
