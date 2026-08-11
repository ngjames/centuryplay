# airplay 2 protocol documentation (sender reference)

> **status**: verified implementation notes from centuryplay's airplay 2 sender path
> **last updated**: august 2026

this document describes the airplay 2 (ap2) protocol as implemented by the sender (source) side of centuryplay, for audio-only streaming. it complements [docs/AIRPLAY_PROTOCOL.md](AIRPLAY_PROTOCOL.md), which covers airplay 1 / raop. every fact here is grounded in the implementation in `app/src/main/java/com/airplay/streamer/airplay2/` and its jvm unit/integration tests; confirmation against real receiver hardware (homepod, apple tv 4k, shairport-sync + nqptp) is still pending and is tracked in [docs/TESTING_AP2.md](TESTING_AP2.md).

## table of contents

1. [overview](#overview)
2. [pairing](#pairing)
3. [control channel](#control-channel)
4. [audio streaming](#audio-streaming)
5. [timing](#timing)
6. [setup plist reference](#setup-plist-reference)
7. [sources](#sources)

---

## overview

centuryplay acts as an airplay 2 **sender** (source), the same role itunes/music plays on apple platforms. audio-only: no video mirroring, no dacp, no fairplay sapv2 (not required for audio-only streaming).

| property | value |
|----------|-------|
| control protocol | rtsp over plain tcp, port 7000, no tls |
| security | chacha20-poly1305 (hap framing + audio), srp-6a transient pairing, hkdf-sha512 keys |
| audio transport | rtp over udp |
| audio format | uncompressed alac, 44100 hz, 16-bit, stereo |
| timing | ntp (default) or ptp-master (shairport-sync + nqptp) |
| discovery | mdns/bonjour (`_airplay._tcp`, `protocolVersion=2`) |

### protocol flow summary

```
sender (this app)                          receiver
   |                                            |
   |  ---- post /pair-pin-start --------------> |
   |  <---- 200 + pin / status --------------- |
   |                                            |
   |  ---- pair-setup m1 (srp-6a a, tlv8) ----> |
   |  <---- pair-setup m2 (salt, b) ----------- |
   |  ---- pair-setup m3 (proof) -------------> |
   |  <---- pair-setup m4 --------------------- |   session keys derived (hkdf-sha512)
   |                                            |
   |  ---- setup (event plist, timing) -------> |
   |  <---- 200 (eventport) ------------------- |
   |  ---- setup (audio plist, shk) ----------> |
   |  <---- 200 (control/data ports, timing) -- |
   |  ---- flush -----------------------------> |
   |  ---- record ----------------------------> |
   |                                            |
   |  ==== udp: encrypted rtp audio ===========> |
   |  ---- post /feedback keepalive (~30s) ----> |
   |                                            |
   |  ---- teardown ---------------------------> |
```

---

## pairing

pairing is **transient**: a one-session srp-6a exchange, no stored credentials (no m5/m6 flows). this is enough for audio streaming and matches what pyatv, airplay2-rs and owntone senders do.

1. `POST /pair-pin-start` obtains the setup pin (or the user supplies one).
2. `POST /pair-setup` runs srp-6a over tlv8 bodies:
   - m1: `METHOD` 0x00 (pair-setup), `SEQ_NO` 1, `PUBLIC_KEY` (client a), `FLAGS` 0x10 (transient pairing)
   - m2: server salt + public key b
   - m3: `SEQ_NO` 3, client public key a, client proof
   - m4: server proof, verified by the client
3. the resulting shared secret `k` feeds hkdf-sha512 to derive the control-channel keys.

srp-6a uses sha-512 and the rfc 5054 3072-bit group (g=5), not the bouncycastle default srp6client sha-1/1024-bit parameters.

fairplay sapv2 is **not required** for audio-only streaming. caveat: one newer-homepod-firmware case (build 23l471) reportedly demands fairplay; this is contested by the sender implementations referenced below and is not implemented.

---

## control channel

control runs over plain tcp 7000. there is **no tls**. pre-pairing requests (pair-pin-start, pair-setup) are plaintext http; once pairing completes, every subsequent request/response body is encrypted with the hap chacha20-poly1305 framing.

### hap frame format

```
+--------------------+-------------------------+------------------+
| length (2, LE)     | chacha20-poly1305       | tag (16)         |
+--------------------+-------------------------+------------------+
```

- `length`: 2-byte little-endian ciphertext length (aad = this length prefix).
- each frame is at most 1024 bytes.
- nonce: 4 zero bytes + 8-byte little-endian counter, incremented per frame.
- keys: hkdf-sha512 with salt `control-salt`, info `control-write-encryption-key` for what the sender writes and `control-read-encryption-key` for what it reads.

### requests

- `setup` (event channel): binary plist body with `timingProtocol` etc. (see [setup plist reference](#setup-plist-reference)).
- `setup` (audio): binary plist body with the stream params and the shared `shk`.
- `flush` then `record` start the stream.
- `set_parameter` volume: body `volume: <float 0..1>`.
- `POST /feedback` keepalive every ~30s while streaming (body `volume: 0.0` or the current volume); a receiver may drop the session if it goes quiet.
- `teardown` closes the session.

---

## audio streaming

alac is the realtime airplay 2 stream codec. centuryplay sends **uncompressed alac** frames (bit-packed 23-bit header + raw pcm samples), 352 samples per frame, 44100 hz, stereo.

- rtp header: 12 bytes, v=2, payload type 96.
- per-packet encryption: chacha20-poly1305.
  - aad = rtp timestamp (4 bytes) + ssrc (4 bytes)
  - nonce = 4 zero bytes + 8-byte **little-endian packet counter** (increments per packet, independent of the rtp sequence number)
- key = `shk`, the **sender-generated** 32 random bytes placed in the audio `setup` plist; the receiver decrypts with whatever `shk` the sender supplied, so any consistent 32-byte key works.
- pacing: buffer roughly 125 frames (~1s cold) before the first packet, then send at the capture rate.
- anchor (sync) packets tell the receiver when to play; anchors are stamped in the receiver's clock frame (see timing).

---

## timing

two modes, selectable in settings (`auto` = ntp default, `ptp` for shairport-sync).

### ntp (default)

- event `setup` carries `timingProtocol` = `ntp`.
- after audio `setup` the receiver returns a timing port; the sender answers pt 82/83 (0x52 request, 0x53 reply) timing requests.
- clock offset from the exchange: `offset = ((t2 - t1) + (t3 - t4)) / 2`.
- anchors are stamped in the receiver clock frame by adding that offset to the local monotonic time.
- single-room playback works, including homepod / apple tv 4k (airplay2-rs reports ntp-mode works on homepod).

### ptp-master (shairport-sync + nqptp)

- event `setup` carries `timingProtocol` = `ptp`; the sender runs a ptp grandmaster.
- `announce` first, before any sync/follow_up, so nqptp accepts the clock id (nqptp discards sync/follow_up until announce establishes it).
- gptp profile: transport-specific field 0x1; sync/follow_up log message interval 125 ms (0xfd); announce interval 1 s (0x00).
- follow_up carries the 802.1as apple org tlv (org id 00:17:f2, last gm phase/freq change fields), length field 28.
- binds udp 319/320; on unrooted android this falls back to ephemeral source ports. whether nqptp accepts an ephemeral-source grandmaster is the open question; if it does not, ptp on android would require privileged ports (root/shizuku), which is out of scope. ntp is the recommended default.

### ptp-slave (deferred)

ptp-slave mode (receiver as grandmaster, mtrudel's one-way-offset from sync/follow_up) is documented but **not implemented**. ntp mode deliberately does not use the one-way-offset approach.

---

## setup plist reference

### event channel setup

| key | value |
|-----|-------|
| `sessionUUID` | uuid string |
| `deviceID` / `macAddress` | sender mac |
| `timingProtocol` | `"NTP"` or `"PTP"` |
| `timingPeerInfo` | addresses + id |
| `name` | `"centuryplay"` |

response: `eventPort`.

### audio setup

| key | value |
|-----|-------|
| `streams[0].type` | 96 |
| `streams[0].ct` | 2 (alac; ct=1 is pcm) |
| `streams[0].spf` | 352 |
| `streams[0].audioFormat` | 1633771873 (`'alac'` fourcc; not the raop l16 bitmask) |
| `streams[0].shk` | sender-generated 32 bytes |
| `streams[0].latencyMin` / `latencyMax` | 11025 / 88200 |

response: `controlPort`, `dataPort`, timing info.

---

## sources

- [airplay2-rs `AIRPLAY_2_SPEC.md`](https://github.com/zecuse/airplay2-rs)
- [pyatv](https://github.com/postlund/pyatv)
- [shairport-sync](https://github.com/mikebrady/shairport-sync) (`rtp.c` chacha20-poly1305 decrypt layout: aad ts+ssrc, nonce 4z + 8-byte le counter)
- [mtrudel/airplay](https://github.com/mtrudel/airplay) (hex.pm `airplay` package)
- [openairplay/airplay2-receiver](https://github.com/openairplay/airplay2-receiver)

---

## changelog

- **august 2026**: replaced the may 2026 research draft with verified sender-implementation notes (no tls on 7000, chacha20-poly1305 control + audio, hkdf-sha512 control keys, sender-generated shk, uncompressed alac, ntp default / ptp-master, fairplay not required for audio-only).
