//! Windows build (GNU toolchain):
//! - the icon and the product details (name, version, maker) go into aperit.exe;
//! - WebView2Loader.dll is linked to load when it is first used rather than at start, and is carried inside
//!   the exe, so `aperit.exe` alone runs. `src/loader.rs` puts the DLL in place before it is needed.
//!
//! Needs `windres`, `dlltool` and `gendef` from MinGW on the PATH, as the build already does.

use std::path::{Path, PathBuf};
use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=assets/aperit.ico");
    let windows_gnu = std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") && std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("gnu");
    if !windows_gnu {
        return;
    }
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap());
    resources(&out);
    webview2_loader(&out);
}

fn run(cmd: &mut Command) {
    let status = cmd.status().unwrap_or_else(|e| panic!("could not run {cmd:?}: {e} (MinGW's bin folder must be on the PATH)"));
    assert!(status.success(), "{cmd:?} failed");
}

/// The icon and the version details, compiled with windres and linked into the program.
fn resources(out: &Path) {
    let root = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let icon = root.join("assets").join("aperit.ico");
    let version = std::env::var("CARGO_PKG_VERSION").unwrap();
    let nums: Vec<u32> = version.split(['.', '-']).filter_map(|p| p.parse().ok()).chain(std::iter::repeat(0)).take(4).collect();
    let comma = nums.iter().map(u32::to_string).collect::<Vec<_>>().join(",");
    let rc = format!(
        r#"1 ICON "{icon}"
1 VERSIONINFO
FILEVERSION {comma}
PRODUCTVERSION {comma}
FILEOS 0x40004
FILETYPE 0x1
BEGIN
  BLOCK "StringFileInfo"
  BEGIN
    BLOCK "040904B0"
    BEGIN
      VALUE "CompanyName", "fidenit"
      VALUE "FileDescription", "APERIT"
      VALUE "FileVersion", "{version}"
      VALUE "InternalName", "aperit"
      VALUE "LegalCopyright", "Copyright 2026 fidenit. Apache License 2.0"
      VALUE "OriginalFilename", "aperit.exe"
      VALUE "ProductName", "APERIT"
      VALUE "ProductVersion", "{version}"
    END
  END
  BLOCK "VarFileInfo"
  BEGIN
    VALUE "Translation", 0x409, 1200
  END
END
"#,
        icon = icon.display().to_string().replace('\\', "/"),
    );
    let rc_path = out.join("aperit.rc");
    std::fs::write(&rc_path, rc).unwrap();
    let obj = out.join("aperit-resources.o");
    run(Command::new(std::env::var("WINDRES").unwrap_or_else(|_| "windres".into())).args(["-O", "coff", "-i"]).arg(&rc_path).arg("-o").arg(&obj));
    println!("cargo:rustc-link-arg-bins={}", obj.display());
}

/// A delay-load import library for WebView2Loader.dll, found first by the linker, and a copy of the DLL for
/// the program to carry.
fn webview2_loader(out: &Path) {
    let dll = find_loader().expect("WebView2Loader.dll from the webview2-com-sys crate");
    std::fs::copy(&dll, out.join("WebView2Loader.dll")).unwrap();
    let def = out.join("WebView2Loader.def");
    let listed = Command::new("gendef").arg("-").arg(&dll).output().expect("gendef");
    assert!(listed.status.success(), "gendef failed");
    std::fs::write(&def, listed.stdout).unwrap();
    // webview2-com-sys asks the linker for the library "WebView2Loader.dll". The first file name the GNU
    // linker tries for that is lib<name>.dll.a, so this one wins over the crate's ordinary import library.
    let lib = out.join("libWebView2Loader.dll.dll.a");
    run(Command::new("dlltool").arg("-d").arg(&def).args(["-D", "WebView2Loader.dll", "-y"]).arg(&lib));
    println!("cargo:rustc-link-search=native={}", out.display());
    println!("cargo:rustc-link-lib=static=delayimp");
}

/// The x64 WebView2Loader.dll shipped in the webview2-com-sys crate, in the version Cargo.lock names.
fn find_loader() -> Option<PathBuf> {
    let lock = std::fs::read_to_string(PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").ok()?).join("Cargo.lock")).ok()?;
    let at = lock.find("name = \"webview2-com-sys\"")?;
    let version = lock[at..].lines().nth(1)?.trim().strip_prefix("version = \"")?.trim_end_matches('"').to_string();
    let home = std::env::var("CARGO_HOME").map(PathBuf::from).or_else(|_| std::env::var("USERPROFILE").map(|h| PathBuf::from(h).join(".cargo"))).ok()?;
    for registry in std::fs::read_dir(home.join("registry").join("src")).ok()?.flatten() {
        let dll = registry.path().join(format!("webview2-com-sys-{version}")).join("x64").join("WebView2Loader.dll");
        if dll.exists() {
            return Some(dll);
        }
    }
    None
}
