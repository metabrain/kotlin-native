/*
 * Copyright 2010-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include "CodeGen.h"

bool KotlinNativeLlvmBackend::compile(std::unique_ptr<Module> module, raw_pwrite_stream &os) {
  if (createTargetMachine()) {
    return true;
  }
  module->setDataLayout(targetMachine->createDataLayout());

  // Preparation passes. Right after linkage we have a lot of unused symbols. Lets strip them.
  legacy::PassManager preparationPasses;
  preparationPasses.add(
      createTargetTransformInfoWrapperPass(targetMachine->getTargetIRAnalysis()));

  preparationPasses.add(createInternalizePass());
  preparationPasses.add(createEliminateAvailableExternallyPass());
  preparationPasses.add(createGlobalDCEPass());
  preparationPasses.run(*module);

  legacy::PassManager modulePasses;
  modulePasses.add(
      createTargetTransformInfoWrapperPass(targetMachine->getTargetIRAnalysis()));

  legacy::FunctionPassManager functionPasses(module.get());
  functionPasses.add(
      createTargetTransformInfoWrapperPass(targetMachine->getTargetIRAnalysis()));

  createPasses(modulePasses, functionPasses);

  legacy::PassManager codeGenPasses;
  codeGenPasses.add(
      createTargetTransformInfoWrapperPass(targetMachine->getTargetIRAnalysis()));

  switch (config.outputKind) {
    case OUTPUT_KIND_BITCODE:
      modulePasses.add(createBitcodeWriterPass(os));
      break;
    case OUTPUT_KIND_OBJECT_FILE:
      targetMachine->addPassesToEmitFile(codeGenPasses, os, getCodeGenFileType());
      break;
  }

  functionPasses.doInitialization();
  for (Function &F : *module)
    if (!F.isDeclaration())
      functionPasses.run(F);
  functionPasses.doFinalization();

  modulePasses.run(*module);

  codeGenPasses.run(*module);

  return false;
}

Optional<Reloc::Model> KotlinNativeLlvmBackend::getRelocModel() {
  switch (config.relocMode) {
    case LLVMRelocDefault: return None;
    case LLVMRelocStatic: return Reloc::Model::Static;
    case LLVMRelocPIC: return Reloc::Model::PIC_;
    case LLVMRelocDynamicNoPic: return Reloc::Model::DynamicNoPIC;
  }
}

bool KotlinNativeLlvmBackend::createTargetMachine() {
  std::string error;
  const llvm::Target *target = TargetRegistry::lookupTarget(config.targetTriple, error);
  if (!target) {
    logging::error() << error;
    return true;
  }
  CodeModel::Model codeModel = CodeModel::Default; // TODO: add support for other code models
  llvm::TargetOptions options;
  targetMachine.reset(target->createTargetMachine(config.targetTriple,
                                                  getCPU(),
                                                  getTargetFeatures(),
                                                  options,
                                                  getRelocModel(),
                                                  codeModel,
                                                  getCodegenOptLevel()));

  return false;
}

TargetMachine::CodeGenFileType KotlinNativeLlvmBackend::getCodeGenFileType() {
  switch (config.outputKind) {
    case OUTPUT_KIND_OBJECT_FILE:return TargetMachine::CodeGenFileType::CGFT_ObjectFile;
    default:logging::error() << "Unsupported codegen file type!\n";
      return TargetMachine::CodeGenFileType::CGFT_Null;
  }
}

CodeGenOpt::Level KotlinNativeLlvmBackend::getCodegenOptLevel() {
  switch (config.optLevel) {
    case 0:
      return CodeGenOpt::Level::None;
    case 1:
      return CodeGenOpt::Level::Less;
    case 2:
      return CodeGenOpt::Level::Default;
    case 3:
      return CodeGenOpt::Level::Aggressive;
  }
}

std::string KotlinNativeLlvmBackend::getTargetFeatures() {
  SubtargetFeatures features("");
  features.getDefaultSubtargetFeatures(triple);
  return features.getString();
}

void KotlinNativeLlvmBackend::createPasses(legacy::PassManager &modulePasses,
                                           legacy::FunctionPassManager &functionPasses) {
  std::unique_ptr<TargetLibraryInfoImpl> tlii(new TargetLibraryInfoImpl(triple));

  PassManagerBuilder passManagerBuilder;
  if (optLevel <= 1) {
    passManagerBuilder.Inliner = createAlwaysInlinerLegacyPass();
  } else {
    passManagerBuilder.Inliner = createFunctionInliningPass(optLevel, sizeLevel, false);
  }
  passManagerBuilder.OptLevel = optLevel;
  passManagerBuilder.SizeLevel = sizeLevel;
  passManagerBuilder.SLPVectorize = optLevel > 1;
  passManagerBuilder.LoopVectorize = optLevel > 1;
  passManagerBuilder.PrepareForLTO = config.shouldPerformLto;
  passManagerBuilder.PrepareForThinLTO = config.shouldPerformLto;

  modulePasses.add(new TargetLibraryInfoWrapperPass(*tlii));

  targetMachine->adjustPassManager(passManagerBuilder);

  functionPasses.add(new TargetLibraryInfoWrapperPass(*tlii));
  if (!config.shouldPreserveDebugInfo) {
    modulePasses.add(createStripSymbolsPass(true));
  }
  passManagerBuilder.populateFunctionPassManager(functionPasses);
  passManagerBuilder.populateModulePassManager(modulePasses);
  if (config.shouldPerformLto) {
    passManagerBuilder.populateLTOPassManager(modulePasses);
  }
}
