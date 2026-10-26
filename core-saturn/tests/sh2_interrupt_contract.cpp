/* Compile the actual adapter with section GC; no executable SH2 code is needed. */
#include "../src/sh2_dynarec_devmiyax/DynarecSh2CInterface.cpp"

// A valid C++ object with its real interrupt-list member, without a JIT arena.
// Only SH2DynGetInterrupts is retained from the production translation unit.
DynarecSh2::DynarecSh2() {}
DynarecSh2::~DynarecSh2() {}

int main() {
    DynarecSh2 cpu;
    SH2_struct context = {};
    context.ext = &cpu;
    interrupt_struct output[MAX_INTERRUPTS + 1] = {};
    if (SH2DynGetInterrupts(&context, output) != 0) return 1;
    cpu.m_IntruptTbl.push_back(dIntcTbl{0x42, 7});
    cpu.m_IntruptTbl.push_back(dIntcTbl{0x44, 5});
    if (SH2DynGetInterrupts(&context, output) != 2) return 2;
    if (output[0].vector != 0x42 || output[0].level != 7 ||
        output[1].vector != 0x44 || output[1].level != 5) return 3;
    for (int i = 0; i < MAX_INTERRUPTS; ++i)
        cpu.m_IntruptTbl.push_back(dIntcTbl{static_cast<u8>(i), 1});
    output[MAX_INTERRUPTS].vector = 0xad;
    if (SH2DynGetInterrupts(&context, output) != MAX_INTERRUPTS ||
        output[MAX_INTERRUPTS].vector != 0xad) return 4;
    puts("PASS: actual SH2 adapter preserves pending IRQ count and bounds");
    return 0;
}
