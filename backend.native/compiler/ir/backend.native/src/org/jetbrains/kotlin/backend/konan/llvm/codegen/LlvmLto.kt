package org.jetbrains.kotlin.backend.konan.llvm.codegen

import kotlinx.cinterop.*
import llvm.*
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.KonanPhase
import org.jetbrains.kotlin.backend.konan.PhaseManager
import org.jetbrains.kotlin.backend.konan.library.KonanLibraryReader
import org.jetbrains.kotlin.backend.konan.llvm.parseBitcodeFile
import org.jetbrains.kotlin.konan.target.CompilerOutputKind

internal fun lto(context: Context, phaser: PhaseManager, nativeLibraries: List<String>) {
    val libraries = context.llvm.librariesToLink
    val programModule = context.llvmModule!!
    val runtime = context.llvm.runtime

    fun stdlibPredicate(libraryReader: KonanLibraryReader) = libraryReader.uniqueName == "stdlib"
    val stdlibPath = libraries.first(::stdlibPredicate).bitcodePaths.first { it.endsWith("program.kt.bc") }
    val stdlibModule = parseBitcodeFile(stdlibPath)
    val otherModules = libraries.filterNot(::stdlibPredicate).flatMap { it.bitcodePaths }

    phaser.phase(KonanPhase.BITCODE_LINKER) {
        for (library in nativeLibraries + otherModules) {
            val libraryModule = parseBitcodeFile(library)
            val failed = LLVMLinkModules2(programModule, libraryModule)
            if (failed != 0) {
                throw Error("failed to link $library") // TODO: retrieve error message from LLVM.
            }
        }
    }

    // TODO: ugly
    fun Boolean.toInt() = if (this) 1 else 0

    phaser.phase(KonanPhase.LLVM_CODEGEN) {
        assert(context.shouldUseNewPipeline()) // just sanity check for now.
        val target = LLVMGetTarget(runtime.llvmModule)!!.toKString()
        val llvmRelocMode = if (context.config.produce == CompilerOutputKind.PROGRAM)
            LLVMRelocMode.LLVMRelocStatic else LLVMRelocMode.LLVMRelocPIC
        memScoped {
            val configuration = alloc<CompilationConfiguration>()
            context.mergedObject = context.config.tempFiles.create("merged", ".o")
            val (outputKind, filename) = Pair(OutputKind.OUTPUT_KIND_OBJECT_FILE, context.mergedObject.absolutePath)
            configuration.apply {
                optLevel = if (context.shouldOptimize()) 3 else 1
                sizeLevel = 0 // TODO: make target dependent. On wasm it should be >0.
                this.outputKind = outputKind
                shouldProfile = context.shouldProfilePhases().toInt()
                fileName = filename.cstr.ptr
                targetTriple = target.cstr.ptr
                relocMode = llvmRelocMode
                shouldPerformLto = context.shouldOptimize().toInt()
                shouldPreserveDebugInfo = context.shouldContainDebugInfo().toInt()
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