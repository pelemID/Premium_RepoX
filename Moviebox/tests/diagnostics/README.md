# Reacher diagnostic kit (tanpa root)

Kit ini **tidak memperbaiki atau mengganti playback**. Tidak memasang APK Mod, membaca akun,
mengakses data privat aplikasi, menangkap HTTPS aplikasi lain, mengubah DNS/region/TLS,
mengekspor token, atau memulai unduhan video. Kode ini tidak masuk ke plugin produksi.

## Termux

Ekstrak `Reacher_Diagnostic_Kit.zip` ke folder Download. Di Termux jalankan:

```bash
bash /storage/emulated/0/Download/Reacher_Diagnostic_Kit/RUN_TERMUX.sh
```

Script memasang Python jika diperlukan dan meminta izin penyimpanan standar Termux.
Masukkan ID atau **tautan detail hasil Share** untuk entri Reacher yang dipilih.
Enter memakai ID awal `3620534210064175096`. Masukkan juga satu serial yang sudah bisa
berjalan sebagai pembanding. Untuk memeriksa entri Indonesia, jalankan ulang dengan tautan
Share entri Indonesia; jangan menebak ID dari judul/poster. Tidak perlu memasang APK Mod.

Output `Reacher_Diagnostic_<waktu>_<acak>.zip` langsung berada di Download. Kirim ZIP itu
bersama tautan Share entri Reacher asli/Indonesia/S1-S4 yang tersedia. Jangan kirim token,
cookie, password, raw logcat, atau URL media bertanda tangan.

## Yang dilakukan probe

- Satu host API yang sama dengan katalog plugin; tidak berpindah host/region ketika ditolak.
- Protokol guest katalog **yang sudah ada**, bukan klaim profil runtime APK 50020133.
  Konfigurasi publik diambil dari source commit baseline yang dipatok hash; signing key
  dibaca hanya di memori. Sesi guest baru dan ID instance acak milik probe tidak disimpan.
  Tidak menggunakan profil/device-bound oracle yang tertinggal dalam kode lama.
- Hanya GET: bootstrap guest, `dub-info`, `get`, `season-info`, dan `resource`.
  Tidak meminta `play-info`, v2, start-download atau source video; tidak mengubah pilihan audio.
- Hubungan subject hanya berasal dari `dub-info`, maksimal tiga subject terkait yang masuk
  kategori Asli/Indonesia/Inggris. Tidak melakukan pencarian atau pemadanan judul otomatis.
- S1E1, lalu S1E2 hanya bila season metadata mencantumkannya. Sparse `allEp` didahulukan;
  `maxEp` menjadi rentang indeks saat `allEp` kosong, sesuai APK. Daftar episode **bukan**
  bukti media tersedia. Tidak menggeser season/episode untuk mencari hasil yang berhasil.
- Parameter resource mengikuti kontrak APK; identitas subject dan indeks item diperiksa.
  URL download tidak diperlakukan sebagai stream. HTTP 401/403/441, termasuk code error
  dalam HTTP 200, dihentikan dan dicatat sebagai hambatan akses, bukan resource tidak ada.
- JSON keluaran hanya berisi field yang diizinkan: ID, judul/tahun metadata, hubungan dub,
  path detail tanpa query, indeks season/episode/resource, HTTP/code, struktur/jumlah item.
  Header, body mentah, pesan error mentah, URL media, cookie dan kunci tidak ditulis.
- `media_validation=NOT_TESTED` dan `device_playback=NOT_TESTED` sengaja dipertahankan:
  probe metadata tidak membuktikan manifest/segmen valid atau video berhasil diputar.

## Opsional: log plugin melalui ADB yang sudah diizinkan

Android membatasi log aplikasi lain. Termux biasa **tidak** otomatis dapat membacanya.
Jika Android 11+ menyediakan Wireless debugging, gunakan `pkg install android-tools`,
pasangkan dengan `adb pair ALAMAT_HP:PORT_PAIR`, masukkan kode yang tampil di HP sendiri,
lalu `adb connect ALAMAT_HP:PORT_DEBUG` (kedua port biasanya berbeda). Jangan membagikan kode
pairing. Alternatif: gunakan ADB komputer yang sudah diotorisasi. Tidak perlu root atau MITM.

Setelah `adb devices` menunjukkan perangkat milik Anda tersambung:

```bash
python reacher_diagnostic.py --logs-only --adb-seconds 90 --out /storage/emulated/0/Download
```

Selama 90 detik, buka Reacher S1E1 di CloudStream/AdiXtream dengan plugin versi 15, lalu satu
serial pembanding. Hanya field log Moviebox yang dikenali direkonstruksi: endpoint/status,
subject/se/ep, ketersediaan detailPath, parsed/audioFiltered/candidates/validated. Raw log tidak
pernah disimpan, dan buffer log perangkat tidak dihapus. Jika tidak ada log yang dapat diakses,
hasilnya BLOCKED; ini tidak mengklaim telah menangkap traffic aplikasi MovieBox resmi.

ADB ini membuktikan perilaku plugin terpasang, **bukan request bisnis APK resmi**. Jika probe
masih ditolak dan log plugin belum menjawab pemetaan, bukti berikutnya harus berupa export
metadata/diagnostik yang memang disediakan aplikasi resmi atau log yang sudah disanitasi
melalui mekanisme debug yang diizinkan. Kit tidak melewati TLS pinning atau autentikasi.

## Pengujian pengembang

```bash
python -m unittest discover -s Moviebox/tests/diagnostics -v
```

Fixture sintetis menguji sanitasi, pemetaan subject/episode, berhenti saat akses ditolak,
dan integritas source yang dipatok. Hasilnya tidak membuktikan Reacher dapat streaming.
