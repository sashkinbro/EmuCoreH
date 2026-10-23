#define MINIMP3_IMPLEMENTATION
#include "Pkgs/minimp3.h"
#include "MpegAudio.h"
#include "BlockFile.h"
#include <stdexcept>

struct Decoder
{
	mp3dec_t			mp3d;
	mp3dec_frame_info_t	info;
	const uint8_t*		buffer;
	int					size, pos;
	bool				loop;
	bool				stopped;
	int					numSamples;
	int					pcmPos;
	short				pcm[MINIMP3_MAX_SAMPLES_PER_FRAME];
};

static Decoder dec = { 0 };

// Field-wise snapshots exclude host pointers and struct padding.
void MpegDec::Reset()
{
	dec = {};
	dec.stopped = true;
}

void MpegDec::SaveState(CBlockFile *file, const uint8_t *rom, size_t romSize)
{
	uint32_t version = 1, offset = UINT32_MAX;
	if (dec.buffer)
	{
		const auto base = reinterpret_cast<uintptr_t>(rom);
		const auto buffer = reinterpret_cast<uintptr_t>(dec.buffer);
		if (buffer < base || buffer - base > romSize)
			throw std::runtime_error("MPEG buffer does not belong to this ROM");
		offset = static_cast<uint32_t>(buffer - base);
	}
	const uint8_t flags[2] = {static_cast<uint8_t>(dec.loop), static_cast<uint8_t>(dec.stopped)};
	static_assert(sizeof(dec.info) == 20 && sizeof(dec.pcm) == 4608);
	file->NewBlock("MPEG Decoder", __FILE__);
	auto field = [file](const auto &value) { file->Write(&value, sizeof(value)); };
	field(version); field(offset); field(dec.size); field(dec.pos); field(flags);
	field(dec.numSamples); field(dec.pcmPos); field(dec.info);
	field(dec.mp3d.reserv); field(dec.mp3d.free_format_bytes);
	field(dec.mp3d.header); field(dec.mp3d.reserv_buf);
	field(dec.mp3d.mdct_overlap); field(dec.mp3d.qmf_state); field(dec.pcm);
}

void MpegDec::LoadState(CBlockFile *file, const uint8_t *rom, size_t romSize)
{
	if (file->FindBlock("MPEG Decoder") != 0) return;
	Decoder restored{};
	uint32_t version = 0, offset = 0;
	uint8_t flags[2]{};
	auto field = [file](auto &value) { file->Read(&value, sizeof(value)); };
	field(version); field(offset); field(restored.size); field(restored.pos); field(flags);
	field(restored.numSamples); field(restored.pcmPos); field(restored.info);
	field(restored.mp3d.reserv); field(restored.mp3d.free_format_bytes);
	field(restored.mp3d.header); field(restored.mp3d.reserv_buf);
	field(restored.mp3d.mdct_overlap); field(restored.mp3d.qmf_state); field(restored.pcm);
	const auto channels = restored.info.channels;
	const bool noBuffer = offset == UINT32_MAX;
	if (version != 1 || flags[0] > 1 || flags[1] > 1 || restored.size < 0 ||
		restored.pos > restored.size || channels < 0 || channels > 2 ||
		restored.numSamples < 0 || restored.numSamples > 1152 ||
		(restored.numSamples && !channels) || restored.pcmPos < 0 ||
		restored.pcmPos > restored.numSamples * channels || (channels && restored.pcmPos % channels) ||
		(noBuffer ? (restored.size || restored.pos || !flags[1]) :
			(offset > romSize || uint64_t(offset) + restored.size > romSize || int64_t(offset) + restored.pos < 0)))
		throw std::runtime_error("Invalid MPEG decoder snapshot");
	restored.buffer = noBuffer ? nullptr : rom + offset;
	restored.loop = flags[0] != 0;
	restored.stopped = flags[1] != 0;
	dec = restored;
}

void MpegDec::SetMemory(const uint8_t *data, int length, bool loop)
{
	mp3dec_init(&dec.mp3d);

	dec.buffer		= data;
	dec.size		= length;
	dec.pos			= 0;
	dec.numSamples	= 0;
	dec.pcmPos		= 0;
	dec.loop		= loop;
	dec.stopped		= false;
}

void MpegDec::UpdateMemory(const uint8_t* data, int length, bool loop)
{
	int diff;
	if (data > dec.buffer) {
		diff = (int)(data - dec.buffer);
	}
	else {
		diff = -(int)(dec.buffer - data);
	}

	dec.buffer	= data;
	dec.size	= length;
	dec.pos		= dec.pos - diff;		// update position relative to our new start location
	dec.loop	= loop;
}

int MpegDec::GetPosition()
{
	return (int)dec.pos;
}

void MpegDec::SetPosition(int pos)
{
	dec.pos = pos;
}

static void FlushBuffer(int16_t*& left, int16_t*& right, int& numStereoSamples)
{
	int numChans = dec.info.channels;

	int &i = dec.pcmPos;

	for (; i < (dec.numSamples * numChans) && numStereoSamples; i += numChans) {
		*left++ = dec.pcm[i];
		*right++ = dec.pcm[i + numChans - 1];
		numStereoSamples--;
	}
}

// might need to copy some silence to end the buffer if we aren't looping
static void EndWithSilence(int16_t*& left, int16_t*& right, int& numStereoSamples)
{
	while (numStereoSamples)
	{
		*left++ = 0;
		*right++ = 0;
		numStereoSamples--;
	}
}

static bool EndOfBuffer()
{
	return dec.pos >= dec.size - HDR_SIZE;
}

void MpegDec::Stop()
{
	dec.stopped = true;
}

bool MpegDec::IsLoaded()
{
	return dec.buffer != nullptr;
}

void MpegDec::DecodeAudio(int16_t* left, int16_t* right, int numStereoSamples)
{
	unsigned emptyLoops = 0;
	// if we are stopped return silence
	if (dec.stopped || !dec.buffer) {
		EndWithSilence(left, right, numStereoSamples);
	}

	// copy any left over samples first
	FlushBuffer(left, right, numStereoSamples);

	while (numStereoSamples) {

		dec.numSamples = mp3dec_decode_frame(
			&dec.mp3d,
			dec.buffer + dec.pos,
			dec.size - dec.pos,
			dec.pcm,
			&dec.info);

		dec.pos += dec.info.frame_bytes;
		dec.pcmPos = 0;	// reset pos
		// A state may select a ROM region containing no valid MPEG frames.
		// Never spin forever without consuming either input or output.
		if (!dec.numSamples && dec.info.frame_bytes <= 0) {
			dec.stopped = true;
			EndWithSilence(left, right, numStereoSamples);
			break;
		}
		if (dec.numSamples) emptyLoops = 0;

		FlushBuffer(left, right, numStereoSamples);

		// check end of buffer handling
		if (EndOfBuffer()) {
			if (dec.loop) {
				if (!dec.numSamples && ++emptyLoops > 1) {
					dec.stopped = true;
					EndWithSilence(left, right, numStereoSamples);
					break;
				}
				dec.pos = 0;
			}
			else {
				EndWithSilence(left, right, numStereoSamples);
			}
		}

	}

}


