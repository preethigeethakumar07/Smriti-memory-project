# Security and privacy at rest

What Smriti keeps on the phone, and how it is protected. Everything stays on the phone: there is no account and no cloud, and the app
has no internet path except to the user's own smart glasses on the local network (`net/LanGuard.kt`, `net/InternetBlocker.kt`).

## What is protected, and how

| What | How |
|---|---|
| The master key | An AES-256 key made inside the phone's secure hardware (the StrongBox chip on the iQOO 15, otherwise the trusted execution environment) by the Android Keystore. It can never be read out of the chip. |
| The vault (`vault.bin`) | A small file, encrypted with the master key (AES-256-GCM), holding two random secrets: the database key and the picture key. Without this phone's secure hardware it opens to nothing. |
| Remembered pictures | Each blurred thumbnail is encrypted with the picture key, AES-256-GCM, with its header as authenticated data; written to a temporary file and renamed, so a stop never leaves a half-written picture. Old plain pictures are converted in the background. Pictures shown in the chat are decrypted in memory only. |
| The memory database | SQLCipher (AES-256) under the database key, used as a raw 256-bit key (no slow password stretching). It holds the captions, what was heard, the chat, the objects seen, camera sessions and the walk guide's saved places and walks. |
| Backups | Off (`allowBackup=false`) and spelled out for Android 12 and later (`data_extraction_rules.xml`): nothing leaves through a backup or a phone-to-phone transfer. |
| The screens | Optional app lock (Settings, Privacy): the phone's own fingerprint, face or PIN prompt when Smriti opens and after 30 seconds away. While it is on, Smriti is hidden from the recent-apps list and from screenshots. The prompt is the platform's, so no fingerprint or face data reaches the app. |
| Faces, numbers, emails | Faces are blurred before analysis and numbers and emails are removed from notes; this cannot be switched off. |
| Undressed scenes | Dropped before they are remembered, described or scanned (Settings, Memory capture). Best effort, see `PLAN-judges-feedback.md`. |
| Wi-Fi places | The networks heard at a saved place are stored only as a hash (5 bytes of SHA-256), never as the address. |
| Telemetry | ML Kit's local usage queue (a database in the app's folder, never sent because there is no internet path) is cleared at every start. |

## Converting an existing database

A phone that ran an earlier version has a plain database. At the first start of the encrypted version, before anything opens it
(`security/DatabaseGuard.kt`):

1. The plain database is exported into an encrypted copy (`sqlcipher_export`).
2. The copy is opened with the key and compared with the original: every table's row count, the schema version and an integrity check
   must match. If anything differs, the copy is thrown away and the plain database stays the database; the next start tries again.
3. The copy takes the original's place; the plain file is kept for the moment.
4. Room opens the encrypted database. Only when that works is the plain file overwritten with zeros and deleted. If it does not work,
   the plain file is put back.
5. If the app is stopped half way, the next start puts the files in order (`DbFiles.recover`, unit-tested).

A database that cannot be opened with this phone's key (the key is lost if the phone's secure hardware is reset) is set aside, still
encrypted, and the app starts fresh instead of failing on every start.

Overwriting with zeros is best effort: flash storage may keep old blocks. The phone's own file-based encryption is the main protection
for deleted files.

## Checked on the phone

On the iQOO 15 (Android 16, StrongBox key), with the real memory of that phone:

* the first attempt at the conversion failed cleanly (a missing "create" flag for the new file); the app kept the plain database, nothing was
  lost, and the failed copy was removed. After the fix the conversion ran at the next start: **1,437 rows in 11 tables** exported, opened with
  the key, matched table by table, put in place, opened by Room, and the plain copy overwritten and deleted;
* afterwards the file starts with a random salt instead of the SQLite header, and neither the database nor its write-ahead log contains a table
  name, a caption or a walk name in readable form;
* the AI status, memory saving, search, the Memory screen, the walk guide and its saved walks all work on the encrypted database;
* a plaintext safety copy was made on the computer before the conversion (and removed afterwards).

Not checked by me: the app lock prompt itself (it needs a person to use a finger, face or PIN), and what happens when the phone's secure hardware is reset.

## What it does not protect against

* Someone holding the **unlocked phone with the app lock off** can open the app.
* The **voice assistant answers when asked**, even with the app lock on. That is a choice: a person who needs Smriti may not be able to
  unlock a screen.
* A **rooted or compromised phone** while the app is running: the keys are in memory then.
* The **developer dashboard** (off by default) shows the pipeline and memory over the local network when LAN mode is switched on; it
  accepts private-network clients only and asks for the PIN shown on the phone. Without LAN mode it is reachable over USB or localhost only.
* Debug builds can be inspected with `adb run-as`; the files are still encrypted, and the keys do not leave the secure hardware.
