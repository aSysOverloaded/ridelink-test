# Apache RTR 200 4V — cluster navigation protocol (working notes)

Source: TVS Connect APK supplied by the owner (Oct 2026), decompiled with jadx.
Status: **read from the official app's code, not yet tested on the bike.**

## Which code path is the RTR 200 4V?

TVS Connect has two generations of Bluetooth code. The RTR 200 4V family is handled by
the **older** stack (`com.tvsm.connect.bluetooth.bluetoothlegatt`), not the newer
`ApacheBleService` used by newer Apaches.

- Vehicle type `3` = `APACHE_N112`, type `27` = `APACHE_U507`. Both are served by
  `U324HelperService` and the map screen's `"apache507"` branch.
- N112 has crash alert and lean angle on the cluster, which matches the RTR 200 4V's 2019
  SmartXonnect launch features. The newer U507 variant may cover later model years.
- **The app picks the protocol at connect time** from the GATT service UUID the bike
  exposes: if it equals the stored `SERVICE_UUID_U507` → U507 protocol, else → N112.
  Our app should do the same.

## Transport

- BLE GATT, write type 1 (`WRITE_TYPE_NO_RESPONSE`), one custom service with a send and
  a receive characteristic.
- The UUID values are **not in the APK**: they're named `SERVICE_UUID_N112`,
  `SEND_CHARACTERISTIC_UUID_N112`, `RECEIVE_CHARACTERISTIC_UUID_N112` (and `_U507`) and
  delivered at runtime. We'll read them off the bike directly (e.g. nRF Connect).
- **No encryption, no checksum, no handshake** on this path:
  `BaseConnectHelperService.getDecryptedData()` returns data unchanged and
  `U324HelperService` doesn't override it; `isValidFrame()` returns `true`.
  (Contrast: the RR 310 / RTR 310 / U449 services do override it.)
- Frames must fit in 20 bytes unless a larger MTU is negotiated.

## N112 navigation frames (`TVSMMapActivity.N112sendNavigationData`)

Sent on a timer while navigating.

| Frame | Layout |
|---|---|
| Line 1 | `'N'` + distance + unit(1) + pictogram(1) + lineCount(1) + ASCII text + `'$'` |
| Line 2 (only if lineCount = 2) | `'O'` + ASCII text + `'$'` (sent ~100 ms after line 1) |

Distance:
- ≤ 1000 m: unit `0`; value = `round(m / 50) × 500`, i.e. **decimetres in 50 m steps**,
  big-endian, Java `BigInteger.toByteArray()` length (usually 2 bytes).
- > 1000 m: unit `1`; value = km × 10 (one decimal). App sends `Continue` / `Straight`, picto 7.

Special frames:
- Arrived: `N 00 00 00 08 01 "You Arrived" $`
- Rerouting: `N 00 00 00 63 01 <text> $` (picto 99)
- U-turn: `N 00 00 00 29 02 "Make A" $` then `O "U Turn" $` (picto 41)

## U507 navigation frame (`U507New_sendNavigationData`)

Fixed 20 bytes, same layout as the newer `ApacheClusterNavigationData`:

| Byte(s) | Field |
|---|---|
| 0–1 | `5A 4E` |
| 2–3 | distance to manoeuvre, metres (rounded to 50), big-endian |
| 4–5 | minutes to destination, big-endian |
| 6–8 | metres to destination, 3 bytes big-endian |
| 9 | pictogram code |
| 10 | number of text lines (2 if text > 17 chars) |
| 11 | nav status (1 = navigating) |
| 12 | unit (0) |
| 13 | alert flag |
| 14 | prefix (1 = turn ahead, 3/4 = left/right now (< 65 m), 5 = straight > 1 km, 6 = GPS off, 7 = no internet) |
| 15–18 | zero padding |
| 19 | `FF` |

## Pictogram codes (from `res/raw/en_tvs_short_instuctions.json`)

| Code | Meaning | Code | Meaning |
|---|---|---|---|
| 0 | turn left / merge left | 15 | slight left |
| 1 | sharp left | 16 | slight right |
| 2 | keep left (fork) | 21 | continue straight |
| 3 | turn right / merge right | 36 | ferry |
| 4 | sharp right | 41 | U-turn |
| 5 | keep right (fork) | 50–57 | head N, NE, E, SE, S, SW, W, NW |
| 7 | straight / keep middle | 65–71 | roundabout exit 1st–7th (45°…315°) |
| 8 | destination | 99 | rerouting (N112) |

## Example frames (from `encode_nav.py`)

```
N112 turn left in 200 m : 4E 07 D0 00 00 01 "Turn left" 24
N112 arrived            : 4E 00 00 00 08 01 "You Arrived" 24
U507 right in 550 m     : 5A 4E 02 26 00 0C 00 10 CC 03 01 01 00 00 01 00 00 00 00 FF
```

## Still to confirm on the real bike

1. Which service UUID the bike exposes (→ N112 or U507).
2. Whether the cluster needs bonding/pairing before it accepts writes.
3. That a hand-sent frame shows the arrow and distance as expected.
