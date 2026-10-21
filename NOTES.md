# TV notes

Toshiba 65UA2B63DB, Vestel board ("cosmos"), Android TV 11, MediaTek. 134 packages at the first checkpoint (2026-10-20).

## Debloat plan (not applied yet)

Every command here can be undone. `pm disable-user --user 0` is reversed with `pm enable`, and `pm uninstall -k --user 0` is reversed with `cmd package install-existing`.

### Safe to disable
| Package | What it is |
|---|---|
| `com.vestel.vestelreporter` | Vestel telemetry |
| `fusion.android.tv.demo` | Shop demo mode |
| `com.vestel.tv.eib` | Energy label viewer |
| `android.autoinstalls.config.vestel.cosmos` | Silently installs partner apps at setup |
| `com.cltv.fast` | Free ad-supported channels |
| `com.google.android.videos` | Google TV / Play Movies |
| `com.google.android.play.games` | Play Games |
| `com.google.android.youtube.tvmusic` | YouTube Music |
| `com.google.android.feedback` | Google feedback |
| `com.google.android.syncadapters.calendar` | Calendar sync |
| `com.google.android.apps.nbu.smartconnect.tv` | Phone setup helper |
| `com.google.android.backdrop` | Ambient mode screensaver |
| `com.android.printspooler` | Printing |

### Optional
- **Freeview Play catch-up apps:** `uk.co.freeview.{amc_catchup,amc_horror,uktv,bbc_sounds,explore,onnow,bbc,itv,ch4_vod,ch5,stv}`.
- **Freeview Play plumbing** (`uk.co.freeview.mdsclient`, `fvpconfigauth`, `tifbridge`, `com.mediatek.tv.freeviewplay`): you lose catch-up in the guide. Test afterwards.
- `com.google.android.apps.mediashell` is Chromecast built-in.
- `com.google.android.katniss` is voice search and Assistant, which the mic button needs.
- `com.google.android.tv.remote.service` lets a phone act as the remote.

### Leave alone
- **MediaTek:** `com.mediatek.tvinput`, `tv.service`, `wwtv.tvcenter`, `tvinputservice.arbitratorservice`, `wwtv.setupwizard`, `network`, `wwtv.webview`.
- **Vestel:** `com.vestel.tv.service`, `keycontroller`, `backlight`, `servicepatch`, `speedup`, `rcupdater`, `com.vestel.overlay.*`.
- **Factory and settings:** `mediatek.factorymenu.ui`, `com.mediatek.tv.factory`, `com.android.tv.settings`.
- **Google core:** `gms`, `gsf`, `com.android.vending`, `webview`, `inputmethod.latin` (the only keyboard), `packageinstaller`.
- **Media player:** `com.mediatek.wwtv.mediaplayer` is the USB media player.

## Gotchas met so far
- Play Protect blocks sideloaded apps that target old SDKs. The launcher therefore targets SDK 35 and declares `QUERY_ALL_PACKAGES`.
- The first `adb connect` reports "failed to authenticate" until you accept the prompt on the TV.
- On macOS, Terminal needs Files & Folders → Downloads permission to run scripts from ~/Downloads.
- Only one HDMI port appears to pass CEC device names, so sources rely on names you set yourself.
