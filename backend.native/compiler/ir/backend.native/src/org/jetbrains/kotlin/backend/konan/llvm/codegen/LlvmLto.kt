package org.jetbrains.kotlin.backend.konan.llvm.codegen

import kotlinx.cinterop.*
import llvm.*
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.KonanPhase
import org.jetbrains.kotlin.backend.konan.PhaseManager
import org.jetbrains.kotlin.backend.konan.library.KonanLibraryReader
import org.jetbrains.kotlin.backend.konan.llvm.parseBitcodeFile
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget

internal fun linkBitcode(mainModule: LLVMModuleRef, toLink: List<LLVMModuleRef?>) {
    for (library in toLink) {
        val failed = LLVMLinkModules2(mainModule, library)
        if (failed != 0) {
            throw Error("failed to link $library") // TODO: retrieve error message from LLVM.
        }
    }
}

internal fun lto(context: Context, phaser: PhaseManager, nativeLibraries: List<String>) {
    val libraries = context.llvm.librariesToLink
    val programModule = context.llvmModule!!
    val runtime = context.llvm.runtime

    fun stdlibPredicate(libraryReader: KonanLibraryReader) = libraryReader.uniqueName == "stdlib"
    val stdlibPath = libraries.first(::stdlibPredicate).bitcodePaths.first { it.endsWith("program.kt.bc") }
    val stdlibModule = parseBitcodeFile(stdlibPath)
    val otherModules = libraries.filterNot(::stdlibPredicate).flatMap { it.bitcodePaths }

    phaser.phase(KonanPhase.BITCODE_LINKER) {
        linkBitcode(programModule, (nativeLibraries + otherModules).map { parseBitcodeFile(it) })
    }

    phaser.phase(KonanPhase.LLVM_CODEGEN) {

        val target = LLVMGetTarget(runtime.llvmModule)!!.toKString()
        val compilingForHost = HostManager.host == context.config.target

        val llvmRelocMode = when (context.config.produce) {
            CompilerOutputKind.PROGRAM -> LLVMRelocMode.LLVMRelocStatic
            else -> LLVMRelocMode.LLVMRelocPIC
        }

        val optLevel = when {
            context.shouldOptimize() -> 3
            context.shouldContainDebugInfo() -> 0
            else -> 1
        }

        val sizeLevel = when (context.config.target) {
            KonanTarget.WASM32, is KonanTarget.ZEPHYR -> 1
            else -> 0
        }

        context.mergedObject = context.config.tempFiles.create("merged", ".o")
        val (outputKind, filename) = Pair(OutputKind.OUTPUT_KIND_OBJECT_FILE, context.mergedObject.absolutePath)

        memScoped {
            val configuration = alloc<CompilationConfiguration>()
            configuration.apply {
                this.optLevel = optLevel
                this.sizeLevel = sizeLevel
                this.outputKind = outputKind
                shouldProfile = context.shouldProfilePhases().toByte().toInt()
                fileName = filename.cstr.ptr
                targetTriple = target.cstr.ptr
                relocMode = llvmRelocMode
                shouldPerformLto = context.shouldOptimize().toByte().toInt()
                shouldPreserveDebugInfo = context.shouldContainDebugInfo().toByte().toInt()
                this.compilingForHost = compilingForHost.toByte().toInt()
            }
            if (LLVMLtoCodegen(
                            LLVMGetModuleContext(context.llvmModule),
                            programModule,
                            runtime.llvmModule,
                            stdlibModule,
                            configuration.readValue()
                    ) != 0) {
                context.log { "Codegen failed" }
            }
        }
    }
}