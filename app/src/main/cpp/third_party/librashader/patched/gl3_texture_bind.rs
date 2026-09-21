// Patched copy of librashader-runtime-gl/src/gl/gl3/texture_bind.rs from the
// pinned librashader revision (87e8a97b50516d997defeaa168173dcd185d4022).
//
// A texture that only owns a single mip level is mipmap incomplete; sampling it
// with the mipmapped samplers upstream always selects makes strict GLES drivers
// (Adreno) return black. Query the texture's immutable level count and bind the
// matching sampler so level-0-only images sample correctly.
//
// Keep this file in sync if the librashader pin is updated.

use crate::gl::BindTexture;
use crate::samplers::SamplerSet;
use crate::texture::InputTexture;
use glow::HasContext;
use librashader_reflect::reflect::semantics::TextureBinding;

pub struct Gl3BindTexture;

impl BindTexture for Gl3BindTexture {
    fn bind_texture(
        ctx: &glow::Context,
        samplers: &SamplerSet,
        binding: &TextureBinding,
        texture: &InputTexture,
    ) {
        unsafe {
            // eprintln!("setting {} to texunit {}", texture.image.handle, binding.binding);;
            ctx.active_texture(glow::TEXTURE0 + binding.binding);
            ctx.bind_texture(glow::TEXTURE_2D, texture.image.handle);

            // Non-immutable textures (the emulator frame the host supplies)
            // report 0 levels; immutable storage textures report their allocated
            // level count. Anything with a single level must not use a
            // mipmapping min filter or GLES samples it as black.
            let levels =
                ctx.get_tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_IMMUTABLE_LEVELS);
            let sampler = if levels > 1 {
                samplers.get(texture.wrap_mode, texture.filter, texture.mip_filter)
            } else {
                samplers.get_no_mipmap(texture.wrap_mode, texture.filter)
            };

            ctx.bind_sampler(binding.binding, Some(sampler));
        }
    }

    fn gen_mipmaps(ctx: &glow::Context, texture: &InputTexture) {
        unsafe {
            ctx.bind_texture(glow::TEXTURE_2D, texture.image.handle);
            ctx.generate_mipmap(glow::TEXTURE_2D);
            ctx.bind_texture(glow::TEXTURE_2D, None);
        }
    }
}
