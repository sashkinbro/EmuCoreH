// SPDX-License-Identifier: GPL-3.0-or-later
#include "Model3/DriveBoard/SkiBoard.h"
#include "BlockFile.h"
#include "Inputs/Input.h"
#include "state_safety.h"
#include <array>
#include <cassert>
#include <filesystem>
#include <fstream>
#include <vector>
#include <cstdarg>
// The board test has no host input/output system or logger.
void DebugLog(const char*,...) {}
void InfoLog(const char*,...) {}
bool ErrorLog(const char*,...) {return true;}
bool CInput::SendForceFeedbackCmd(ForceFeedbackCmd) {
  assert(false&&"Protocol-only Ski board must never request physical output");return false;
}
static std::vector<unsigned char> bytes(const std::filesystem::path& path) {
  std::ifstream file(path,std::ios::binary|std::ios::ate);assert(file);
  std::vector<unsigned char> result(static_cast<size_t>(file.tellg()));file.seekg(0);
  file.read(reinterpret_cast<char*>(result.data()),result.size());return result;
}
static void save(CSkiBoard& board,const std::filesystem::path& path) {
  CBlockFile file;assert(file.Create(path.string(),"Ski test","Self-authored protocol sequence")==0);
  board.SaveState(&file);file.Close();
  assert(file.Load(path.string())==0);assert(file.FindBlock("Ski Board Protocol")==0);
  std::array<unsigned char,6> snapshot{};assert(file.Read(snapshot.data(),snapshot.size())==snapshot.size());file.Close();
  assert(model3retro::safe_state_block("Ski Board Protocol",snapshot.data(),snapshot.size()));
  assert(!model3retro::safe_state_block("Ski Board Protocol",snapshot.data(),5));
  auto bad=snapshot;bad[0]=2;assert(!model3retro::safe_state_block("Ski Board Protocol",bad.data(),bad.size()));
  bad=snapshot;bad[3]=8;assert(!model3retro::safe_state_block("Ski Board Protocol",bad.data(),bad.size()));
}
static void load(CSkiBoard& board,const std::filesystem::path& path) {
  CBlockFile file;assert(file.Load(path.string())==0);board.LoadState(&file);file.Close();
}
int main() {
  Util::Config::Node config("root");config.Set("ForceFeedback",false);config.Set("EmulateSkiBoard",true);
  std::array<unsigned char,65536> noGameRom{};
  CSkiBoard board(config);assert(board.Init(noGameRom.data())==0);board.Reset();
  assert(board.Read()==0xcf); // The protocol must remain enabled without a motor/input source.
  for(unsigned i=0;i<5;i++)board.RunFrame();assert(board.Read()==0xce);
  const auto root=std::filesystem::temp_directory_path()/"emucoreh-ski-board-test";
  std::filesystem::create_directories(root);auto state=root/"state.bin",expected=root/"future.bin",actual=root/"actual.bin";
  save(board,state);
  const auto finishBoot=[&board] {
    for(unsigned i=0;i<5;i++)board.RunFrame();assert(board.Read()==0xcd);
    for(unsigned i=0;i<5;i++)board.RunFrame();assert(board.Read()==0xcc);
    for(unsigned i=0;i<5;i++)board.RunFrame();assert(board.Read()==0x80);
  };
  finishBoot();
  save(board,expected);load(board,state);
  finishBoot();
  save(board,actual);assert(bytes(actual)==bytes(expected));
  board.Write(0xd0);for(unsigned i=0;i<250;i++)board.Read();
  board.Write(0x44); // A vibrate command with no CInputs attached must be harmless.
  board.SetDIPSwitches(0xc7);
  save(board,state);std::array<unsigned char,20> future{};
  for(auto& n:future)n=board.Read();save(board,expected);
  board.Write(0xd4);board.Read();board.Write(0x40);board.SetDIPSwitches(0xcf);load(board,state);
  board.Write(0xd1);assert(board.Read()==0xc7);load(board,state);
  for(auto n:future)assert(board.Read()==n);save(board,actual);assert(bytes(actual)==bytes(expected));
  board.Reset();assert(board.Read()==0xcf);
  Util::Config::Node disabledConfig("disabled");disabledConfig.Set("ForceFeedback",false);
  CSkiBoard disabled(disabledConfig);assert(disabled.Init(noGameRom.data())==0);disabled.Reset();assert(disabled.Read()==0xff);
  std::filesystem::remove_all(root);
}
