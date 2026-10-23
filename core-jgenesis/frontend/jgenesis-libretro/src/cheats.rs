//! Preserve device code syntax and delegate decoding to the upstream platform decoders.
use genesis_config::cheats::GenesisCheatCodeType;
use smsgg_config::cheats::SmsGgCheatCodeType;
use jgenesis_common::cheats::ByteCheatCodeU16Address;

fn codes<'a>(entries: impl Iterator<Item = &'a String>) -> impl Iterator<Item = String> {
    entries.flat_map(|entry| entry.split(['+', '\n', '\r']).map(str::trim)
        .filter(|code| !code.is_empty()).map(str::to_ascii_uppercase))
}

pub fn genesis_codes<'a>(entries: impl Iterator<Item = &'a String>) -> Vec<(u32, u16)> {
    codes(entries).filter_map(|code| GenesisCheatCodeType::guess_from(&code)
        .and_then(|kind| kind.decode(&code))).collect()
}

pub fn smsgg_codes<'a>(entries: impl Iterator<Item = &'a String>) -> Vec<ByteCheatCodeU16Address> {
    codes(entries).filter_map(|code| SmsGgCheatCodeType::guess_from(&code)
        .and_then(|kind| kind.decode(&code))).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn decodes_real_device_formats_and_rejects_foreign_or_invalid_codes() {
        let md = "97JT-EVSG+FF0000:1234+invalid+00C000:FF".to_owned();
        assert_eq!(genesis_codes([&md].into_iter()), [(0x0251E6, 0xA8FF), (0xFF0000, 0x1234)]);
        let sms = "00C000:1234+00C000FF+invalid".to_owned();
        let decoded = smsgg_codes([&sms].into_iter());
        assert_eq!(decoded.len(), 1);
        assert_eq!(decoded[0].address, 0xC000);
        assert_eq!(decoded[0].value, 0xFF);
    }
    #[test]
    #[ignore = "requires generated catalog; set JGENESIS_TEST_CHEAT_PACK_DIR"]
    fn generated_catalog_uses_only_upstream_decodable_codes() {
        let root = std::path::PathBuf::from(std::env::var("JGENESIS_TEST_CHEAT_PACK_DIR").unwrap());
        let mut total = 0;
        for platform in std::fs::read_dir(root).unwrap() {
            let platform = platform.unwrap();
            let genesis = matches!(platform.file_name().to_str(), Some("genesis" | "sega_cd" | "sega_32x"));
            for pack in std::fs::read_dir(platform.path()).unwrap() {
                let pack = pack.unwrap();
                let contents = std::fs::read_to_string(pack.path()).unwrap();
                for code in contents.lines().filter(|line| !line.is_empty() && !line.starts_with("//")) {
                    let code = code.to_owned();
                    let count = if genesis { genesis_codes(std::iter::once(&code)).len() }
                        else { smsgg_codes(std::iter::once(&code)).len() };
                    assert_eq!(count, 1, "Invalid device code in {}: {code}", pack.path().display());
                    total += count;
                }
            }
        }
        assert!(total > 40_000, "Expected the complete Sega catalog, found {total} codes");
        eprintln!("Verified {total} catalog device codes with upstream decoders");
    }

}
