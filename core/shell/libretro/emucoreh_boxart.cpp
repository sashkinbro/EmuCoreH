/*
	EmuCoreH offline box art bridge.

	Opens a Dreamcast/GD-ROM image with the same drivers and ISO filesystem
	reader as the emulator core (GDI, CUE, ISO, CDI and CHD), reads the IP.BIN
	metadata and extracts the 0GDTEX.PVR cover texture exactly like Flycast's
	offline box art scraper (core/ui/boxart/scraper.cpp).
*/

#include <libretro.h>
#include "stdclass.h"
#include "imgread/common.h"
#include "imgread/isofs.h"
#include "reios/reios.h"
#include "ui/boxart/pvrparser.h"

#include <stb_image_write.h>

#include <cctype>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>

namespace {

void copyTrimmed(char *dst, int dstSize, const char *src, int srcSize)
{
	if (dstSize <= 0)
		return;
	int end = srcSize;
	while (end > 0 && (src[end - 1] == '\0' || src[end - 1] == ' '))
		end--;
	const int copy = end < dstSize - 1 ? end : dstSize - 1;
	memcpy(dst, src, copy);
	dst[copy] = '\0';
	// IP.BIN fields are padded with non-printable bytes: replace them like
	// Flycast does so the strings can be used in search requests.
	for (char *p = dst; *p != '\0'; p++)
		if (!std::isprint(static_cast<unsigned char>(*p)))
			*p = ' ';
}

bool writePng(const char *path, u32 width, u32 height, const std::vector<u8> &pixels)
{
	FILE *file = std::fopen(path, "wb");
	if (file == nullptr)
		return false;
	const auto save = [](void *context, void *data, int size) {
		FILE *f = static_cast<FILE *>(context);
		std::fwrite(data, 1, size, f);
	};
	const int result = stbi_write_png_to_func(save, file, width, height, 4, pixels.data(), width * 4);
	std::fclose(file);
	return result != 0;
}

}  // namespace

// Scrapes a disc image without booting the emulator.
//
// Returns a bit mask: bit 0 = IP.BIN metadata was read, bit 1 = the
// 0GDTEX.PVR cover was written. The metadata buffers receive the software
// name and product number, NUL-terminated and safely truncated.
extern "C" RETRO_API int emucoreh_scrape_disc(const char *gamePath, const char *pngOutputPath,
		char *searchNameOut, int searchNameSize, char *uniqueIdOut, int uniqueIdSize)
{
	if (gamePath == nullptr || gamePath[0] == '\0')
		return 0;
	if (searchNameOut != nullptr && searchNameSize > 0)
		searchNameOut[0] = '\0';
	if (uniqueIdOut != nullptr && uniqueIdSize > 0)
		uniqueIdOut[0] = '\0';

	// The scraper runs before the emulator session exists, and both the i18n
	// dictionary and pvrParse use process-wide state, so scrapes are
	// serialized and must never let a bad image escape as an exception.
	static std::mutex scrapeMutex;
	std::lock_guard<std::mutex> lock(scrapeMutex);

	Disc *disc = nullptr;
	try {
		disc = OpenDisc(gamePath);
	} catch (const std::exception &e) {
		WARN_LOG(COMMON, "EmuCoreH boxart: cannot open disc %s: %s", gamePath, e.what());
		return 0;
	} catch (...) {
		WARN_LOG(COMMON, "EmuCoreH boxart: cannot open disc %s", gamePath);
		return 0;
	}
	if (disc == nullptr) {
		WARN_LOG(COMMON, "EmuCoreH boxart: unsupported disc %s", gamePath);
		return 0;
	}

	int result = 0;
	try {
		u8 sector[2048];
		disc->ReadSectors(disc->GetBaseFAD(), 1, sector, sizeof(sector));
		ip_meta_t diskId;
		memcpy(&diskId, sector, sizeof(diskId));
		const bool validBootSector = memcmp(diskId.hardware_id, "SEGA SEGAKATANA ", sizeof(diskId.hardware_id)) == 0
				&& memcmp(diskId.maker_id, "SEGA ENTERPRISES", sizeof(diskId.maker_id)) == 0;
		if (validBootSector)
		{
			if (uniqueIdOut != nullptr && uniqueIdSize > 0)
				copyTrimmed(uniqueIdOut, uniqueIdSize, diskId.product_number, sizeof(diskId.product_number));
			if (searchNameOut != nullptr && searchNameSize > 0)
				copyTrimmed(searchNameOut, searchNameSize, diskId.software_name, sizeof(diskId.software_name));
			result |= 1;
		}
		else
			WARN_LOG(COMMON, "EmuCoreH boxart: invalid IP.BIN boot sector in %s", gamePath);
	} catch (const std::exception &e) {
		WARN_LOG(COMMON, "EmuCoreH boxart: cannot read %s: %s", gamePath, e.what());
	} catch (...) {
		WARN_LOG(COMMON, "EmuCoreH boxart: cannot read %s", gamePath);
	}

	// Offline cover: the 0GDTEX.PVR texture stored in the ISO filesystem.
	if (pngOutputPath != nullptr && pngOutputPath[0] != '\0')
	{
		try {
			IsoFs isofs(disc);
			std::unique_ptr<IsoFs::Directory> root(isofs.getRoot());
			std::unique_ptr<IsoFs::Entry> entry(root != nullptr ? root->getEntry("0GDTEX.PVR") : nullptr);
			if (entry != nullptr && !entry->isDirectory())
			{
				IsoFs::File *gdtex = static_cast<IsoFs::File *>(entry.get());
				std::vector<u8> data(gdtex->getSize());
				gdtex->read(data.data(), data.size());
				u32 width = 0;
				u32 height = 0;
				std::vector<u8> pixels;
				if (pvrParse(data.data(), data.size(), width, height, pixels)
						&& writePng(pngOutputPath, width, height, pixels))
					result |= 2;
			}
		} catch (const std::exception &e) {
			WARN_LOG(COMMON, "EmuCoreH boxart: no GD-ROM texture in %s: %s", gamePath, e.what());
		} catch (...) {
			WARN_LOG(COMMON, "EmuCoreH boxart: no GD-ROM texture in %s", gamePath);
		}
	}

	delete disc;
	return result;
}
