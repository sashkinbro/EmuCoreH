#ifndef _MPEG_AUDIO_H_
#define _MPEG_AUDIO_H_

#include <cstdint>
#include <cstddef>
class CBlockFile;

namespace MpegDec
{
	void Reset();
	void SaveState(CBlockFile *file, const uint8_t *rom, size_t romSize);
	void LoadState(CBlockFile *file, const uint8_t *rom, size_t romSize);
	void	SetMemory(const uint8_t *data, int length, bool loop);
	void	UpdateMemory(const uint8_t *data, int length, bool loop);
	int		GetPosition();
	void	SetPosition(int pos);
	void	DecodeAudio(int16_t* left, int16_t* right, int numStereoSamples);
	void	Stop();
	bool	IsLoaded();
}

#endif

