#pragma once

#include <GLES3/gl3.h>

namespace emucoreh::shader_chain_present {

bool Present(GLuint source_fbo, int source_x, int source_y, int source_width, int source_height,
             int window_width, int window_height,
             int destination_x, int destination_y, int destination_width, int destination_height);
void Destroy();

}  // namespace emucoreh::shader_chain_present
