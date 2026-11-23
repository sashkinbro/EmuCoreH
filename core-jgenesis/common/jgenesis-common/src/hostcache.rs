//! Host-only memoization stored next to emulated state.
//!
//! A `HostCache` holds data that can always be recomputed from the surrounding emulated state
//! (lookup memos, derived values). It is never serialized: encoding writes zero bytes and decoding
//! produces `T::default()`, so savestate formats and state hashes are unchanged. `T::default()`
//! must therefore always describe an empty/invalid cache.

use bincode::de::{BorrowDecoder, Decoder};
use bincode::enc::Encoder;
use bincode::error::{DecodeError, EncodeError};
use bincode::{BorrowDecode, Decode, Encode};
use std::ops::{Deref, DerefMut};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct HostCache<T>(pub T);

impl<T> Deref for HostCache<T> {
    type Target = T;

    #[inline(always)]
    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

impl<T> DerefMut for HostCache<T> {
    #[inline(always)]
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.0
    }
}

impl<T> Encode for HostCache<T> {
    fn encode<E: Encoder>(&self, _encoder: &mut E) -> Result<(), EncodeError> {
        Ok(())
    }
}

impl<T: Default, Context> Decode<Context> for HostCache<T> {
    fn decode<D: Decoder<Context = Context>>(_decoder: &mut D) -> Result<Self, DecodeError> {
        Ok(Self::default())
    }
}

impl<'de, T: Default, Context> BorrowDecode<'de, Context> for HostCache<T> {
    fn borrow_decode<D: BorrowDecoder<'de, Context = Context>>(
        _decoder: &mut D,
    ) -> Result<Self, DecodeError> {
        Ok(Self::default())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Debug, Clone, PartialEq, Encode, Decode)]
    struct WithCache {
        a: u32,
        memo: HostCache<u64>,
        b: u16,
    }

    #[derive(Debug, Clone, PartialEq, Encode, Decode)]
    struct WithoutCache {
        a: u32,
        b: u16,
    }

    #[test]
    fn host_cache_is_invisible_to_serialization() {
        let config = bincode::config::standard();
        let with = WithCache { a: 7, memo: HostCache(99), b: 3 };
        let without = WithoutCache { a: 7, b: 3 };
        let bytes = bincode::encode_to_vec(&with, config).unwrap();
        assert_eq!(bytes, bincode::encode_to_vec(&without, config).unwrap());
        let (decoded, _): (WithCache, usize) = bincode::decode_from_slice(&bytes, config).unwrap();
        assert_eq!(decoded, WithCache { a: 7, memo: HostCache(0), b: 3 });
    }
}
