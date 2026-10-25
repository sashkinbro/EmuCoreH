// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "Model.h"
#include <array>

namespace New3D {

// Borrowed CPU scene; valid only during SceneConsumer::Render. No graphics API
// objects or savestate ownership cross this boundary.
struct SceneFrame {
  const std::vector<Node>& nodes;
  const std::vector<FVertex>& ram;
  const std::vector<FVertex>& rom;
  const UINT16* textureRAM;
  size_t ramBase;
  unsigned width, height;
  std::array<std::array<float,2>,4> zRanges{}; // original signed near/far for guest LOS

  struct VertexView { const FVertex* data=nullptr; size_t count=0; };
  VertexView Vertices(const Model& model, const Mesh& mesh) const {
    if (mesh.vboOffset < 0 || mesh.vertexCount <= 0) return {};
    const auto& buffer=model.dynamic ? ram : rom;
    const size_t base=model.dynamic ? ramBase : 0;
    const size_t offset=static_cast<size_t>(mesh.vboOffset);
    const size_t count=static_cast<size_t>(mesh.vertexCount);
    if (offset < base || offset-base > buffer.size() || count > buffer.size()-(offset-base)) return {};
    return {buffer.data()+offset-base,count};
  }
  struct Origin { int x,y; };
  static Origin TextureOrigin(const Model& model, const Mesh& mesh) {
    return {(mesh.x+model.textureOffsetX)&2047,
      ((mesh.y+model.textureOffsetY)&1023)+(((mesh.y/1024+model.page)&1)*1024)};
  }
  static bool Draws(const Mesh& mesh, Layer layer, bool overlay) {
    if (mesh.highPriority!=overlay) return false;
    switch (layer) {
      case Layer::colour:return !mesh.polyAlpha;
      case Layer::trans1:return (mesh.textureAlpha||mesh.polyAlpha)&&!mesh.transLSelect;
      case Layer::trans2:return (mesh.textureAlpha||mesh.polyAlpha)&&mesh.transLSelect;
      default:return false;
    }
  }
};

class SceneConsumer {
public:
  virtual ~SceneConsumer()=default;
  // Return guest line-of-sight values for the four priority planes.
  virtual std::array<float,4> Render(const SceneFrame& frame)=0;
  virtual void InvalidateTextures(unsigned level,unsigned x,unsigned y,unsigned width,unsigned height)=0;
};
}
