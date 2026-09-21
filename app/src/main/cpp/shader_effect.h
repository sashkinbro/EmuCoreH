#pragma once

#include <GLES3/gl3.h>

namespace emucoreh::shader_effect {

// 0 = off, 1 = CRT, 2 = LCD, 3 = sharp bilinear, 4 = nearest, 5 = bilinear.
void Set(int effect);
bool Present(GLuint texture, int texture_width, int texture_height,
             int source_x, int source_y, int source_width, int source_height,
             int window_height,
             int destination_x, int destination_y, int destination_width, int destination_height);
void Destroy();

}  // namespace emucoreh::shader_effect
