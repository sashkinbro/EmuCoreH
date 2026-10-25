// SPDX-License-Identifier: GPL-3.0-or-later
#include "Graphics/New3D/SceneFrame.h"
#include <cassert>
#include <climits>
int main() {
  using namespace New3D;
  std::vector<Node> nodes;
  std::vector<FVertex> ram(6),rom(9);
  SceneFrame frame{nodes,ram,rom,nullptr,500000,496,384};
  Model dynamic; Mesh mesh;
  mesh.vboOffset=500003;mesh.vertexCount=3;
  auto vertices=frame.Vertices(dynamic,mesh);
  assert(vertices.data==ram.data()+3 && vertices.count==3);
  mesh.vertexCount=4;assert(frame.Vertices(dynamic,mesh).count==0);
  mesh.vboOffset=499999;mesh.vertexCount=1;assert(frame.Vertices(dynamic,mesh).count==0);
  mesh.vboOffset=INT_MAX;mesh.vertexCount=INT_MAX;assert(frame.Vertices(dynamic,mesh).count==0);
  dynamic.dynamic=false;mesh.vboOffset=6;mesh.vertexCount=3;
  assert(frame.Vertices(dynamic,mesh).data==rom.data()+6);
  mesh.vertexCount=-1;assert(frame.Vertices(dynamic,mesh).count==0);
  Model model; model.textureOffsetX=64;model.textureOffsetY=32;model.page=1;
  mesh.x=2016;mesh.y=1008;
  auto texture=SceneFrame::TextureOrigin(model,mesh);
  assert(texture.x==32 && texture.y==1040);
  mesh.polyAlpha=false;mesh.textureAlpha=true;mesh.transLSelect=false;
  assert(SceneFrame::Draws(mesh,Layer::colour,false));
  assert(SceneFrame::Draws(mesh,Layer::trans1,false));
  assert(!SceneFrame::Draws(mesh,Layer::trans2,false));
  mesh.highPriority=true;
  assert(!SceneFrame::Draws(mesh,Layer::colour,false));
  assert(SceneFrame::Draws(mesh,Layer::colour,true));
}
