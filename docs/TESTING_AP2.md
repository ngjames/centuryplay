# airplay 2 testing runbook

> **status**: integration runbook for validating centuryplay's airplay 2 sender path
> **last updated**: august 2026

this runbook mirrors the evidence recorded for the plan's hardware-gated todos (11 and 15). as of august 2026 the ap2 path is validated by jvm unit/integration tests (82 airplay 2 tests, including a byte-exact mock-receiver integration test) but **on-device streaming against a real receiver is not yet verified** - no receiver hardware (airplay2-receiver, shairport-sync, homepod) was available to the implementing session. this document is the exact procedure to close that gap.

## app settings needed

before any run, set centuryplay's settings:

- protocol preference = **airplay 2** (settings > protocol preference; `airplay_prefs` / `protocol_preference` = 2). `auto` also resolves to ap2 for devices advertising port 7000 / `protocolVersion=2`.
- airplay 2 timing = **auto (ntp)** by default, or **ptp** when testing shairport-sync + nqptp (`airplay_prefs` / `ap2_timing`; 0 = ntp, 1 = ptp).

## general prerequisites

- a physical android device (android 10+) with the debug apk installed:
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- the phone and the receiver on the same lan.
- grant record audio + notification permissions; play a test tone (any local audio or a tone generator).
- start a capture from centuryplay and approve the mediaprojection prompt.

---

## a. airplay2-receiver (python, ntp mode) - primary automated target

the openairplay receiver is the easiest validation target: no special hardware, no ptp, no privileged ports.

### install

```bash
git clone https://github.com/openairplay/airplay2-receiver.git
cd airplay2-receiver
virtualenv -p python3 proto          # or: python3 -m venv proto
source proto/bin/activate
pip install -r requirements.txt
pip install pyaudio
```

on macOS you may also need `brew install portaudio` first (see the repo's readme for the pyaudio build flags).

### run

```bash
python ap2-receiver.py -m myap2 --netiface=en0   # interface name per your machine
```

the receiver advertises itself as `myap2` via mdns. disable the system airplay receiver first on recent macOS (system settings > airplay receiver: off) to avoid mdns conflicts.

### stream from the app

1. set protocol preference = airplay 2, timing = auto (ntp).
2. select `myap2` in the device list, start capture, approve the prompt.
3. confirm audio on the receiver's output device.

### evidence to collect

- receiver console logs: expect a pairing exchange (`pair-pin-start`, `pair-setup`) followed by `setup` (event + audio), `record`, and then audio frames being received / played.
- app logcat:
  ```bash
  adb logcat | rg 'AirPlay2Client|RtspClient|RtpStreamer|AudioCaptureService'
  ```
  expect connect -> pair -> setupStreaming success, anchors sent, no errors.

---

## b. shairport-sync + nqptp (ptp mode) - hardware-conditional

this mirrors the plan's todo-11 runbook (originally recorded as blocked-on-hardware in `.omo/evidence/task-11-centuryplay-airplay2.txt`).

### prerequisites

- a linux receiver machine (e.g. raspberry pi) with shairport-sync built with airplay 2 support and [nqptp](https://github.com/mikebrady/nqptp) v1.1+ running as a service.
- udp ports 319/320 open. note: nqptp is passive - it monitors ptp coming from the airplay source.
- the android device from the general prerequisites.

### steps

1. on the receiver, start nqptp with verbose logging:
   ```bash
   sudo nqptp -vvv 2>&1 | tee /tmp/nqptp.log
   ```
   (or `systemctl journalctl -u nqptp -f`), then start shairport-sync with ap2 enabled:
   ```bash
   shairport-sync -vv 2>&1 | tee /tmp/shairport.log
   ```
2. on the phone set protocol preference = airplay 2 and timing = **ptp**.
3. select the shairport-sync receiver and start capture.
4. collect evidence:
   - `/tmp/nqptp.log`: a line showing a master clock id accepted (nqptp records the source clock; sync/follow_up are discarded until announce establishes the clock id, which is why the sender sends announce first).
   - `/tmp/shairport.log`: the session opening and audio frames being received (playing state).
   - app logcat:
     ```bash
     adb logcat | rg 'AirPlay2Client|PtpMasterClock|RtpStreamer'
     ```
     expect "ptp mode" active, announce/sync/follow_up sent, anchors sent, no errors.
5. pass = audio audible on the receiver **and** nqptp.log shows the phone's clock id **and** shairport.log shows frames received.

### known ptp limitation

on unrooted android the app cannot bind privileged ports 319/320 and falls back to ephemeral source ports. whether nqptp accepts an ephemeral-source grandmaster is the open question (plan draft assumption a6). if nqptp rejects it, ptp on android requires privileged ports (root/shizuku) - out of scope - and **ntp mode is the supported default**. keep the ntp-mode paths (a and c) as the primary validation routes.

---

## c. homepod / apple tv 4k (ntp mode) - best-effort, hardware-conditional

airplay 2 devices from apple are ntp-mode targets; no special setup needed on the device.

1. set protocol preference = airplay 2, timing = auto (ntp).
2. select the homepod / apple tv 4k from the device list and start capture.
3. pass = audio audible on the speaker; app logcat shows a healthy stream (setup -> record -> anchors, no errors).

as of august 2026 this is unverified (blocked-on-hardware). see the caveat in docs/AIRPLAY2_PROTOCOL.md about the contested fairplay requirement on newer homepod firmware (build 23l471).

---

## evidence checklist (any mode)

- app logcat tail showing connect -> pair -> setup (event + audio) -> record -> anchors, no exceptions (save with `adb logcat -d`).
- receiver-side log showing a playing/streaming state (nqptp master-clock line for ptp, or frame-received lines for airplay2-receiver / shairport).
- the setting values used (protocol preference, timing mode).
- date, apk version, receiver version.

reference: plan `.omo/plans/centuryplay-airplay2.md` todos 11 and 15; protocol facts in [docs/AIRPLAY2_PROTOCOL.md](AIRPLAY2_PROTOCOL.md).
