// Cartridge pin-42 wiring cannot be recovered from a Sega ROM header.
// Size/CRC records from MAME gamegear.xml (CC0-1.0 data), pinned source:
// https://github.com/mamedev/mame/blob/954def46685cd0276671138fbd032036b1a771fb/hash/gamegear.xml
// Only records explicitly declaring pin_42=sms_mode are included.
const SMS_MODE_CARTRIDGES: &[(usize, u32)] = &[
    (32768, 0x9083e393),  // Pro Action Replay (v1.01)
    (32768, 0xd0263024),  // Seishun Scandal (Taiwan, SMS mode)
    (65536, 0xbd1cc7df),  // Super Tetris (Korea, SMS mode?)
    (131072, 0x354bee78), // Paperboy (Taiwan, SMS mode)
    (131072, 0x4762e022), // Kung Fu Kid (Taiwan, SMS mode)
    (131072, 0x5e4b454e), // Argos no Juujiken (Taiwan, SMS mode)
    (131072, 0x5e7b18c8), // Wonder Kid (USA, prototype)
    (131072, 0x6f8e46cf), // Alex Kidd in Miracle World (Taiwan, SMS mode)
    (131072, 0x6fe448a5), // Great Basketball (Taiwan, SMS mode)
    (131072, 0x7bb81e3d), // Taito Chase H.Q. (Japan, SMS mode)
    (131072, 0x89efcc22), // Secret Command (Taiwan, SMS mode)
    (131072, 0x98f64975), // Black Belt (Taiwan, SMS mode)
    (131072, 0xb6207f0d), // Hokuto no Ken (Taiwan, SMS mode)
    (131072, 0xc8381def), // Taito Chase H.Q. (USA, SMS mode)
    (131072, 0xcacdf759), // Quartet (Taiwan, SMS mode)
    (131072, 0xfb163003), // Doki Doki Penguin Land - Uchuu Daibouken (Taiwan, SMS mode)
    (262144, 0x01eab89d), // Out Run Europa (Europe, Brazil, SMS mode)
    (262144, 0x10dbbef4), // Super Kick Off (Europe, SMS mode)
    (262144, 0x1d93246e), // Olympic Gold (Europe, v1, SMS mode)
    (262144, 0x311d2863), // Prince of Persia (USA, SMS mode)
    (262144, 0x44136a72), // Forgotten Worlds (Taiwan, SMS mode)
    (262144, 0x45f058d6), // Prince of Persia (Europe, SMS mode)
    (262144, 0x56201996), // R.C. Grand Prix (USA, SMS mode)
    (262144, 0x59840fd6), // Castle of Illusion Starring Mickey Mouse (Europe, USA, SMS mode)
    (262144, 0x6630e5fd), // Aerial Assault (Taiwan, SMS mode)
    (262144, 0x76c5bdfb), // Jang Pung II (Kor, SMS mode)
    (262144, 0x7d59283b), // Scramble Spirits (Taiwan, SMS mode)
    (262144, 0x7eaed675), // Lord of Sword (Taiwan, SMS mode)
    (262144, 0x8813514b), // The Excellent Dizzy Collection (prototype, SMS mode)
    (262144, 0x98cf1254), // Thunder Blade (Taiwan, SMS mode)
    (262144, 0x9942b69b), // Mickey Mouse no Castle Illusion (Japan, SMS mode)
    (262144, 0x9c76fb3a), // Rastan Saga (Japan, SMS mode)
    (262144, 0xa2f9c7af), // Olympic Gold (Japan, USA, v0, SMS mode)
    (262144, 0xac2ea669), // Shadow of the Beast (Taiwan, SMS mode)
    (262144, 0xad9ff469), // The Cyber Shinobi (Taiwan, SMS mode)
    (262144, 0xb948752e), // Final Bubble Bobble (Taiwan, SMS mode)
    (262144, 0xc888222b), // Fantastic Dizzy (Europe, SMS mode)
    (262144, 0xcafd2d83), // Prince of Persia (Taiwan, SMS mode)
    (262144, 0xcc521975), // Cave Dude (USA, prototype)
    (262144, 0xd282ef71), // Submarine Attack (Taiwan, SMS mode)
    (262144, 0xda8e95a9), // WWF WrestleMania: Steel Cage Challenge (Europe, SMS mode)
    (262144, 0xe5f789b9), // Predator 2 (Europe, USA, SMS mode)
    (262144, 0xf037ec00), // Out Run Europa (USA, SMS mode)
    (262144, 0xf4f848c2), // Double Dragon (Taiwan, SMS mode)
    (524288, 0x01a2d595), // Street Battle (US, SMS mode, prototype)
    (524288, 0x63a7f906), // Strider (Taiwan, SMS mode)
    (524288, 0x9fa727a0), // Street Hero (USA, prototype, SMS mode)
    (524288, 0xaa140c9c), // The Excellent Dizzy Collection (Europe, SMS mode)
    (524288, 0xab67c6bd), // Shadow Dancer (Taiwan, SMS mode)
    (524288, 0xe532716f), // R-Type (Taiwan, SMS mode)
    (524288, 0xfb481971), // Street Hero (USA, prototype alt, SMS mode)
];

pub fn game_gear_uses_sms_mode(bytes: &[u8]) -> bool {
    if !SMS_MODE_CARTRIDGES.iter().any(|(size, _)| *size == bytes.len()) {
        return false;
    }
    let crc = crc::Crc::<u32>::new(&crc::CRC_32_ISO_HDLC).checksum(bytes);
    if SMS_MODE_CARTRIDGES.contains(&(bytes.len(), crc)) {
        return true;
    }
    // Owner-provided Japanese Castle h1 variant: host-verified identical SMS
    // hardware requirement. Require its original boot/header signatures too.
    bytes.len() == 262144
        && crc == 0x5877b10d
        && bytes[..8] == [0xf3, 0xed, 0x56, 0xc3, 0x02, 0x01, 0xff, 0xff]
        && bytes[0x7ff0..0x8000]
            == [0x54, 0x4d, 0x52, 0x20, 0x53, 0x45, 0x47, 0x41, 0, 0, 0, 0, 0x01, 0x33, 0, 0x50]
}
