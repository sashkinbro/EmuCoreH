/**
 ** Supermodel
 ** A Sega Model 3 Arcade Emulator.
 ** Copyright 2011 Bart Trzynadlowski, Nik Henson 
 **
 ** This file is part of Supermodel.
 **
 ** Supermodel is free software: you can redistribute it and/or modify it under
 ** the terms of the GNU General Public License as published by the Free 
 ** Software Foundation, either version 3 of the License, or (at your option)
 ** any later version.
 **
 ** Supermodel is distributed in the hope that it will be useful, but WITHOUT
 ** ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 ** FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
 ** more details.
 **
 ** You should have received a copy of the GNU General Public License along
 ** with Supermodel.  If not, see <http://www.gnu.org/licenses/>.
 **/
 
/*
 * RTC72421.cpp
 * 
 * Epson RTC-72421 implementation. 
 *
 * TO-DO List:
 * -----------
 * The guest calendar advances with emulated frames and is part of save states.
 * It is seeded from the host's local calendar during initialization.
 * - Writes do nothing yet.
 */

#include "RTC72421.h"

#include <ctime>
#include "Supermodel.h"
#include "BlockFile.h"


/******************************************************************************
 Emulation Functions
******************************************************************************/

UINT8 CRTC72421::ReadRegister(unsigned reg)
{
	return m_clock.Read(reg);
}

void CRTC72421::WriteRegister(unsigned reg, UINT8 data)
{
	// TO-DO: emulate me!
}

void CRTC72421::Reset(void)
{
	// nothing to do
}


/******************************************************************************
 Initialization and Shutdown
******************************************************************************/

void CRTC72421::Init(void)
{
	const time_t now = time(nullptr);
	if (const auto* local = localtime(&now)) {
		Model3Clock::Calendar initial{static_cast<uint32_t>(local->tm_year + 1900),
			static_cast<uint32_t>(local->tm_mon + 1),static_cast<uint32_t>(local->tm_mday),
			static_cast<uint32_t>(local->tm_wday),static_cast<uint32_t>(local->tm_hour),
			static_cast<uint32_t>(local->tm_min),static_cast<uint32_t>(local->tm_sec),0};
		if (initial.Valid()) m_clock = initial;
	}
}

void CRTC72421::SaveState(CBlockFile* file)
{
	file->NewBlock("Model 3 RTC", __FILE__);
	file->Write(&m_clock, sizeof(m_clock));
}

void CRTC72421::LoadState(CBlockFile* file)
{
	if (file->FindBlock("Model 3 RTC") != OKAY) return;
	Model3Clock::Calendar restored;
	if (file->Read(&restored, sizeof(restored)) == sizeof(restored) && restored.Valid())
		m_clock = restored;
}

CRTC72421::CRTC72421(void)
{	
	DebugLog("Built RTC-72421\n");
}

CRTC72421::~CRTC72421(void)
{	
	DebugLog("Destroyed RTC-72421\n");
}
