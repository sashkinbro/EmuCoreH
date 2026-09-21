// Patched copy of librashader-runtime-gl/src/samplers.rs from the pinned
// librashader revision (87e8a97b50516d997defeaa168173dcd185d4022).
//
// Upstream always creates samplers whose minification filter is a mipmapping
// filter (`filter.gl_mip(mip)`). Textures that only own a single mip level are
// then incomplete, and strict GLES drivers (Adreno in particular) return black
// for every sample, which blanked the whole shader chain output on OpenGL.
//
// This copy adds a parallel set of non-mipmapped samplers; the GL3 texture
// binding picks them whenever the bound texture has no mip levels.
//
// Keep this file in sync if the librashader pin is updated.

use crate::error;
use crate::error::FilterChainError;
use glow::HasContext;
use librashader_common::map::FastHashMap;
use librashader_common::{FilterMode, WrapMode};

pub struct SamplerSet {
    // todo: may need to deal with differences in mip filter.
    samplers: FastHashMap<(WrapMode, FilterMode, FilterMode), glow::Sampler>,
    // Samplers without mipmap minification, used for textures that only have a
    // single level so they stay complete on GLES.
    no_mipmap_samplers: FastHashMap<(WrapMode, FilterMode), glow::Sampler>,
}

impl SamplerSet {
    #[inline(always)]
    pub fn get(&self, wrap: WrapMode, filter: FilterMode, mipmap: FilterMode) -> glow::Sampler {
        // SAFETY: the sampler set is complete for the matrix
        // wrap x filter x mipmap
        unsafe {
            *self
                .samplers
                .get(&(wrap, filter, mipmap))
                .unwrap_unchecked()
        }
    }

    #[inline(always)]
    pub fn get_no_mipmap(&self, wrap: WrapMode, filter: FilterMode) -> glow::Sampler {
        // SAFETY: the sampler set is complete for the matrix wrap x filter
        unsafe {
            *self
                .no_mipmap_samplers
                .get(&(wrap, filter))
                .unwrap_unchecked()
        }
    }

    fn make_sampler(
        context: &glow::Context,
        sampler: glow::Sampler,
        wrap: WrapMode,
        filter: FilterMode,
        mip: FilterMode,
    ) {
        unsafe {
            context.sampler_parameter_i32(sampler, glow::TEXTURE_WRAP_S, wrap.into());
            context.sampler_parameter_i32(sampler, glow::TEXTURE_WRAP_T, wrap.into());
            context.sampler_parameter_i32(sampler, glow::TEXTURE_MAG_FILTER, filter.into());
            context.sampler_parameter_i32(
                sampler,
                glow::TEXTURE_MIN_FILTER,
                filter.gl_mip(mip) as i32,
            );
        }
    }

    fn make_no_mipmap_sampler(
        context: &glow::Context,
        sampler: glow::Sampler,
        wrap: WrapMode,
        filter: FilterMode,
    ) {
        unsafe {
            context.sampler_parameter_i32(sampler, glow::TEXTURE_WRAP_S, wrap.into());
            context.sampler_parameter_i32(sampler, glow::TEXTURE_WRAP_T, wrap.into());
            context.sampler_parameter_i32(sampler, glow::TEXTURE_MAG_FILTER, filter.into());
            context.sampler_parameter_i32(sampler, glow::TEXTURE_MIN_FILTER, filter.into());
        }
    }

    pub fn new(context: &glow::Context) -> error::Result<SamplerSet> {
        let mut samplers = FastHashMap::default();
        let wrap_modes = &[
            WrapMode::ClampToBorder,
            WrapMode::ClampToEdge,
            WrapMode::Repeat,
            WrapMode::MirroredRepeat,
        ];
        for wrap_mode in wrap_modes {
            for filter_mode in &[FilterMode::Linear, FilterMode::Nearest] {
                for mip_filter in &[FilterMode::Linear, FilterMode::Nearest] {
                    unsafe {
                        let sampler = context
                            .create_sampler()
                            .map_err(|_| FilterChainError::GlSamplerError)?;

                        let mut gl_wrap_mode = *wrap_mode;
                        if context.version().is_embedded && gl_wrap_mode == WrapMode::ClampToBorder
                        {
                            gl_wrap_mode = WrapMode::ClampToEdge;
                        }

                        SamplerSet::make_sampler(
                            context,
                            sampler,
                            gl_wrap_mode,
                            *filter_mode,
                            *mip_filter,
                        );

                        samplers.insert((*wrap_mode, *filter_mode, *mip_filter), sampler);
                    }
                }
            }
        }

        let mut no_mipmap_samplers = FastHashMap::default();
        for wrap_mode in wrap_modes {
            for filter_mode in &[FilterMode::Linear, FilterMode::Nearest] {
                unsafe {
                    let sampler = context
                        .create_sampler()
                        .map_err(|_| FilterChainError::GlSamplerError)?;

                    let mut gl_wrap_mode = *wrap_mode;
                    if context.version().is_embedded && gl_wrap_mode == WrapMode::ClampToBorder {
                        gl_wrap_mode = WrapMode::ClampToEdge;
                    }

                    SamplerSet::make_no_mipmap_sampler(
                        context,
                        sampler,
                        gl_wrap_mode,
                        *filter_mode,
                    );

                    no_mipmap_samplers.insert((*wrap_mode, *filter_mode), sampler);
                }
            }
        }

        // assert all samplers were created.
        assert_eq!(samplers.len(), wrap_modes.len() * 2 * 2);
        assert_eq!(no_mipmap_samplers.len(), wrap_modes.len() * 2);
        Ok(SamplerSet {
            samplers,
            no_mipmap_samplers,
        })
    }
}
