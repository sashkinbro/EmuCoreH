/**
 ** Supermodel
 ** A Sega Model 3 Arcade Emulator.
 ** Copyright 2011-2020 Bart Trzynadlowski, Nik Henson, Ian Curtis,
 **                     Harry Tuttle, and Spindizzi
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
 ** with this program.  If not, see <http://www.gnu.org/licenses/>.
 **/

#include "Supermodel.h"
#include "StandaloneNetBoard.h"

#include <cstring>

// These expand the index before the address-of operator, so CommRAM16[0x72]
// reads the buffer, not the pointer variable.
#define RAM16 *(UINT16*)&RAM
#define CommRAM16 *(UINT16*)&CommRAM

namespace
{
constexpr size_t kCommRamBankSize = 0x10000;
constexpr size_t kCommRamDataOffset = 0x100;
}

CStandaloneNetBoard::CStandaloneNetBoard(void)
{
}

void CStandaloneNetBoard::SaveState(CBlockFile* SaveState)
{
	(void)SaveState;
}

void CStandaloneNetBoard::LoadState(CBlockFile* SaveState)
{
	(void)SaveState;
}

bool CStandaloneNetBoard::IsGame(const char* gameName) const
{
	return m_gameInfo.name == gameName || m_gameInfo.parent == gameName;
}

bool CStandaloneNetBoard::Init(UINT8* netRAMPtr, UINT8* netBufferPtr)
{
	RAM = netRAMPtr;
	Buffer = netBufferPtr;

	CommRAM = Buffer;
	externalCommRAM = Buffer + kCommRamBankSize;

	m_attached = m_gameInfo.netboard_present;

	if (!m_attached)
		return OKAY;

	if (IsGame("daytona2") || IsGame("harley") || IsGame("scud") || IsGame("srally2") ||
		IsGame("skichamp") || IsGame("spikeout") || IsGame("spikeofe"))
		m_gameType = 1;
	else if (IsGame("lemans24") || IsGame("von2") || IsGame("dirtdvls"))
		m_gameType = 2;
	else
	{
		// Keep the previous no-network-board behavior for games whose netboard
		// protocol is not part of the single-cabinet simulation.
		InfoLog("Network board is not emulated for this game");
		m_attached = false;
		return OKAY;
	}

	m_state = State::start;
	return OKAY;
}

void CStandaloneNetBoard::RunFrame(void)
{
	if (!IsRunning())
		return;

	switch (m_state)
	{
	case State::start:
		m_status0 = 0;
		m_status1 = IsGame("dirtdvls") ? 0x4004 : 0xe000;
		m_state = State::init;
		break;

	case State::init:
		memset(Buffer, 0, 2 * kCommRamBankSize);
		if (m_gameType == 1)
		{
			if (m_status0 & 0x8000)
			{
				m_IRQ2ack |= 0x01;
				if (m_status0 == 0xf000)
				{
					m_status1 = 0;
					CommRAM16[0x72] = FLIPENDIAN16(0x1);
					m_state = State::testing;
				}
				m_status0 = 0;
			}
		}
		else
		{
			m_status1 = 0;
			m_state = State::testing;
			m_counter = 0;
		}
		break;

	case State::testing:
		if (m_gameType == 1)
		{
			// A single attached cabinet completes the self-test with zero
			// remote machines and index zero.
			m_status0 = 0;
			m_status1 = 0x2021;
			CommRAM16[0x0] = RAM16[0x400];
			CommRAM16[0x2] = 0;
			CommRAM16[0x4] = 0;

			m_counter = 0;
			CommRAM16[0x6] = 0;

			m_segmentSize = RAM16[0x404];
			CommRAM16[0x8] = FLIPENDIAN16(0x100 + m_segmentSize);
			CommRAM16[0xa] = FLIPENDIAN16(RAM16[0x402] - m_segmentSize - 1);
			CommRAM16[0xc] = FLIPENDIAN16(0x100);
			CommRAM16[0xe] = FLIPENDIAN16(RAM16[0x402] - m_segmentSize + 0x200);
		}
		else
		{
			m_status0 = 5;
			m_status1 = IsGame("dirtdvls") ? 0x7400 : 0;
			CommRAM16[0x0] = RAM16[0x200];
			CommRAM16[0x2] = 0;
			CommRAM16[0x4] = 0;

			m_counter = 0;
			CommRAM16[0x6] = 0;

			m_segmentSize = RAM16[0x204];
			CommRAM16[0x8] = FLIPENDIAN16(0x100 + m_segmentSize);
			CommRAM16[0xa] = FLIPENDIAN16(RAM16[0x206]);
			CommRAM16[0xc] = FLIPENDIAN16(0x100);
			CommRAM16[0xe] = FLIPENDIAN16(RAM16[0x206] + 0x80);
		}
		m_state = State::ready;
		break;

	case State::ready:
		m_counter++;
		CommRAM16[0x6] = FLIPENDIAN16(m_counter);

		// The full emulation sends each local segment and receives it back
		// from the next cabinet. A single cabinet has no peer, so copy the
		// local segment directly into the receive slot.
		if (m_segmentSize != 0 &&
			kCommRamDataOffset + 2ULL * m_segmentSize <= kCommRamBankSize)
		{
			std::memcpy(CommRAM + kCommRamDataOffset + m_segmentSize,
				CommRAM + kCommRamDataOffset, m_segmentSize);
		}

		// Swap CommRAM banks
		if (m_commbank)
		{
			m_commbank = false;
			CommRAM = Buffer;
			externalCommRAM = Buffer + kCommRamBankSize;
		}
		else
		{
			m_commbank = true;
			CommRAM = Buffer + kCommRamBankSize;
			externalCommRAM = Buffer;
		}
		break;

	case State::error:
		break;
	}
}

void CStandaloneNetBoard::Reset(void)
{
	m_running = false;
	m_state = State::start;
	m_counter = 0;
	m_status0 = 0;
	m_status1 = 0;
	m_IRQ2ack = 0;
	m_commbank = false;
	if (Buffer != nullptr)
	{
		CommRAM = Buffer;
		externalCommRAM = Buffer + kCommRamBankSize;
	}
}

bool CStandaloneNetBoard::IsAttached(void)
{
	return m_attached;
}

bool CStandaloneNetBoard::IsRunning(void)
{
	return m_attached && m_running;
}

void CStandaloneNetBoard::GetGame(const Game& gameInfo)
{
	m_gameInfo = gameInfo;
}

UINT8 CStandaloneNetBoard::ReadCommRAM8(unsigned addr)
{
	return externalCommRAM[addr];
}

UINT16 CStandaloneNetBoard::ReadCommRAM16(unsigned addr)
{
	return *(UINT16*)&externalCommRAM[addr];
}

UINT32 CStandaloneNetBoard::ReadCommRAM32(unsigned addr)
{
	return *(UINT32*)&externalCommRAM[addr];
}

void CStandaloneNetBoard::WriteCommRAM8(unsigned addr, UINT8 data)
{
	externalCommRAM[addr] = data;
}

void CStandaloneNetBoard::WriteCommRAM16(unsigned addr, UINT16 data)
{
	*(UINT16*)&externalCommRAM[addr] = data;
}

void CStandaloneNetBoard::WriteCommRAM32(unsigned addr, UINT32 data)
{
	*(UINT32*)&externalCommRAM[addr] = data;
}

UINT16 CStandaloneNetBoard::ReadIORegister(unsigned reg)
{
	if (!IsRunning())
		return 0;

	switch (reg)
	{
	case 0x00:
		return m_IRQ2ack;
	case 0x88:
		return m_status0;
	case 0x8a:
		return m_status1;
	default:
		ErrorLog("read from unknown IO register 0x%02x", reg);
		return 0;
	}
}

void CStandaloneNetBoard::WriteIORegister(unsigned reg, UINT16 data)
{
	switch (reg)
	{
	case 0x00:
		m_IRQ2ack = data;
		break;
	case 0x88:
		m_status0 = data;
		break;
	case 0x8a:
		m_status1 = data;
		break;
	case 0xc0:
		if (data == 0)
			Reset();
		m_running = (data != 0);
		break;
	default:
		ErrorLog("write to unknown IO register 0x%02x", reg);
	}
}
