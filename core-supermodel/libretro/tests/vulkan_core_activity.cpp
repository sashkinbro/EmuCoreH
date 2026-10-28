// SPDX-License-Identifier: GPL-3.0-or-later
// A separate translation unit keeps the standalone probes' private state apart.
#define main Model3VulkanCoreProbe
#include "vulkan_core_lifecycle.cpp"
#undef main
int Model3RunRetailValidation(const char* directory){
  Dl_info own{};check(dladdr(reinterpret_cast<const void*>(&Model3RunRetailValidation),&own)!=0,"QA native library directory");
  const auto base=std::filesystem::path(directory);
  // Preserve the ROM-set basename: Supermodel uses it to choose Games.xml definitions.
  std::string romName;
  std::ifstream marker(base/"retail.enabled");
  std::getline(marker,romName);
  if(romName.empty())romName="owner.zip";
  check(std::filesystem::path(romName).filename()==std::filesystem::path(romName) &&
        std::filesystem::path(romName).extension()==".zip","local retail ROM-set filename");
  std::vector<std::string> strings={"model3-retail-validation",
    (std::filesystem::path(own.dli_fname).parent_path()/"libsupermodel_libretro.so").string(),
    (base/"system").string(),(base/romName).string(),(base/"retail-save").string(),(base/"retail").string(),
    std::filesystem::exists(base/"state.rstate")?"1":"1800"};
  if(std::filesystem::exists(base/"state.rstate"))strings.push_back((base/"state.rstate").string());
  std::vector<char*> args;for(auto& item:strings)args.push_back(item.data());
  setenv("MODEL3_VULKAN_VALIDATION","1",1);
  return Model3VulkanCoreProbe(static_cast<int>(args.size()),args.data());
}
