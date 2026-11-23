# Oracle suites: owner content lives in oracle-content/ on the device (never committed).
# App pad masks (pressed=1): Start 8, Up 16, Right 32, Down 64, Left 128,
# Cross 16384, Circle 8192, Square 32768, Coin 1.

SUITE_md_CORE=1
SUITE_md_GAME="Golden Axe (World).md"
SUITE_md_FRAMES=1800
SUITE_md_INPUT="400-410:8,600-610:8,800-810:8,1000-1700:32,1200-1210:16384,1500-1510:16384"

SUITE_sor_CORE=1
SUITE_sor_GAME="Streets of Rage (World).md"
SUITE_sor_FRAMES=1800
SUITE_sor_INPUT="500-510:8,800-810:8,1000-1010:8,1200-1700:32"

SUITE_sms_CORE=1
SUITE_sms_GAME="Alex Kidd in Shinobi World (USA, Europe, Brazil) (En).sms"
SUITE_sms_FRAMES=1500
SUITE_sms_INPUT="300-310:8,600-610:8,800-1400:32"

SUITE_s32x_CORE=1
SUITE_s32x_GAME="Virtua Racing Deluxe (Europe).32x"
SUITE_s32x_FRAMES=1800
SUITE_s32x_INPUT="600-610:8,900-910:8,1100-1110:8,1300-1800:16384"

SUITE_doom_CORE=1
SUITE_doom_GAME="Doom (Japan, USA).32x"
SUITE_doom_FRAMES=1800
SUITE_doom_INPUT="600-610:8,800-810:8,1000-1010:8,1200-1800:16"

SUITE_scd_CORE=1
SUITE_scd_GAME="Sonic CD (Europe).chd"
SUITE_scd_FRAMES=1800

SUITE_sat_CORE=3
SUITE_sat_GAME="Panzer Dragoon (USA) (5S).chd"
SUITE_sat_FRAMES=${SAT_FRAMES:-1200}
SUITE_sat_OPTIONS="beetle_saturn_autortc=disabled;beetle_saturn_midsync=disabled"

SUITE_dc_CORE=0
SUITE_dc_GAME="Re-Volt (Europe) (EnFrDeEsIt).chd"
SUITE_dc_FRAMES=${DC_FRAMES:-1800}
SUITE_dc_OPTIONS="reicast_emucoreh_fixed_rtc=enabled"
SUITE_dc_SYSFILES="@C@/Sega Dreamcast BIOS v1.01d (1998)(Sega)(Eu).bin=>dc/dc_boot.bin"

SUITE_m3_CORE=2
SUITE_m3_GAME="srally2.zip"
SUITE_m3_OPTIONS="supermodel_emucoreh_fixed_rtc=enabled"
SUITE_m3_FRAMES=${M3_FRAMES:-1800}
SUITE_m3_INPUT="900-910:1,1000-1010:8,1300-1310:8"

SUITE_vf3_CORE=2
SUITE_vf3_GAME="lemans24.zip"
SUITE_vf3_OPTIONS="supermodel_emucoreh_fixed_rtc=enabled"
SUITE_vf3_FRAMES=1800
SUITE_vf3_INPUT="900-910:1,1000-1010:8,1200-1210:8,1300-1800:16384"
