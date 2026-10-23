#ifndef _VBO_H_
#define _VBO_H_

#ifdef __ANDROID__
#include <GLES3/gl3.h>
#else
#include <GL/glew.h>
#endif

namespace New3D {

class VBO
{
public:
	VBO();

	void Create			(GLenum target, GLenum usage, GLsizeiptr size, const void* data=nullptr);
	void BufferSubData	(GLintptr offset, GLsizeiptr size, const GLvoid* data);
	bool AppendData		(GLsizeiptr size, const GLvoid* data);
	void Reset			();		// don't delete data, just go back to start
	void Destroy		();
	void Bind			(bool enable);
	int  GetSize		();
	int  GetCapacity	();

private:
	GLuint	m_id;
	GLenum	m_target;
	int		m_capacity;
	int		m_size;
};

} // New3D

#endif
