//! WebView2Loader.dll, which web cards need, is carried inside the program (see build.rs) and linked to load
//! when first used. Before that happens it has to be somewhere Windows finds it: next to the program if it is
//! there already (an installed copy), otherwise written to the data folder and loaded from there by full path.
//! Once a module named WebView2Loader.dll is loaded, every later request for it gets that one.

#[cfg(all(windows, target_env = "gnu"))]
pub fn prepare() {
    const DLL: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/WebView2Loader.dll"));
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn LoadLibraryW(name: *const u16) -> *mut std::ffi::c_void;
    }
    let beside = std::env::current_exe().ok().and_then(|e| e.parent().map(|d| d.join("WebView2Loader.dll")));
    if beside.as_ref().map(|p| p.exists()).unwrap_or(false) {
        return;
    }
    let path = crate::sys::data_dir().join("WebView2Loader.dll");
    let same = std::fs::read(&path).map(|b| b == DLL).unwrap_or(false);
    if !same && std::fs::write(&path, DLL).is_err() {
        crate::sys::log("ui.log", "could not write WebView2Loader.dll to the data folder; web cards will not open");
        return;
    }
    let wide: Vec<u16> = path.as_os_str().encode_wide_null();
    if unsafe { LoadLibraryW(wide.as_ptr()) }.is_null() {
        crate::sys::log("ui.log", "could not load WebView2Loader.dll; web cards will not open");
    }
}

#[cfg(not(all(windows, target_env = "gnu")))]
pub fn prepare() {}

#[cfg(all(windows, target_env = "gnu"))]
trait WideNull {
    fn encode_wide_null(&self) -> Vec<u16>;
}

#[cfg(all(windows, target_env = "gnu"))]
impl WideNull for std::ffi::OsStr {
    fn encode_wide_null(&self) -> Vec<u16> {
        use std::os::windows::ffi::OsStrExt;
        self.encode_wide().chain(std::iter::once(0)).collect()
    }
}
