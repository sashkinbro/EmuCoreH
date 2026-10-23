use bincode::{Decode, Encode};
use jgenesis_common::frontend::{
    AudioOutput, Color, FrameSize, RenderFrameOptions, Renderer, SaveWriter,
};
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};

fn bincode_config() -> impl bincode::config::Config {
    bincode::config::standard()
        .with_little_endian()
        .with_fixed_int_encoding()
        .with_limit::<{ 100 * 1024 * 1024 }>()
}

pub struct FrameOutput {
    pub pixels: Vec<u32>,
    pub width: u32,
    pub height: u32,
    pub aspect_ratio: f64,
    pub fps: f64,
    pub queued: bool,
}

impl FrameOutput {
    pub fn new() -> Self {
        Self {
            pixels: Vec::new(),
            width: 320,
            height: 224,
            aspect_ratio: 4.0 / 3.0,
            fps: 60.0,
            queued: false,
        }
    }

    pub fn begin_frame(&mut self) {
        self.queued = false;
    }
}

impl Renderer for FrameOutput {
    type Err = String;

    fn render_frame(
        &mut self,
        frame_buffer: &[Color],
        frame_size: FrameSize,
        target_fps: f64,
        options: RenderFrameOptions,
    ) -> Result<(), Self::Err> {
        let width = frame_size.width.max(1);
        let height = frame_size.height.max(1);
        let pixel_count = (width as usize).saturating_mul(height as usize);
        if frame_buffer.len() < pixel_count {
            return Err(format!(
                "frame buffer too small: {} pixels, expected {pixel_count}",
                frame_buffer.len()
            ));
        }

        self.pixels.clear();
        self.pixels.reserve(pixel_count);
        for color in &frame_buffer[..pixel_count] {
            self.pixels
                .push((u32::from(color.r) << 16) | (u32::from(color.g) << 8) | u32::from(color.b));
        }

        self.width = width;
        self.height = height;
        let raw_aspect = f64::from(width) / f64::from(height);
        self.aspect_ratio = options
            .pixel_aspect_ratio
            .map(|par| raw_aspect * f64::from(par))
            .filter(|value| value.is_finite() && *value > 0.25 && *value < 4.0)
            .unwrap_or(raw_aspect);
        self.fps = if target_fps.is_finite() && target_fps > 1.0 { target_fps } else { 60.0 };
        self.queued = true;
        Ok(())
    }
}

pub struct AudioBuffer {
    pub samples: Vec<i16>,
}

impl AudioBuffer {
    pub fn new() -> Self {
        Self { samples: Vec::with_capacity(4096) }
    }

    pub fn begin_frame(&mut self) {
        self.samples.clear();
    }

    pub fn frames(&self) -> usize {
        self.samples.len() / 2
    }
}

fn to_i16(sample: f64) -> i16 {
    let sample = if sample.is_finite() { sample } else { 0.0 };
    (sample.clamp(-1.0, 1.0) * f64::from(i16::MAX)).round() as i16
}

impl AudioOutput for AudioBuffer {
    type Err = String;

    fn push_sample(&mut self, sample_l: f64, sample_r: f64) -> Result<(), Self::Err> {
        self.samples.push(to_i16(sample_l));
        self.samples.push(to_i16(sample_r));
        Ok(())
    }
}

pub struct FsSaveWriter {
    directory: PathBuf,
    base_name: String,
}

impl FsSaveWriter {
    pub fn new(directory: PathBuf, base_name: String) -> Self {
        let _ = fs::create_dir_all(&directory);
        Self { directory, base_name }
    }

    fn path_for(&self, extension: &str) -> PathBuf {
        self.directory.join(format!("{}.{}", self.base_name, extension))
    }

    fn write_atomic(&self, path: &Path, bytes: &[u8]) -> Result<(), String> {
        let temporary = path.with_extension(format!(
            "{}tmp",
            path.extension()
                .and_then(|value| value.to_str())
                .map(|value| format!("{value}."))
                .unwrap_or_default()
        ));
        let mut file = File::create(&temporary)
            .map_err(|error| format!("Unable to create save {}: {error}", temporary.display()))?;
        file.write_all(bytes)
            .and_then(|_| file.sync_all())
            .map_err(|error| format!("Unable to sync save {}: {error}", temporary.display()))?;
        drop(file);
        replace_file(&temporary, path)
            .map_err(|error| format!("Unable to replace save {}: {error}", path.display()))
    }
}

#[cfg(not(windows))]
fn replace_file(source: &Path, destination: &Path) -> std::io::Result<()> {
    fs::rename(source, destination)?;
    if let Some(directory) = destination.parent() {
        File::open(directory)?.sync_all()?;
    }
    Ok(())
}

#[cfg(windows)]
fn replace_file(source: &Path, destination: &Path) -> std::io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn MoveFileExW(source: *const u16, destination: *const u16, flags: u32) -> i32;
    }
    let source: Vec<_> = source.as_os_str().encode_wide().chain(Some(0)).collect();
    let destination: Vec<_> = destination.as_os_str().encode_wide().chain(Some(0)).collect();
    if unsafe { MoveFileExW(source.as_ptr(), destination.as_ptr(), 1 | 8) } == 0 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn saves_replace_and_reload_without_losing_existing_data() {
        let directory = std::env::temp_dir().join(format!("jgenesis-save-{}", std::process::id()));
        let mut writer = FsSaveWriter::new(directory.clone(), "battery".into());
        writer.persist_bytes("sav", &[1, 2, 3]).unwrap();
        writer.persist_bytes("sav", &[4, 5]).unwrap();
        assert_eq!(writer.load_bytes("sav").unwrap(), [4, 5]);
        assert!(!directory.join("battery.sav.tmp").exists());
        std::fs::remove_dir_all(directory).unwrap();
    }
}

impl SaveWriter for FsSaveWriter {
    type Err = String;

    fn load_bytes(&mut self, extension: &str) -> Result<Vec<u8>, Self::Err> {
        let path = self.path_for(extension);
        if !path.is_file() {
            return Ok(Vec::new());
        }
        fs::read(&path).map_err(|error| format!("Unable to read save {}: {error}", path.display()))
    }

    fn persist_bytes(&mut self, extension: &str, bytes: &[u8]) -> Result<(), Self::Err> {
        let path = self.path_for(extension);
        self.write_atomic(&path, bytes)
    }

    fn load_serialized<D: Decode<()>>(&mut self, extension: &str) -> Result<D, Self::Err> {
        let bytes = self.load_bytes(extension)?;
        if bytes.is_empty() {
            return Err(format!("Save file for .{extension} is empty"));
        }
        bincode::decode_from_slice(&bytes, bincode_config())
            .map(|(value, _)| value)
            .map_err(|error| format!("Unable to decode .{extension}: {error}"))
    }

    fn persist_serialized<E: Encode>(&mut self, extension: &str, data: E) -> Result<(), Self::Err> {
        let bytes = bincode::encode_to_vec(data, bincode_config())
            .map_err(|error| format!("Unable to encode .{extension}: {error}"))?;
        self.persist_bytes(extension, &bytes)
    }
}
