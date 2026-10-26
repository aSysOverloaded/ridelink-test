# RideLink Test

A tiny test app for putting navigation instructions on the TFT dash of **my own**
TVS Apache RTR 200 4V (2025, 5" TFT). One screen, a few buttons, and a log. It is a
proof-of-concept, not the finished thing: once it shows an arrow on the cluster, the
real Google-Maps app can be built on the same protocol code.

This is interoperability work on a motorcycle I own. It talks only to the bike over
Bluetooth — no TVS account, no TVS servers, no internet permission.

---

## What you need (do this at home, on your personal laptop)

1. **Install Android Studio** (free): https://developer.android.com/studio
   During first launch it downloads the Android SDK automatically — just click through
   the defaults and let it finish. You need internet for this one-time setup.
2. **Enable developer mode on your phone** so you can install the app over USB:
   - Settings → About phone → tap **Build number** 7 times.
   - Settings → System → Developer options → turn on **USB debugging**.
3. A **USB cable** to connect the phone to the laptop.

## Building and installing (about 10 minutes the first time)

1. Unzip this folder somewhere, e.g. your Desktop.
2. In Android Studio: **File → Open**, pick the `RideLinkTest` folder, and wait for it
   to finish "Gradle sync" (progress bar at the bottom). The first sync downloads a few
   build components, so give it a few minutes.
3. Plug in your phone. Allow the "USB debugging" prompt on the phone.
4. Pick your phone from the device dropdown at the top, then press the green **Run ▶**
   button. The app installs and opens on your phone.

## Using it (next to the bike)

1. Turn the bike on so the cluster's Bluetooth is awake, and make sure the TVS Connect
   app is **closed** (two apps can't hold the Bluetooth link at once).
2. Open RideLink Test, allow the Bluetooth permission prompts.
3. Tap **Scan for my bike**, then tap your bike when it appears in the list.
4. Watch the log fill in — it prints the bike's Bluetooth services, which confirms the
   connection.
5. Tap **Turn LEFT in 200 m**. If the arrow and "200 m" show on the TFT, we've proven it.
6. Whatever happens, long-press the log, copy it, and send it back. The log is how we
   fix anything that doesn't work the first time.

## If the arrow doesn't appear

That's expected to be possible on the first try — the exact format may need one small
tweak. The log tells us what to change. The **Try any arrow code** box lets you sweep
through codes (0–120) to see which drawing each one shows on your cluster, which is how
we build the final arrow table for your specific bike.

## Files worth knowing

- `app/src/main/java/dev/ridelink/test/ClusterProtocol.kt` — the message format for the
  cluster (arrows, distance, scrambling, checksum). This is the valuable, bike-specific
  part, and it's the code the final app will reuse.
- `app/src/main/java/dev/ridelink/test/MainActivity.kt` — the screen, buttons and
  Bluetooth connection.
- `PROTOCOL.md` (sent separately) — the written explanation of all of the above.

## Notes

- Keep this for personal use on your own bike.
- Test with the bike stationary.
- The app never changes the cluster's firmware; it only sends the same kind of
  Bluetooth messages the official app sends.
