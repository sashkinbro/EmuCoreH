// SPDX-License-Identifier: GPL-3.0-or-later
// QA-only capture/replay of borrowed CPU scenes; never a guest savestate
// format.
#pragma once
#include "SceneFrame.h"
#include <cstdint>
#include <istream>
#include <ostream>
#include <type_traits>
namespace New3D {
struct OwnedSceneSnapshot {
  std::vector<Node> nodes;
  std::vector<FVertex> ram, rom;
  std::vector<UINT16> textures;
  uint64_t ramBase = 0;
  unsigned width = 0, height = 0;
  std::array<std::array<float, 2>, 4> zRanges{};
  SceneFrame Frame() const {
    return {nodes,
            ram,
            rom,
            textures.empty() ? nullptr : textures.data(),
            size_t(ramBase),
            width,
            height,
            zRanges};
  }
};
namespace SnapshotDetail {
struct Writer {
  std::ostream &stream;
  template <class T> void operator()(const T &value) {
    static_assert(std::is_trivially_copyable<T>::value);
    stream.write(reinterpret_cast<const char *>(&value), sizeof(value));
  }
};
struct Reader {
  std::istream &stream;
  template <class T> void operator()(T &value) {
    static_assert(std::is_trivially_copyable<T>::value);
    stream.read(reinterpret_cast<char *>(&value), sizeof(value));
  }
};
template <class IO> void View(IO &io, Viewport &v) {
  io(v.vpX);
  io(v.vpY);
  io(v.vpWidth);
  io(v.vpHeight);
  io(v.angle_left);
  io(v.angle_right);
  io(v.angle_top);
  io(v.angle_bottom);
  io(v.projectionMatrix.currentMatrix);
  io(v.lightingParams);
  io(v.sunClamp);
  io(v.intensityClamp);
  io(v.spotEllipse);
  io(v.spotRange);
  io(v.spotColor);
  io(v.fogParams);
  io(v.scrollFog);
  io(v.losPosX);
  io(v.losPosY);
  io(v.x);
  io(v.y);
  io(v.width);
  io(v.height);
  io(v.priority);
  io(v.select);
  io(v.number);
  io(v.spotFogColor);
  io(v.scrollAtt);
  io(v.hardwareStep);
}
} // namespace SnapshotDetail
inline bool WriteSceneSnapshot(std::ostream &stream, const SceneFrame &frame) {
  static_assert(std::is_trivially_copyable<Mesh>::value &&
                std::is_trivially_copyable<FVertex>::value);
  SnapshotDetail::Writer io{stream};
  const UINT32 magic = 0x4d335343, version = 1, vertexSize = sizeof(FVertex),
               meshSize = sizeof(Mesh);
  io(magic);
  io(version);
  io(vertexSize);
  io(meshSize);
  io(frame.width);
  io(frame.height);
  const uint64_t ramBase = frame.ramBase;
  io(ramBase);
  const UINT32 ram = UINT32(frame.ram.size()), rom = UINT32(frame.rom.size()),
               nodes = UINT32(frame.nodes.size()),
               textures = frame.textureRAM ? 2048 * 2048 : 0;
  io(ram);
  io(rom);
  io(nodes);
  io(textures);
  io(frame.zRanges);
  stream.write(reinterpret_cast<const char *>(frame.ram.data()),
               size_t(ram) * sizeof(FVertex));
  stream.write(reinterpret_cast<const char *>(frame.rom.data()),
               size_t(rom) * sizeof(FVertex));
  if (textures)
    stream.write(reinterpret_cast<const char *>(frame.textureRAM),
                 textures * sizeof(UINT16));
  for (const auto &node : frame.nodes) {
    auto viewport = node.viewport;
    SnapshotDetail::View(io, viewport);
    const UINT32 models = UINT32(node.models.size());
    io(models);
    for (const auto &model : node.models) {
      io(model.dynamic);
      io(model.textureOffsetX);
      io(model.textureOffsetY);
      io(model.page);
      io(model.modelMat);
      io(model.scale);
      const UINT32 meshes = model.meshes ? UINT32(model.meshes->size()) : 0;
      io(meshes);
      if (meshes)
        stream.write(reinterpret_cast<const char *>(model.meshes->data()),
                     size_t(meshes) * sizeof(Mesh));
    }
  }
  return bool(stream);
}
inline bool ReadSceneSnapshot(std::istream &stream,
                              OwnedSceneSnapshot &result) {
  SnapshotDetail::Reader io{stream};
  UINT32 magic = 0, version = 0, vertexSize = 0, meshSize = 0;
  io(magic);
  io(version);
  io(vertexSize);
  io(meshSize);
  if (!stream || magic != 0x4d335343 || version != 1 ||
      vertexSize != sizeof(FVertex) || meshSize != sizeof(Mesh))
    return false;
  OwnedSceneSnapshot scene;
  io(scene.width);
  io(scene.height);
  io(scene.ramBase);
  UINT32 ram = 0, rom = 0, nodes = 0, textures = 0;
  io(ram);
  io(rom);
  io(nodes);
  io(textures);
  io(scene.zRanges);
  if (!stream || !scene.width || !scene.height || scene.width > 8192 ||
      scene.height > 8192 || ram > 2000000 || rom > 2000000 || nodes > 1024 ||
      (textures && textures != 2048 * 2048))
    return false;
  scene.ram.resize(ram);
  scene.rom.resize(rom);
  scene.textures.resize(textures);
  scene.nodes.resize(nodes);
  stream.read(reinterpret_cast<char *>(scene.ram.data()),
              size_t(ram) * sizeof(FVertex));
  stream.read(reinterpret_cast<char *>(scene.rom.data()),
              size_t(rom) * sizeof(FVertex));
  if (textures)
    stream.read(reinterpret_cast<char *>(scene.textures.data()),
                textures * sizeof(UINT16));
  if (!stream)
    return false;
  size_t totalModels = 0, totalMeshes = 0;
  for (auto &node : scene.nodes) {
    SnapshotDetail::View(io, node.viewport);
    UINT32 models = 0;
    io(models);
    totalModels += models;
    if (!stream || totalModels > 100000)
      return false;
    node.models.resize(models);
    for (auto &model : node.models) {
      io(model.dynamic);
      io(model.textureOffsetX);
      io(model.textureOffsetY);
      io(model.page);
      io(model.modelMat);
      io(model.scale);
      UINT32 meshes = 0;
      io(meshes);
      totalMeshes += meshes;
      if (!stream || totalMeshes > 100000)
        return false;
      model.meshes = std::make_shared<std::vector<Mesh>>(meshes);
      if (meshes)
        stream.read(reinterpret_cast<char *>(model.meshes->data()),
                    size_t(meshes) * sizeof(Mesh));
      if (!stream)
        return false;
    }
  }
  result = std::move(scene);
  return true;
}
} // namespace New3D
