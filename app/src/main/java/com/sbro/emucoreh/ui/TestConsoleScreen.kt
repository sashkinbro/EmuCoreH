
package com.sbro.emucoreh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun TestConsoleScreen(
    modifier: Modifier = Modifier,
    vm: TestConsoleViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("EmuCoreH — M0 Native PS1 Machine Console", style = MaterialTheme.typography.titleLarge)
        Text("Engineering console for deterministic core smoke", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("API version: ${state.apiVersionHex}", fontFamily = FontFamily.Monospace)
                Text("Host: ${state.hostInfo}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.runSmoke() }, enabled = !state.isRunning) {
                Text("Run Smoke (core-runner --smoke)")
            }
            OutlinedButton(onClick = { vm.refreshDiagnostics() }, enabled = !state.isRunning) {
                Text("Refresh")
            }
            if (state.isRunning) CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.runCpuTests() }, enabled = !state.isRunning) {
                Text("Run CPU Tests (DoD §38)")
            }
            Button(onClick = { vm.runIrqTimerTests() }, enabled = !state.isRunning) {
                Text("Run IRQ/Timer §12-13")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.runDmaTests() }, enabled = !state.isRunning) {
                Text("Run DMA Tests (§11)")
            }
            Button(onClick = { vm.runGteTests() }, enabled = !state.isRunning) {
                Text("Run GTE Tests (§14-15)")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.runGpuTests() }, enabled = !state.isRunning) {
                Text("Run GPU Tests (§16-18)")
            }
            Button(onClick = { vm.runSpuMdecTests() }, enabled = !state.isRunning) {
                Text("Run SPU/MDEC (§21-23)")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.runCdromSioTests() }, enabled = !state.isRunning) {
                Text("Run CDROM/SIO (§24-26)")
            }
            Button(onClick = { vm.runJitTests() }, enabled = !state.isRunning) {
                Text("Run JIT Tests (§28-29)")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.runBiosTests() }, enabled = !state.isRunning) {
                Text("Run BIOS Tests (§10)")
            }
            Button(onClick = { vm.runOptimizedTests() }, enabled = !state.isRunning) {
                Text("Run Optimized (§11 NEON)")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.runRegressionTests() }, enabled = !state.isRunning) {
                Text("Run Regression (§13)")
            }
            Button(onClick = { vm.runFinalTests() }, enabled = !state.isRunning) {
                Text("Run Final (§14+15)")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.runDiscLoaderTests() }, enabled = !state.isRunning) {
                Text("Run Disc Loader (BIN/CUE)")
            }
            Button(onClick = { vm.runAsyncDiscTests() }, enabled = !state.isRunning) {
                Text("Run Async Disc (§25)")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.runSavestateFileTests() }, enabled = !state.isRunning) {
                Text("Run Savestate File (§27)")
            }
            Button(onClick = { vm.runBootTests() }, enabled = !state.isRunning) {
                Text("Run Boot (BIOS+Disc)")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.runHostThreadTests() }, enabled = !state.isRunning) {
                Text("Run Host Thread (§5.2)")
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Smoke Result", style = MaterialTheme.typography.titleMedium)
                Text(state.smokeResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("CPU Self Test (R3000A DoD §38)", style = MaterialTheme.typography.titleMedium)
                Text(state.cpuTestResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("IRQ/Timer Self Test (§12-13)", style = MaterialTheme.typography.titleMedium)
                Text(state.irqTimerResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("DMA Self Test (§11)", style = MaterialTheme.typography.titleMedium)
                Text(state.dmaTestResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("GTE Self Test (§14-15)", style = MaterialTheme.typography.titleMedium)
                Text(state.gteTestResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("GPU Self Test (§16-18)", style = MaterialTheme.typography.titleMedium)
                Text(state.gpuTestResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SPU/MDEC Self Test (§21-23)", style = MaterialTheme.typography.titleMedium)
                Text(state.spuMdecTestResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("CDROM/SIO Self Test (§24-26)", style = MaterialTheme.typography.titleMedium)
                Text(state.cdromSioTestResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("JIT Self Test (§28-29)", style = MaterialTheme.typography.titleMedium)
                Text(state.jitTestResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("BIOS Self Test (§10)", style = MaterialTheme.typography.titleMedium)
                Text(state.biosTestResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Optimized Self Test (§11 NEON)", style = MaterialTheme.typography.titleMedium)
                Text(state.optimizedTestResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Regression Self Test (§13)", style = MaterialTheme.typography.titleMedium)
                Text(state.regressionTestResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Final Self Test (§14+15)", style = MaterialTheme.typography.titleMedium)
                Text(state.finalTestResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Disc Loader Test (BIN/CUE 2352/2048)", style = MaterialTheme.typography.titleMedium)
                Text(state.discLoaderResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Async Disc Test (§25 bounded queue)", style = MaterialTheme.typography.titleMedium)
                Text(state.asyncDiscResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Savestate File Test (§27 chunk)", style = MaterialTheme.typography.titleMedium)
                Text(state.savestateFileResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Boot Test (BIOS+Disc 100 steps)", style = MaterialTheme.typography.titleMedium)
                Text(state.bootResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Host Thread Test (§5.2 bounded queue)", style = MaterialTheme.typography.titleMedium)
                Text(state.hostThreadResult, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Diagnostics (emucorea_diagnostics)", style = MaterialTheme.typography.titleMedium)
                Text(state.diagnostics, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("M0+CPU+IRQ/Timer+DMA+GTE+GPU+SPU/MDEC+CDROM/SIO+JIT+BIOS+Optimized+Regression+Final checks:", style = MaterialTheme.typography.titleSmall)
                Text("• endian/types  • scheduler ordering  • memory mapping  • snapshot/replay  • hash\n• MIPS decoder  • branch delay  • HI/LO  • I_STAT/I_MASK  • Timer0-2  • DMA channels  • GTE RTPS/RTPT/NCLIP/AVSZ/OP/MVMVA  • FLAG  • GPU VRAM fill/copy  • SPU ADSR/mixer  • MDEC IDCT  • CDROM GetStat/ReadN  • SIO pad poll  • MemCard  • JIT W^X/IR/block cache  • BIOS mapping/BEV/reset  • GTE/MDEC/SPU NEON  • PAL/NTSC  • CD streaming  • savestate  • timing edges  • JIT linking/hot/batching/LTO  • lifecycle SAF", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
