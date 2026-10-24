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

#ifndef INCLUDED_STANDALONENETBOARD_H
#define INCLUDED_STANDALONENETBOARD_H

#include "INetBoard.h"

/*
 * Single-cabinet Model 3 network board.
 *
 * Several Model 3 games (Daytona USA 2, Scud Race, Sega Rally 2, ...) refuse
 * to leave their boot self-test when the network board is absent.  The full
 * CNetBoard/CSimNetBoard implementations depend on SDL_net and on a second
 * linked instance, neither of which exists in the Android libretro build.
 *
 * This implementation keeps the same comm-RAM and I/O-register protocol but
 * reports a valid one-cabinet link: the board is attached, self-test completes
 * with zero remote machines, and the ready-state loopback copies the local
 * segment back into the receive slot.  Link play remains unsupported.
 */
class CStandaloneNetBoard : public INetBoard
{
public:
	CStandaloneNetBoard(void);
	~CStandaloneNetBoard(void) = default;

	void SaveState(CBlockFile* SaveState) override;
	void LoadState(CBlockFile* SaveState) override;

	bool Init(UINT8* netRAMPtr, UINT8* netBufferPtr) override;
	void RunFrame(void) override;
	void Reset(void) override;

	bool IsAttached(void) override;
	bool IsRunning(void) override;

	void GetGame(const Game& gameInfo) override;

	UINT8 ReadCommRAM8(unsigned addr) override;
	UINT16 ReadCommRAM16(unsigned addr) override;
	UINT32 ReadCommRAM32(unsigned addr) override;

	void WriteCommRAM8(unsigned addr, UINT8 data) override;
	void WriteCommRAM16(unsigned addr, UINT16 data) override;
	void WriteCommRAM32(unsigned addr, UINT32 data) override;

	UINT16 ReadIORegister(unsigned reg) override;
	void WriteIORegister(unsigned reg, UINT16 data) override;

private:
	bool IsGame(const char* gameName) const;

	UINT8* RAM = nullptr;
	UINT8* Buffer = nullptr;
	UINT8* CommRAM = nullptr;
	UINT8* externalCommRAM = nullptr;

	Game m_gameInfo{};
	int m_gameType = 0; // 1 = Daytona-style, 2 = Dirt Devils/Le Mans-style
	bool m_attached = false;
	bool m_running = false;
	bool m_commbank = false;

	UINT16 m_status0 = 0;
	UINT16 m_status1 = 0;
	UINT16 m_IRQ2ack = 0;
	UINT16 m_counter = 0;
	UINT16 m_segmentSize = 0;

	enum class State
	{
		start,
		init,
		testing,
		ready,
		error
	};

	State m_state = State::start;
};

#endif // INCLUDED_STANDALONENETBOARD_H
