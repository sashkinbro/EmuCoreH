// SPDX-License-Identifier: GPL-3.0-or-later
#include "Sound/MPEG/MpegAudio.h"
#include "BlockFile.h"
#include "state_safety.h"
#include <cassert>
#include <fstream>
#include <vector>
#include <algorithm>
#include <filesystem>
#include <iostream>
using namespace model3retro;
static std::vector<unsigned char> bytes(const std::filesystem::path &path) {
    std::ifstream file(path,std::ios::binary|std::ios::ate);assert(file);
    std::vector<unsigned char> data(static_cast<size_t>(file.tellg()));file.seekg(0);
    file.read(reinterpret_cast<char*>(data.data()),data.size());return data;
}
static void save(const std::filesystem::path &path,const std::vector<unsigned char>& rom) {
    CBlockFile file;assert(!file.Create(path.string(),"MPEG Test","Authored stereo fixture"));
    MpegDec::SaveState(&file,rom.data(),rom.size());file.Close();
    auto data=bytes(path);size_t offset=read32(data.data());
    const auto header=12+read32(data.data()+offset+4)+read32(data.data()+offset+8);
    assert(read32(data.data()+offset)-header==11321);
    assert(safe_state_block("MPEG Decoder",data.data()+offset+header,11321));
}
static void load(const std::filesystem::path &path,const std::vector<unsigned char>& rom) {
    CBlockFile file;assert(!file.Load(path.string()));MpegDec::LoadState(&file,rom.data(),rom.size());file.Close();
}
static std::vector<int16_t> decode(int count) {
    std::vector<int16_t> left(count),right(count),interleaved;
    MpegDec::DecodeAudio(left.data(),right.data(),count);
    for(int i=0;i<count;i++){interleaved.push_back(left[i]);interleaved.push_back(right[i]);}
    return interleaved;
}
int main(int argc,char** argv) {
    assert(argc==2);const auto rom=bytes(argv[1]);assert(rom.size()>1000);
    const auto root=std::filesystem::temp_directory_path()/"emucoreh-mpeg-state-test";
    std::filesystem::create_directories(root);const auto state=root/"snapshot.bin",future=root/"future.bin",actual=root/"actual.bin";
    MpegDec::Reset();assert(!MpegDec::IsLoaded());
    MpegDec::SetMemory(rom.data(),static_cast<int>(rom.size()),true);
    const auto initial=decode(1365);assert(std::any_of(initial.begin(),initial.end(),[](auto n){return n!=0;}));
    save(state,rom);const auto expected=decode(1500);save(future,rom);
    decode(777);MpegDec::Reset();load(state,rom);
    assert(decode(1500)==expected);save(actual,rom);assert(bytes(actual)==bytes(future));
    // Stop must remain silent after restore, even while a track is still loaded.
    MpegDec::Stop();save(state,rom);MpegDec::Reset();load(state,rom);
    const auto silence=decode(1500);assert(std::all_of(silence.begin(),silence.end(),[](auto n){return n==0;}));
    // A loop changes the buffer base before the initial preface is exhausted.
    MpegDec::SetMemory(rom.data(),static_cast<int>(rom.size()),false);
    MpegDec::UpdateMemory(rom.data()+576,static_cast<int>(rom.size()-576),true);
    assert(MpegDec::GetPosition()==-576);save(state,rom);const auto preface=decode(1500);
    MpegDec::Reset();load(state,rom);assert(decode(1500)==preface);
    // Imported states pointing at non-MPEG ROM data cannot hang the frame worker.
    std::vector<unsigned char> empty(4096);MpegDec::SetMemory(empty.data(),empty.size(),true);
    const auto invalid=decode(1000);assert(std::all_of(invalid.begin(),invalid.end(),[](auto n){return n==0;}));
    MpegDec::Reset();std::filesystem::remove_all(root);
    std::cout<<"Nonzero stereo MPEG replay, filter/PCM history, stopped tracks, loop prefaces and invalid-stream progress passed\n";
}
