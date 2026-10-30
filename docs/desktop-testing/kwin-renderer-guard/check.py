#!/usr/bin/env python3
# Compile and execute the exact production verifyRendererMatchesMode body.
import argparse,pathlib,subprocess
p=argparse.ArgumentParser()
p.add_argument('source');p.add_argument('--compiler',required=True);p.add_argument('--qt-prefix',required=True);p.add_argument('--output',required=True)
a=p.parse_args();root=pathlib.Path(a.output);root.mkdir(parents=True,exist_ok=True)
source=pathlib.Path(a.source).read_text();start=source.index('static bool verifyRendererMatchesMode(');end=source.index('AndroidEglBackend::RenderingMode AndroidEglBackend::renderingModeFromEnvironment()',start)
body=source[start:end]
preamble='''#include <QByteArray>
#include <QDebug>
#include <cstdio>
using GLenum = unsigned int;
constexpr GLenum GL_RENDERER = 0x1F01;
static const char *testRenderer;
static const unsigned char *glGetString(GLenum) { return reinterpret_cast<const unsigned char *>(testRenderer); }
struct AndroidEglBackend { enum class RenderingMode { SystemGlesHardware, ZinkHardware, LlvmpipeSoftware, Invalid }; };
static const char *renderingModeName(AndroidEglBackend::RenderingMode) { return "test"; }
'''
main='''int main() {
using M = AndroidEglBackend::RenderingMode;
struct Row { const char *renderer; bool system; bool zink; bool llvmpipe; };
const Row rows[] = {
 {"Adreno (TM) 660", true, false, false},
 {"zink Vulkan 1.4 (Turnip Adreno 660)", false, true, false},
 {"llvmpipe (LLVM 20.1.8, 128 bits)", false, false, true},
 {"zink Vulkan 1.4 (llvmpipe LLVM 20)", false, false, false},
 {"zink Vulkan 1.4 (lavapipe)", false, false, false},
 {"softpipe", false, false, false},
 {"", false, false, false},
};
bool all = true;
for (const auto &row : rows) {
 testRenderer = row.renderer;
 const bool expected[] = {row.system, row.zink, row.llvmpipe};
 const M modes[] = {M::SystemGlesHardware, M::ZinkHardware, M::LlvmpipeSoftware};
 for (int i = 0; i < 3; ++i) {
  const bool got = verifyRendererMatchesMode(modes[i]);
  std::printf("renderer=%s mode=%d got=%d expected=%d %s\\n", row.renderer, i, got, expected[i], got == expected[i] ? "PASS" : "FAIL");
  all &= got == expected[i];
 }
}
return all ? 0 : 1;
}
'''
cpp=root/'check.cpp';cpp.write_text(preamble+body+main)
qt=pathlib.Path(a.qt_prefix)
subprocess.run([a.compiler,'-std=c++17','-fPIC',str(cpp),'-I'+str(qt/'include/qt6'),'-I'+str(qt/'include/qt6/QtCore'),'-L'+str(qt/'lib'),'-Wl,-rpath,'+str(qt/'lib'),'-lQt6Core','-o',str(root/'check')],check=True)
raise SystemExit(subprocess.run([str(root/'check')]).returncode)
