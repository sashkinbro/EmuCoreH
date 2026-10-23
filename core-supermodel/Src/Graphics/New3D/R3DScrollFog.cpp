#include "R3DScrollFog.h"
#include "Mat4.h"
#include <cstdio>
#include <string>

namespace New3D {

#ifndef __ANDROID__
#include "Graphics/Shader.h"
#endif

#ifdef __ANDROID__
namespace {
static void LogShaderError(GLuint shader, const char* label)
{
	GLint ok = 0;
	glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
	if (ok == GL_TRUE) return;
	GLint len = 0;
	glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &len);
	if (len <= 0) return;
	std::string msg;
	msg.resize(static_cast<size_t>(len));
	GLsizei written = 0;
	glGetShaderInfoLog(shader, len, &written, msg.data());
	fprintf(stderr, "R3DScrollFog %s compile error: %s\n", label ? label : "shader", msg.c_str());
}

static void LogProgramError(GLuint program)
{
	GLint ok = 0;
	glGetProgramiv(program, GL_LINK_STATUS, &ok);
	if (ok == GL_TRUE) return;
	GLint len = 0;
	glGetProgramiv(program, GL_INFO_LOG_LENGTH, &len);
	if (len <= 0) return;
	std::string msg;
	msg.resize(static_cast<size_t>(len));
	GLsizei written = 0;
	glGetProgramInfoLog(program, len, &written, msg.data());
	fprintf(stderr, "R3DScrollFog link error: %s\n", msg.c_str());
}

static bool CompileShaderProgram(GLuint* programOut, GLuint* vsOut, GLuint* fsOut, const char* vsSrc, const char* fsSrc)
{
	if (!programOut || !vsOut || !fsOut) return false;
	*programOut = 0;
	*vsOut = 0;
	*fsOut = 0;

	GLuint vs = glCreateShader(GL_VERTEX_SHADER);
	glShaderSource(vs, 1, &vsSrc, nullptr);
	glCompileShader(vs);
	LogShaderError(vs, "vertex");

	GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
	glShaderSource(fs, 1, &fsSrc, nullptr);
	glCompileShader(fs);
	LogShaderError(fs, "fragment");

	GLuint program = glCreateProgram();
	glAttachShader(program, vs);
	glAttachShader(program, fs);
	glLinkProgram(program);
	LogProgramError(program);

	GLint ok = 0;
	glGetProgramiv(program, GL_LINK_STATUS, &ok);
	if (ok != GL_TRUE) {
		glDeleteProgram(program);
		glDeleteShader(vs);
		glDeleteShader(fs);
		return false;
	}

	*programOut = program;
	*vsOut = vs;
	*fsOut = fs;
	return true;
}
} // namespace
#endif

#ifdef __ANDROID__
static const char *vertexShaderFog = R"glsl(#version 300 es
precision highp float;

uniform mat4 mvp;
in vec3 inVertex;

void main(void)
{
	gl_Position = mvp * vec4(inVertex,1.0);
}

)glsl";

static const char *fragmentShaderFog = R"glsl(#version 300 es
precision mediump float;

uniform float	fogAttenuation;
uniform float	fogAmbient;
uniform vec4	fogColour;
uniform vec3	spotFogColor;
uniform vec4	spotEllipse;

out vec4 oColor;

// Spotlight on fog
float	ellipse;
vec2	position, size;
vec3	lSpotFogColor;

// Scroll fog
vec3	lFogColor;
vec4	scrollFog;

void main()
{
	// Scroll fog base color
	lFogColor = fogColour.rgb * fogAmbient;

	// Spotlight on fog (area)
	position = spotEllipse.xy;
	size = spotEllipse.zw;
	ellipse = length((gl_FragCoord.xy - position) / size);
	ellipse = ellipse * ellipse;			// decay rate = square of distance from center
	ellipse = 1.0 - ellipse;				// invert
	ellipse = max(0.0, ellipse);			// clamp

	// Spotlight on fog (color)
	lSpotFogColor = mix(spotFogColor * ellipse * fogColour.rgb, vec3(0.0), fogAttenuation);

	// Scroll fog density
	scrollFog = vec4(lFogColor + lSpotFogColor, fogColour.a);

	// Final Color
	oColor = scrollFog;
}

)glsl";
#else
static const char *vertexShaderFog = R"glsl(

#version 120

uniform mat4 mvp;
attribute vec3 inVertex; 

void main(void)
{
	gl_Position = mvp * vec4(inVertex,1.0);
}

)glsl";

static const char *fragmentShaderFog = R"glsl(

#version 120

uniform float	fogAttenuation;
uniform float	fogAmbient;
uniform vec4	fogColour;
uniform vec3	spotFogColor;
uniform vec4	spotEllipse;

// Spotlight on fog
float	ellipse;
vec2	position, size;
vec3	lSpotFogColor;

// Scroll fog
float	lfogAttenuation;
vec3	lFogColor;
vec4	scrollFog;

void main()
{
	// Scroll fog base color
	lFogColor = fogColour.rgb * fogAmbient;

	// Spotlight on fog (area) 
	position = spotEllipse.xy;
	size = spotEllipse.zw;
	ellipse = length((gl_FragCoord.xy - position) / size);
	ellipse = ellipse * ellipse;			// decay rate = square of distance from center
	ellipse = 1.0 - ellipse;				// invert
	ellipse = max(0.0, ellipse);			// clamp

	// Spotlight on fog (color)
	lSpotFogColor = mix(spotFogColor * ellipse * fogColour.rgb, vec3(0.0), fogAttenuation);

	// Scroll fog density
	scrollFog = vec4(lFogColor + lSpotFogColor, fogColour.a);

	// Final Color
	gl_FragColor = scrollFog;
}

)glsl";
#endif


R3DScrollFog::R3DScrollFog(const Util::Config::Node &config)
  : m_config(config)
{
	//default coordinates are NDC -1,1 etc

	m_triangles[0].p1.Set(-1,-1, 0);
	m_triangles[0].p2.Set(-1, 1, 0);
	m_triangles[0].p3.Set( 1, 1, 0);

	m_triangles[1].p1.Set(-1,-1, 0);
	m_triangles[1].p2.Set( 1, 1, 0);
	m_triangles[1].p3.Set( 1,-1, 0);

	m_shaderProgram		= 0;
	m_vertexShader		= 0;
	m_fragmentShader	= 0;

	AllocResources();
}

R3DScrollFog::~R3DScrollFog()
{
	DeallocResources();
}

void R3DScrollFog::DrawScrollFog(float rgba[4], float attenuation, float ambient, float *spotRGB, float *spotEllipse)
{
	//=======
	Mat4 mvp;
	//=======

	// yeah this would have been much easier with immediate mode and fixed function ..  >_<

	// some ogl states
	glDepthMask			(GL_FALSE);			// disable z writes
	glDisable			(GL_DEPTH_TEST);	// disable depth testing
	glEnable			(GL_BLEND);
	glBlendFunc			(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

	m_vbo.Bind			(true);
	glUseProgram		(m_shaderProgram);
	glUniform4f			(m_locFogColour, rgba[0], rgba[1], rgba[2], rgba[3]);
	glUniform1f			(m_locFogAttenuation, attenuation);
	glUniform1f			(m_locFogAmbient, ambient);
	glUniform3f			(m_locSpotFogColor, spotRGB[0], spotRGB[1], spotRGB[2]);
	glUniform4f			(m_locSpotEllipse, spotEllipse[0], spotEllipse[1], spotEllipse[2], spotEllipse[3]);
	glUniformMatrix4fv	(m_locMVP, 1, GL_FALSE, mvp);

	glEnableVertexAttribArray	(m_locInVertex);
	glVertexAttribPointer		(m_locInVertex, 3, GL_FLOAT, GL_FALSE, sizeof(SFVertex), 0);
	glDrawArrays				(GL_TRIANGLES, 0, 6);
	glDisableVertexAttribArray	(m_locInVertex);

	glUseProgram		(0);
	m_vbo.Bind			(false);

	glDisable			(GL_BLEND);
	glDepthMask			(GL_TRUE);
}

void R3DScrollFog::AllocResources()
{
#ifdef __ANDROID__
	bool success = CompileShaderProgram(&m_shaderProgram, &m_vertexShader, &m_fragmentShader, vertexShaderFog, fragmentShaderFog);
#else
	bool success =
		LoadShaderProgram(
			&m_shaderProgram,
			&m_vertexShader,
			&m_fragmentShader,
			m_config["VertexShaderFog"].ValueAsDefault<std::string>(""),
			m_config["FragmentShaderFog"].ValueAsDefault<std::string>(""),
			vertexShaderFog,
			fragmentShaderFog
		);
#endif
	if (!success) {
		m_shaderProgram = 0;
		m_vertexShader = 0;
		m_fragmentShader = 0;
		return;
	}

	m_locMVP			= glGetUniformLocation(m_shaderProgram, "mvp");
	m_locFogColour		= glGetUniformLocation(m_shaderProgram, "fogColour");
	m_locFogAttenuation	= glGetUniformLocation(m_shaderProgram, "fogAttenuation");
	m_locFogAmbient		= glGetUniformLocation(m_shaderProgram, "fogAmbient");
	m_locSpotFogColor	= glGetUniformLocation(m_shaderProgram, "spotFogColor");
	m_locSpotEllipse	= glGetUniformLocation(m_shaderProgram, "spotEllipse");

	m_locInVertex		= glGetAttribLocation(m_shaderProgram, "inVertex");

	m_vbo.Create(GL_ARRAY_BUFFER, GL_STATIC_DRAW, sizeof(SFTriangle) * (2), m_triangles);
}

void R3DScrollFog::DeallocResources()
{
	if (m_shaderProgram) {
#ifdef __ANDROID__
		glUseProgram(0);
		glDetachShader(m_shaderProgram, m_vertexShader);
		glDetachShader(m_shaderProgram, m_fragmentShader);
		glDeleteShader(m_vertexShader);
		glDeleteShader(m_fragmentShader);
		glDeleteProgram(m_shaderProgram);
#else
		DestroyShaderProgram(m_shaderProgram, m_vertexShader, m_fragmentShader);
#endif
	}

	m_shaderProgram		= 0;
	m_vertexShader		= 0;
	m_fragmentShader	= 0;

	m_vbo.Destroy();
}

}
