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

# ---- Broad jgenesis library (PGO training + per-game performance sweep) ----
G_INPUT="200-205:8,400-405:8,600-605:8,800-805:16384,900-905:8,1000-1500:32,1100-1110:16384,1300-1310:32768,1500-1800:160"
add_suite() { eval "SUITE_$1_CORE=1; SUITE_$1_GAME=\"\$2\"; SUITE_$1_FRAMES=1800; SUITE_$1_INPUT=\"\$G_INPUT\""; }
add_suite gg_alien3 "Alien 3 (U) [!].gg"
add_suite gg_syndrome "Alien Syndrome (J).gg"
add_suite gg_castle "Castle of Illusion Starring Mickey Mouse (U) [S][!].gg"
add_suite gg_aleste "GG Aleste 3 (Japan) (En) (Aleste Collection).gg"
add_suite sg_onyx "Black Onyx, The (Japan) [T-En by SSTranslations v1.0] [Enhanced].sg"
add_suite sg_chack "Chack'n Pop (Japan) [T-En by Goldenband v1.02].sg"
add_suite sg_ninja "Ninja Princess (Japan) [T-En by Goldenband v1.0].sg"
add_suite md_shinobi "Revenge of Shinobi, The (USA, Europe).md"
add_suite md_outrun "OutRun (USA, Europe).md"
add_suite md_umk3 "Ultimate Mortal Kombat 3 (USA).md"
add_suite md_asteborg "Demons of Asteborg (World) (En,Fr,De,Es,It,Pt-BR) (Aftermarket) (Unl).md"
add_suite x_spiderman "Amazing Spider-Man, The - Web of Fire (USA).32x"
add_suite x_carnage "Cyber Brawl ~ Cosmic Carnage (Japan, USA).32x"
add_suite x_mk2 "Mortal Kombat II (Japan, USA).32x"
add_suite x_motocross "Motocross Championship (USA).32x"
add_suite x_shadow "Shadow Squadron ~ Stellar Assault (USA, Europe).32x"
add_suite cd_mk "Mortal Kombat (USA).chd"
add_suite cd_terminator "Terminator The (USA).chd"
JG_ALL="md sor sms s32x doom scd gg_alien3 gg_syndrome gg_castle gg_aleste sg_onyx sg_chack sg_ninja md_shinobi md_outrun md_umk3 md_asteborg x_spiderman x_carnage x_mk2 x_motocross x_shadow cd_mk cd_terminator"

# ---- Broad Flycast library ----
DC_BIOS="@C@/Sega Dreamcast BIOS v1.01d (1998)(Sega)(Eu).bin=>dc/dc_boot.bin"
add_dc() { eval "SUITE_$1_CORE=0; SUITE_$1_GAME=\"\$2\"; SUITE_$1_FRAMES=2400; SUITE_$1_INPUT=\"\$G_INPUT\"; SUITE_$1_OPTIONS=\"reicast_emucoreh_fixed_rtc=enabled\"; SUITE_$1_SYSFILES=\"\$3\""; }
add_dc dc_daytona "Daytona USA 2001 (Europe) (EnFrDeEs).chd" "$DC_BIOS"
add_dc dc_ut "Unreal Tournament (USA) (EnFrDeEsIt).chd" "$DC_BIOS"
add_dc dc_vf3 "Virtua Fighter 3tb (USA).chd" "$DC_BIOS"
add_dc dc_toy "Disney-Pixar Toy Story 2 - Buzz Lightyear to the Rescue (USA).chd" "$DC_BIOS"
add_dc nao_cvs2 "capcom_vs_snk_2.zip" "@C@/naomi.zip=>dc/naomi.zip"
SUITE_nao_cvs2_INPUT="200-210:1,400-410:1,600-610:8,800-810:8,1000-1010:16384,1200-1800:32"
add_dc nao2_vf4 "vf4tuned.zip" "@C@/naomi2.zip=>dc/naomi2.zip"
SUITE_nao2_vf4_INPUT="$SUITE_nao_cvs2_INPUT"
FC_ALL="dc dc_daytona dc_ut dc_vf3 dc_toy nao_cvs2 nao2_vf4"
SUITE_cd_mk_SYSFILES="@C@/bios_CD_U.bin=>bios_CD_U.bin"
SUITE_cd_terminator_SYSFILES="@C@/bios_CD_U.bin=>bios_CD_U.bin"
add_sat() { eval "SUITE_$1_CORE=3; SUITE_$1_GAME=\"\$2\"; SUITE_$1_FRAMES=2400; SUITE_$1_INPUT=\"\$G_INPUT\"; SUITE_$1_OPTIONS=\"beetle_saturn_autortc=disabled;beetle_saturn_midsync=disabled\""; }
add_sat sat_sonicr "Sonic R (USA, Brazil).cue"
add_sat sat_daytona "Daytona USA (USA).cue"
add_dc dc_sa2 "Sonic Adventure 2 (USA) (En,Ja,Fr,De,Es).cue" "$DC_BIOS"
add_dc dc_shenmue "Shenmue (Europe) (En,Fr,De,Es) (Shenmue Passport).cue" "$DC_BIOS"
add_dc dc_doa2 "Dead or Alive 2 (USA).cue" "$DC_BIOS"
add_dc dc_taxi "Crazy Taxi (USA).cue" "$DC_BIOS"
SAT_ALL="sat sat_sonicr sat_daytona"
FC_ALL="$FC_ALL dc_sa2 dc_shenmue dc_doa2 dc_taxi"
