// SPDX-License-Identifier: GPL-3.0-or-later
#include "Graphics/New3D/SceneSnapshot.h"
#include <cassert>
#include <sstream>
int main() {
  using namespace New3D;
  std::vector<Node> nodes(1);
  std::vector<FVertex> ram(3), rom;
  std::vector<UINT16> textures(2048 * 2048);
  nodes[0].viewport = {};
  nodes[0].viewport.priority = 2;
  nodes[0].viewport.projectionMatrix.LoadIdentity();
  nodes[0].viewport.fogParams[3] = .25f;
  Model model;
  model.modelMat[3] = .75f;
  model.meshes = std::make_shared<std::vector<Mesh>>(1);
  model.meshes->front().vertexCount = 3;
  nodes[0].models.push_back(model);
  textures[123] = 0x1234;
  ram[1].pos[2] = -45;
  SceneFrame frame{nodes, ram, rom, textures.data(), 500000, 992, 768};
  frame.zRanges[2] = {-1, -100};
  std::ostringstream output(std::ios::binary);
  assert(WriteSceneSnapshot(output, frame));
  OwnedSceneSnapshot snapshot;
  std::istringstream input(output.str(), std::ios::binary);
  assert(ReadSceneSnapshot(input, snapshot));
  auto restored = snapshot.Frame();
  assert(restored.width == 992 && restored.height == 768 &&
         restored.ramBase == 500000);
  assert(restored.ram[1].pos[2] == -45 && restored.textureRAM[123] == 0x1234);
  assert(restored.nodes[0].viewport.priority == 2 &&
         restored.nodes[0].viewport.fogParams[3] == .25f);
  assert(restored.nodes[0].models[0].modelMat[3] == .75f);
  assert(restored.nodes[0].models[0].meshes->front().vertexCount == 3);
  assert(restored.zRanges[2][1] == -100);
  std::istringstream truncated(output.str().substr(0, 100), std::ios::binary);
  assert(!ReadSceneSnapshot(truncated, snapshot));
  assert(snapshot.Frame().width == 992);
  std::istringstream corrupt("invalid", std::ios::binary);
  assert(!ReadSceneSnapshot(corrupt, snapshot));
}
