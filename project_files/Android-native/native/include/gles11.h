/* Header shim used by the pas2c output: the engine's MOBILE build selects the
 * Pascal unit "gles11" (OpenGL ES 1.1) in place of "GL". */
#pragma once
#include <GLES/gl.h>
#include <GLES/glext.h>

/* Not part of GLES 1.1 core; Android drivers accept it via GL_EXT_bgra / format conversion. */
#ifndef GL_BGRA
#define GL_BGRA 0x80E1
#endif
