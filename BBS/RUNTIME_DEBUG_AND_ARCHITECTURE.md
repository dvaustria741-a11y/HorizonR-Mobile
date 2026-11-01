# BBS Runtime Architecture, Disassembly & Debug Reference (`Vasysik/horizon-android` + `HorizonZ`)

Folder **`BBS/`** menyimpan seluruh komponen **Runtime, Rust Engine, Native `.so` (`libhorizon_android.so`), dan Minecraft 26.3 Runner** dari upstream [`Vasysik/horizon-android`](https://github.com/Vasysik/horizon-android) beserta sistem **Auto-Update & Re-Apply Patch Otomatis** (`BBS/sync_and_merge_bbs.py`).

## 1. Struktur Folder `BBS/`

* **`BBS/sync_and_merge_bbs.py`**:
  Skrip sinkronisasi otomatis & 3-way patch merger. Saat terdapat update dari `https://github.com/Vasysik/horizon-android`, skrip ini:
  1. Menyalin file runtime upstream terbaru ke `BBS/runtime-rust/upstream_clean/` dan mencatat hash SHA-256 ke `BBS/upstream_state.json`.
  2. Menerapkan pembaruan upstream tersebut ke pohon source utama (`crates/`, `docs/`, `packaging/`).
  3. **Menerapkan ulang seluruh edit & patch kustom HorizonZ secara otomatis** di atas file upstream terbaru sehingga kustomisasi launcher tidak pernah hilang ketika upstream melakukan update.
  4. Menyimpan hasil file yang sudah dipatch ke `BBS/runtime-rust/patched_active/` dan menyalin binary `.so` (`arm64-v8a` & `armeabi-v7a`) ke `BBS/jni-so/`.
* **`BBS/custom_runtime_edits.json`**:
  Manifes deklaratif daftar file runtime yang dipantau dan aturan edit kustom HorizonZ.
* **`BBS/runtime-rust/upstream_clean/` & `BBS/runtime-rust/patched_active/`**:
  Salinan kode Rust/Java/Kotlin yang menjalankan Minecraft 26.3 (`launcher.rs`, `launcher_pages.rs`, `android_main.rs`, `android_resources.rs`, `official_install.rs`, `classify.rs`, `HorizonActivity.java`, `HorizonNativeBridge.kt`).
* **`BBS/jni-so/arm64-v8a/` & `BBS/jni-so/armeabi-v7a/`**:
  Lokasi staging binary native `libhorizon_android.so` beserta tabel simbol `exported_symbols.txt`.

## 2. Alur Eksekusi Runtime Minecraft 26.3 (`libhorizon_android.so`)

1. **Proses UI Launcher (`dev.horizon.android` — `ZalithMainActivity.kt`)**:
   - Menyiapkan direktori profil, memvalidasi `.hmod` di `horizon-mods/` (`syncAndRepairModsStorage`), menyinkronkan `options.txt` & `horizon-launcher.toml` (`saveConfig`), serta menyalin skin aktif 64×64 ke `filesDir/custom_skin_active.png` dan 9 slot skin bawaan `.local/horizon-client/26.3/assets/minecraft/textures/entity/player/{wide,slim}/`.
   - Menulis milestone awal ke `filesDir/zalith-game-boot.txt` lalu menjalankan `HorizonActivity` pada proses terpisah `:game` (`dev.horizon.android:game`).
   - Begitu `ZalithMainActivity` menerima `Lifecycle.Event.ON_STOP` saat `:game` tampil di depan, launcher masuk ke mode hibernasi (`isGameProcessSleeping = true`, 0% CPU/GPU/Audio).

2. **Proses Native Game (`dev.horizon.android:game` — `HorizonActivity.java` -> `libhorizon_android.so`)**:
   - `HorizonActivity` (`extends android.app.NativeActivity`) memuat `System.loadLibrary("horizon_android")` dan mengatur variabel lingkungan (`HORIZON_HOME`, `HORIZON_WGPU_BACKEND=vulkan|gl`, `HORIZON_PLAYER_NAME`, `HORIZON_ORIENTATION`, `HORIZON_ANDROID_PUBLIC=1`).
   - Menampilkan overlay splash hitam `HORIZONZ` yang memantau file IPC `zalith-game-boot.txt` setiap 60ms hingga mencapai `100|Main Menu Ready`.

3. **Entry Point Rust (`crates/horizon-android/src/lib.rs` -> `crates/horizon-client/src/android_main.rs`)**:
   - `#[unsafe(no_mangle)] fn android_main(app: AndroidApp)` menginisialisasi logger Android, membaca `HORIZON_HOME`, dan menjalankan event loop `winit` untuk `struct Launcher` (`crates/horizon-client/src/android/launcher.rs`).
   - Ekspor simbol JNI tambahan di `android_main.rs`:
     - `Java_dev_horizon_android_bridge_HorizonNativeBridge_nativeIsGameInstalled`
     - `Java_dev_horizon_android_bridge_HorizonNativeBridge_nativeStartOfficialInstall`
     - `Java_dev_horizon_android_bridge_HorizonNativeBridge_nativeGetInstallProgress`

4. **Direct Boot & Surface WGPU (`crates/horizon-client/src/android/launcher.rs`)**:
   - Pada `Launcher::resumed(&mut self, events: &ActiveEventLoop)`:
     - Memanggil `sanitize_and_repair_horizon_mods(&self.home)` dan `self.catalog.refresh(&self.home)` (`38%`).
     - Memanggil `self.open(events)` untuk menginisialisasi surface Vulkan / OpenGL ES (`wgpu-hal` / `ash` / `khronos-egl`) (`55%`).
     - Memanggil `apply_horizonz_custom_skin(&self.home)` (`78%`) dan langsung mengeksekusi `self.action(events)` (tanpa menampilkan menu egui internal `Horizon Android 0.7.5` karena `draw_pages()` di `launcher_pages.rs` di-bypass).
     - Setelah engine Minecraft 26.3 siap, menulis `100|Main Menu Ready` ke `zalith-game-boot.txt` sehingga splash `HORIZONZ` memudar (fade-out) ke Main Menu Minecraft!
