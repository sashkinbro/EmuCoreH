// SPDX-License-Identifier: GPL-3.0-or-later
// Separate debuggable QA package; no existing app or global layer settings.
#define main Model3VulkanRasterProbe
#include "vulkan_scene_lifecycle.cpp"
#undef main
#include <android/native_activity.h>
#include <android/log.h>
#include <thread>
#include <unistd.h>
int Model3RunRetailValidation(const char* directory);
extern "C" __attribute__((visibility("default"))) void ANativeActivity_onCreate(ANativeActivity* activity,void*,size_t){
 std::thread([activity]{
  std::string path=std::string(activity->internalDataPath)+"/vulkan-validation.txt";
  FILE* report=std::freopen(path.c_str(),"w",stdout);
  if(!report){__android_log_print(ANDROID_LOG_ERROR,"Model3VulkanQA","Unable to open result file");ANativeActivity_finish(activity);return;}
  dup2(fileno(stdout),fileno(stderr));setvbuf(stdout,nullptr,_IOLBF,0);setvbuf(stderr,nullptr,_IOLBF,0);
  std::printf("Model3 Vulkan QA app-local validation starting\n");
  char name[]="model3-vulkan-qa",validation[]="--validation";char* args[]={name,validation};
  const bool retail=access((std::string(activity->internalDataPath)+"/retail.enabled").c_str(),F_OK)==0;
  int result=retail?Model3RunRetailValidation(activity->internalDataPath):Model3VulkanRasterProbe(2,args);
  std::printf("RESULT exit=%d\n",result);std::fflush(nullptr);
  __android_log_print(ANDROID_LOG_INFO,"Model3VulkanQA","Completed result=%d; report=%s",result,path.c_str());
  ANativeActivity_finish(activity);
 }).detach();
}
