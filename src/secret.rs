//! The keys the user gives the app: Claude, TypeSafe Jev, ElevenLabs, an OpenAI-compatible service, and the
//! header lines an AG-UI agent asks for. None of them is kept in plain text anywhere.
//!
//! - Windows: encrypted for this user account on this computer (DPAPI) in `data/keys.bin`. Copied to another
//!   computer or opened by another account, the file is unreadable.
//! - macOS: one item in the user's login keychain.
//! - Elsewhere: only for as long as the program runs.
//!
//! What it does not stop: a program running as the same user on the same computer can ask the system to
//! decrypt it, as it could read anything else of that user's.
//!
//! The first time, keys left in plain-text files by earlier versions (`key.txt`, `jev.txt`, `eleven.txt`, the
//! third line of `openai.txt`, the header lines of `agui.txt`) are moved in, checked, and removed.

use std::collections::BTreeMap;
use std::sync::Mutex;

pub const CLAUDE: &str = "claude";
pub const JEV: &str = "jev";
pub const ELEVEN: &str = "elevenlabs";
pub const OPENAI: &str = "openai";
/// `Header-Name: value` lines, one per line, sent to an AG-UI agent.
pub const AGUI_HEADERS: &str = "agui-headers";

static KEYS: Mutex<Option<BTreeMap<String, String>>> = Mutex::new(None);

/// A key, or None when there is none.
pub fn get(name: &str) -> Option<String> {
    with(|m| m.get(name).cloned()).filter(|v| !v.trim().is_empty())
}

/// Keep a key; an empty value removes it. Says why when it could not be kept.
pub fn set(name: &str, value: &str) -> Result<(), String> {
    let value = value.trim();
    let mut all = with(|m| m.clone());
    if value.is_empty() {
        all.remove(name);
    } else {
        all.insert(name.to_string(), value.to_string());
    }
    store::save(&all)?;
    *KEYS.lock().unwrap() = Some(all);
    Ok(())
}

fn with<T>(f: impl FnOnce(&BTreeMap<String, String>) -> T) -> T {
    let mut slot = KEYS.lock().unwrap();
    if slot.is_none() {
        let mut all = store::load();
        migrate(&mut all);
        *slot = Some(all);
    }
    f(slot.as_ref().unwrap())
}

/// Keys that earlier versions wrote in plain text next to the program: move them in, then remove the files.
/// A file is removed only after the store was written and read back with the key in it.
fn migrate(all: &mut BTreeMap<String, String>) {
    if !store::PERSISTS {
        // Nothing would keep them: leave the files, and read them for this run.
        for (name, value, _) in leftovers() {
            all.entry(name).or_insert(value);
        }
        return;
    }
    let found = leftovers();
    if found.is_empty() {
        return;
    }
    let mut next = all.clone();
    for (name, value, _) in &found {
        // A file found now was put there after the last move: it is the newer key.
        next.insert(name.clone(), value.clone());
    }
    if store::save(&next).is_err() || store::load() != next {
        crate::sys::log("keys.log", "could not move plain-text keys into the encrypted store; the files were left as they were");
        *all = next;
        return;
    }
    *all = next;
    for (_, _, undo) in found {
        undo();
    }
    crate::sys::log("keys.log", "moved plain-text keys into the encrypted store and removed the files");
}

type Undo = Box<dyn FnOnce()>;

/// (name, value, what removes it from its file)
fn leftovers() -> Vec<(String, String, Undo)> {
    let base = crate::sys::base_dir();
    let read = |f: &str| std::fs::read_to_string(base.join(f)).ok();
    let mut out: Vec<(String, String, Undo)> = Vec::new();
    for (file, name) in [("key.txt", CLAUDE), ("jev.txt", JEV), ("eleven.txt", ELEVEN)] {
        if let Some(v) = read(file).map(|s| s.trim().to_string()).filter(|s| !s.is_empty()) {
            let path = base.join(file);
            out.push((name.to_string(), v, Box::new(move || remove(&path))));
        }
    }
    // openai.txt: address, model, key. The first two stay; they are not secret.
    if let Some(text) = read("openai.txt") {
        let lines: Vec<&str> = text.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#')).collect();
        if let Some(key) = lines.get(2).map(|k| k.to_string()) {
            let keep = format!("{}\n{}\n", lines[0], lines.get(1).copied().unwrap_or(""));
            let path = base.join("openai.txt");
            out.push((OPENAI.to_string(), key, Box::new(move || {
                let _ = std::fs::write(&path, keep);
            })));
        }
    }
    // agui.txt: the address, then header lines. The address stays.
    if let Some(text) = read("agui.txt") {
        let lines: Vec<&str> = text.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#')).collect();
        if lines.len() > 1 {
            let headers = lines[1..].join("\n");
            let keep = format!("{}\n", lines[0]);
            let path = base.join("agui.txt");
            out.push((AGUI_HEADERS.to_string(), headers, Box::new(move || {
                let _ = std::fs::write(&path, keep);
            })));
        }
    }
    out
}

/// Overwrite, then delete: what was on the disk does not stay readable in the file's old place.
fn remove(path: &std::path::Path) {
    if let Ok(meta) = std::fs::metadata(path) {
        let _ = std::fs::write(path, vec![0u8; meta.len() as usize]);
    }
    let _ = std::fs::remove_file(path);
}

fn encode(all: &BTreeMap<String, String>) -> Vec<u8> {
    serde_json::to_vec(all).unwrap_or_default()
}

fn decode(bytes: &[u8]) -> BTreeMap<String, String> {
    serde_json::from_slice(bytes).unwrap_or_default()
}

#[cfg(windows)]
mod store {
    //! DPAPI: CryptProtectData / CryptUnprotectData, scoped to the current user.
    use std::collections::BTreeMap;
    use std::ffi::c_void;

    pub const PERSISTS: bool = true;

    #[repr(C)]
    struct Blob {
        len: u32,
        data: *mut u8,
    }

    #[link(name = "crypt32")]
    unsafe extern "system" {
        fn CryptProtectData(input: *const Blob, description: *const u16, entropy: *const Blob, reserved: *mut c_void, prompt: *mut c_void, flags: u32, output: *mut Blob) -> i32;
        fn CryptUnprotectData(input: *const Blob, description: *mut *mut u16, entropy: *const Blob, reserved: *mut c_void, prompt: *mut c_void, flags: u32, output: *mut Blob) -> i32;
    }
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn LocalFree(mem: *mut c_void) -> *mut c_void;
    }

    /// Never show a dialog.
    const UI_FORBIDDEN: u32 = 0x1;
    /// Mixed into the encryption, so the file is this program's and not any DPAPI blob.
    const ENTROPY: &[u8] = b"APERIT keys v1";

    fn path() -> std::path::PathBuf {
        crate::sys::data_dir().join("keys.bin")
    }

    fn run(protect: bool, input: &[u8]) -> Option<Vec<u8>> {
        let inb = Blob { len: input.len() as u32, data: input.as_ptr() as *mut u8 };
        let ent = Blob { len: ENTROPY.len() as u32, data: ENTROPY.as_ptr() as *mut u8 };
        let mut out = Blob { len: 0, data: std::ptr::null_mut() };
        let ok = unsafe {
            if protect {
                CryptProtectData(&inb, std::ptr::null(), &ent, std::ptr::null_mut(), std::ptr::null_mut(), UI_FORBIDDEN, &mut out)
            } else {
                CryptUnprotectData(&inb, std::ptr::null_mut(), &ent, std::ptr::null_mut(), std::ptr::null_mut(), UI_FORBIDDEN, &mut out)
            }
        };
        if ok == 0 || out.data.is_null() {
            return None;
        }
        let bytes = unsafe { std::slice::from_raw_parts(out.data, out.len as usize) }.to_vec();
        unsafe {
            std::ptr::write_bytes(out.data, 0, out.len as usize);
            LocalFree(out.data as *mut c_void);
        }
        Some(bytes)
    }

    pub fn load() -> BTreeMap<String, String> {
        std::fs::read(path()).ok().and_then(|b| run(false, &b)).map(|b| super::decode(&b)).unwrap_or_default()
    }

    #[cfg(test)]
    mod tests {
        #[test]
        fn sealed_keys_read_back_and_do_not_show() {
            let mut all = std::collections::BTreeMap::new();
            all.insert("claude".to_string(), "sk-ant-api03-THIS-IS-A-TEST".to_string());
            let sealed = super::run(true, &super::super::encode(&all)).expect("seal");
            assert!(!sealed.windows(6).any(|w| w == b"sk-ant"), "the key shows in the sealed bytes");
            assert_eq!(super::super::decode(&super::run(false, &sealed).expect("open")), all);
            // Sealed without this program's entropy, it does not open as ours.
            let mut other = sealed.clone();
            let n = other.len();
            other[n - 1] ^= 0xFF;
            assert!(super::run(false, &other).is_none());
        }
    }

    pub fn save(all: &BTreeMap<String, String>) -> Result<(), String> {
        let sealed = run(true, &super::encode(all)).ok_or("Windows could not encrypt the keys")?;
        let tmp = path().with_extension("tmp");
        std::fs::write(&tmp, sealed).map_err(|e| e.to_string())?;
        std::fs::rename(&tmp, path()).map_err(|e| e.to_string())
    }
}

#[cfg(target_os = "macos")]
mod store {
    //! One generic password item in the login keychain, holding all keys.
    use std::collections::BTreeMap;
    use std::ffi::c_void;

    pub const PERSISTS: bool = true;

    #[link(name = "Security", kind = "framework")]
    unsafe extern "C" {
        fn SecKeychainFindGenericPassword(keychain: *const c_void, service_len: u32, service: *const u8, account_len: u32, account: *const u8, password_len: *mut u32, password: *mut *mut c_void, item: *mut *mut c_void) -> i32;
        fn SecKeychainAddGenericPassword(keychain: *const c_void, service_len: u32, service: *const u8, account_len: u32, account: *const u8, password_len: u32, password: *const c_void, item: *mut *mut c_void) -> i32;
        fn SecKeychainItemModifyAttributesAndData(item: *mut c_void, attributes: *const c_void, length: u32, data: *const c_void) -> i32;
        fn SecKeychainItemFreeContent(attributes: *mut c_void, data: *mut c_void) -> i32;
    }
    #[link(name = "CoreFoundation", kind = "framework")]
    unsafe extern "C" {
        fn CFRelease(object: *const c_void);
    }

    const SERVICE: &[u8] = b"APERIT";
    const ACCOUNT: &[u8] = b"keys";

    fn find() -> (Option<Vec<u8>>, *mut c_void) {
        let (mut len, mut data, mut item) = (0u32, std::ptr::null_mut(), std::ptr::null_mut());
        let status = unsafe { SecKeychainFindGenericPassword(std::ptr::null(), SERVICE.len() as u32, SERVICE.as_ptr(), ACCOUNT.len() as u32, ACCOUNT.as_ptr(), &mut len, &mut data, &mut item) };
        if status != 0 || data.is_null() {
            return (None, item);
        }
        let bytes = unsafe { std::slice::from_raw_parts(data as *const u8, len as usize) }.to_vec();
        unsafe {
            SecKeychainItemFreeContent(std::ptr::null_mut(), data);
        }
        (Some(bytes), item)
    }

    pub fn load() -> BTreeMap<String, String> {
        let (bytes, item) = find();
        if !item.is_null() {
            unsafe { CFRelease(item) };
        }
        bytes.map(|b| super::decode(&b)).unwrap_or_default()
    }

    pub fn save(all: &BTreeMap<String, String>) -> Result<(), String> {
        let data = super::encode(all);
        let (_, item) = find();
        let status = unsafe {
            if item.is_null() {
                SecKeychainAddGenericPassword(std::ptr::null(), SERVICE.len() as u32, SERVICE.as_ptr(), ACCOUNT.len() as u32, ACCOUNT.as_ptr(), data.len() as u32, data.as_ptr() as *const c_void, std::ptr::null_mut())
            } else {
                let s = SecKeychainItemModifyAttributesAndData(item, std::ptr::null(), data.len() as u32, data.as_ptr() as *const c_void);
                CFRelease(item);
                s
            }
        };
        if status == 0 { Ok(()) } else { Err(format!("the keychain refused the keys (status {status})")) }
    }
}

#[cfg(not(any(windows, target_os = "macos")))]
mod store {
    //! No store this program trusts here: keys live only while it runs.
    use std::collections::BTreeMap;

    pub const PERSISTS: bool = false;

    pub fn load() -> BTreeMap<String, String> {
        BTreeMap::new()
    }

    pub fn save(_: &BTreeMap<String, String>) -> Result<(), String> {
        Ok(())
    }
}
